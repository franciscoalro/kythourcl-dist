package com.SuperCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

class SuperCine : MainAPI() {
    override var name = "SuperCine"
    override var mainUrl = "https://supercine-tv.net"
    override var lang = "pt"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "https://supercine-tv.net/"
    )

    override val mainPage = mainPageOf(
        "movies?what=launch" to "Filmes (Lançamentos)",
        "tvshows?what=launch" to "Séries (Lançamentos)",
        "category?terms=animes" to "Animes"
    )

    private suspend fun safeGet(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl${if (url.startsWith("/")) "" else "/"}$url"
        return try {
            val resp = app.get(fullUrl, headers = defaultHeaders, timeout = 15)
            if (resp.isSuccessful) resp.text else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseItem(item: JSONObject): SearchResponse? {
        val imdb = item.optString("imdb").ifBlank { item.optString("post_id") }
        if (imdb.isBlank() || imdb.equals("null", ignoreCase = true)) return null

        val rawTitle = item.optString("title").ifBlank { item.optString("post_title") }
        if (rawTitle.isBlank() || rawTitle.equals("null", ignoreCase = true)) return null
        val title = Jsoup.parse(rawTitle).text()

        val poster = item.optString("poster").ifBlank { item.optString("image_url") }
        val backdrop = item.optString("backdrop_path").ifBlank { item.optString("backdrop") }
        val type = item.optString("type").lowercase()
        val catStr = item.optString("category")

        val isAnime = catStr.contains("anime", ignoreCase = true)
        val isSeries = type == "tvshows" || type.contains("serie") || catStr.contains("série", ignoreCase = true) || isAnime
        val tvType = if (isAnime) TvType.Anime else if (isSeries) TvType.TvSeries else TvType.Movie

        val year = item.optString("year").toIntOrNull()
        val rating = item.optString("imdbRating").toDoubleOrNull()

        val itemData = JSONObject().apply {
            put("title", title)
            put("poster", poster)
            put("backdrop", backdrop)
            put("imdb", imdb)
            put("type", if (isSeries) "tvshows" else "movies")
            put("year", year ?: 0)
            put("rating", rating ?: 0.0)
        }.toString()

        return if (isSeries) {
            newTvSeriesSearchResponse(title, itemData, tvType) {
                this.posterUrl = poster.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                this.year = year
                if (rating != null && rating > 0.0) this.score = Score.from10(rating)
            }
        } else {
            newMovieSearchResponse(title, itemData, tvType) {
                this.posterUrl = poster.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                this.year = year
                if (rating != null && rating > 0.0) this.score = Score.from10(rating)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val itemsList = mutableListOf<SearchResponse>()
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "$mainUrl/wp-json/api/${request.data}${separator}page=$page&showposts=21&version=1.0&origin=web"

        val responseText = safeGet(url)
        if (!responseText.isNullOrBlank()) {
            try {
                val root = JSONObject(responseText)
                val dataArr = root.optJSONArray("data")
                if (dataArr != null) {
                    for (i in 0 until dataArr.length()) {
                        val obj = dataArr.optJSONObject(i) ?: continue
                        parseItem(obj)?.let { itemsList.add(it) }
                    }
                }
            } catch (_: Exception) {}
        }

        return newHomePageResponse(request.name, itemsList, hasNext = itemsList.size >= 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = try {
            URLEncoder.encode(query.trim(), "UTF-8")
        } catch (_: Exception) {
            query.trim()
        }
        val url = "$mainUrl/wp-json/api/search?s=$encodedQuery&showposts=21&version=1.0&origin=web"
        val responseText = safeGet(url) ?: return emptyList()

        val results = mutableListOf<SearchResponse>()
        try {
            val root = JSONObject(responseText)
            val dataArr = root.optJSONArray("data")
            if (dataArr != null) {
                for (i in 0 until dataArr.length()) {
                    val obj = dataArr.optJSONObject(i) ?: continue
                    parseItem(obj)?.let { results.add(it) }
                }
            }
        } catch (_: Exception) {}

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        var imdb = ""
        var type = "movies"
        var title = "SuperCine"
        var poster: String? = null
        var backdrop: String? = null
        var year: Int? = null
        var scoreVal: Double? = null

        if (url.trim().startsWith("{")) {
            try {
                val json = JSONObject(url)
                imdb = json.optString("imdb")
                type = json.optString("type", "movies")
                title = json.optString("title", "SuperCine")
                poster = json.optString("poster").takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                backdrop = json.optString("backdrop").takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                year = json.optInt("year").takeIf { it > 0 }
                scoreVal = json.optDouble("rating").takeIf { !it.isNaN() && it > 0.0 }
            } catch (_: Exception) {}
        } else {
            imdb = Regex("""imdb=([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: url
            type = if (url.contains("type=tvshows") || url.contains("tvshows") || url.contains("serie")) "tvshows" else "movies"
        }

        val embedUrl = "$mainUrl/embed-api/?imdb=$imdb&type=$type"
        val embedHtml = safeGet(embedUrl) ?: ""

        val isSeries = type == "tvshows"

        if (isSeries) {
            val tmdbMatch = Regex("""tmdb\s*=\s*["']?(\d+)""").find(embedHtml)
            val tmdb = tmdbMatch?.groupValues?.get(1)

            val episodesList = mutableListOf<Episode>()
            if (!tmdb.isNullOrBlank()) {
                val seasonsUrl = "$mainUrl/wp-json/api/tvshows?what=seasons&tmdb=$tmdb&version=1.0&origin=web"
                val seasonsText = safeGet(seasonsUrl)
                if (!seasonsText.isNullOrBlank()) {
                    try {
                        val sJson = JSONObject(seasonsText)
                        val seasonsArr = sJson.optJSONArray("seasons")
                        if (seasonsArr != null) {
                            for (s in 0 until seasonsArr.length()) {
                                val sObj = seasonsArr.optJSONObject(s) ?: continue
                                val seasonNum = sObj.optInt("season", s + 1)
                                val epsArr = sObj.optJSONArray("episodes") ?: continue
                                for (e in 0 until epsArr.length()) {
                                    val epObj = epsArr.optJSONObject(e) ?: continue
                                    val epNum = epObj.optInt("ep", e + 1)
                                    val rawEpTitle = epObj.optString("title").ifBlank { "Episódio $epNum" }
                                    val epTitle = Jsoup.parse(rawEpTitle).text()
                                    val epBackdrop = epObj.optString("backdrop").takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

                                    val epData = JSONObject().apply {
                                        put("type", "tvshows")
                                        put("tmdb", tmdb)
                                        put("season", seasonNum)
                                        put("episode", epNum)
                                    }.toString()

                                    episodesList.add(
                                        newEpisode(epData) {
                                            this.name = epTitle
                                            this.season = seasonNum
                                            this.episode = epNum
                                            this.posterUrl = epBackdrop
                                        }
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                if (scoreVal != null && scoreVal > 0.0) this.score = Score.from10(scoreVal)
            }
        }

        // Movie
        val serverTokens = Regex("""data-(?:server|url)=["']([^"']+)["']""").findAll(embedHtml)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        val movieData = JSONObject().apply {
            put("type", "movies")
            put("imdb", imdb)
            put("tokens", JSONArray(serverTokens))
        }.toString()

        return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            if (scoreVal != null && scoreVal > 0.0) this.score = Score.from10(scoreVal)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false
        try {
            val json = JSONObject(data)
            val type = json.optString("type")

            if (type == "tvshows") {
                val tmdb = json.optString("tmdb")
                val season = json.optInt("season", 1)
                val episode = json.optInt("episode", 1)
                val playerUrl = "$mainUrl/wp-json/api/tvshows?what=player&tmdb=$tmdb&season=$season&episode=$episode&version=1.0&origin=web"
                val pText = safeGet(playerUrl)
                if (!pText.isNullOrBlank()) {
                    val pJson = JSONObject(pText)
                    val players = pJson.optJSONArray("players")
                    if (players != null) {
                        for (i in 0 until players.length()) {
                            val p = players.optJSONObject(i) ?: continue
                            val token = p.optString("url")
                            val pTitle = p.optString("title").ifBlank { "Player ${i + 1}" }
                            val pLang = p.optString("lang")
                            val displayName = if (pLang.isNotBlank()) "$name - $pTitle ($pLang)" else "$name - $pTitle"
                            if (resolveToken(token, displayName, subtitleCallback, callback)) {
                                foundAny = true
                            }
                        }
                    }
                }
            } else {
                val tokensArr = json.optJSONArray("tokens")
                if (tokensArr != null) {
                    for (i in 0 until tokensArr.length()) {
                        val token = tokensArr.optString(i) ?: continue
                        val displayName = "$name - Player ${i + 1}"
                        if (resolveToken(token, displayName, subtitleCallback, callback)) {
                            foundAny = true
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return foundAny
    }

    private suspend fun resolveToken(
        token: String,
        displayName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (token.isBlank()) return false
        val embedUrl = "$mainUrl/embed-api/?action=embed&url=$token"
        val html = safeGet(embedUrl) ?: return false

        val streamUrl = Regex("""(?:location\.href|src)\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return false
        val cleanUrl = streamUrl.replace("&amp;", "&")

        if (cleanUrl.contains("sub1=")) {
            val subMatch = Regex("""sub1=([^&]+)""").find(cleanUrl)
            val subUrl = subMatch?.groupValues?.get(1)?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            }
            if (!subUrl.isNullOrBlank()) {
                subtitleCallback(newSubtitleFile("Português", subUrl))
            }
        }

        val success = loadExtractor(cleanUrl, subtitleCallback, callback)
        if (success) {
            return true
        }

        if (cleanUrl.contains(".m3u8")) {
            callback(
                newExtractorLink(
                    source = name,
                    name = displayName,
                    url = cleanUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                }
            )
            return true
        } else if (cleanUrl.contains(".mp4")) {
            callback(
                newExtractorLink(
                    source = name,
                    name = displayName,
                    url = cleanUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                }
            )
            return true
        }

        return false
    }
}
