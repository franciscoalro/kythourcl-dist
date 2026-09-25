package com.SuperCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
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
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "https://supercine-tv.net/"
    )

    override val mainPage = mainPageOf(
        "/wp-json/api/movies?what=launch" to "Filmes - Lançamentos",
        "/wp-json/api/tvshows?what=launch" to "Séries - Lançamentos",
        "/wp-json/api/category?terms=animes" to "Animes",
        "/wp-json/api/category?terms=acao" to "Ação",
        "/wp-json/api/category?terms=comedia" to "Comédia",
        "/wp-json/api/category?terms=drama" to "Drama",
        "/wp-json/api/category?terms=terror" to "Terror",
        "/wp-json/api/category?terms=animacao" to "Animação",
        "/wp-json/api/category?terms=aventura" to "Aventura"
    )

    private suspend fun safeGet(url: String, referer: String = "$mainUrl/"): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl${if (url.startsWith("/")) "" else "/"}$url"
        val headers = defaultHeaders.toMutableMap()
        if (referer.isNotBlank()) {
            headers["Referer"] = referer
        }
        return try {
            val resp = app.get(fullUrl, headers = headers, timeout = 10)
            if (resp.isSuccessful) resp.text else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSearchItem(item: JSONObject): SearchResponse? {
        val id = item.optString("post_id").ifBlank { item.optString("id") }
        val rawTitle = item.optString("title").ifBlank { item.optString("post_title") }
        if (rawTitle.isBlank() || rawTitle.equals("null", ignoreCase = true)) return null

        val title = rawTitle.trim()
        val poster = item.optString("poster").ifBlank { item.optString("image_url") }.ifBlank { item.optString("thumbnail") }
        val backdrop = item.optString("backdrop_path").ifBlank { item.optString("backdrop") }
        val typeStr = item.optString("type").ifBlank { item.optString("post_type") }.lowercase()
        val imdb = item.optString("imdb")
        val year = item.optString("year")
        val imdbRating = item.optString("imdbRating")
        val runtime = item.optString("runtime")

        val catArray = item.optJSONArray("category")
        val genresList = JSONArray()
        if (catArray != null) {
            for (c in 0 until catArray.length()) {
                val cObj = catArray.optJSONObject(c)
                val catName = cObj?.optString("name") ?: catArray.optString(c)
                if (!catName.isNullOrBlank() && !catName.equals("Lançamentos", ignoreCase = true)) {
                    genresList.put(catName)
                }
            }
        }

        val isSeries = typeStr.contains("serie") || typeStr.contains("tv") || typeStr.contains("anime")
        val tvType = if (typeStr.contains("anime")) {
            TvType.Anime
        } else if (isSeries) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        val dataObj = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("poster", poster)
            put("backdrop", backdrop)
            put("type", if (isSeries) "tvshows" else "movies")
            put("imdb", imdb)
            put("year", year)
            put("rating", imdbRating)
            put("runtime", runtime)
            put("genres", genresList)
            put("plot", item.optString("description").ifBlank { item.optString("sinopse") })
        }

        val dataString = dataObj.toString()

        return if (isSeries) {
            newTvSeriesSearchResponse(title, dataString, tvType) {
                this.posterUrl = poster.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            }
        } else {
            newMovieSearchResponse(title, dataString, tvType) {
                this.posterUrl = poster.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val itemsList = mutableListOf<SearchResponse>()
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "$mainUrl${request.data}${separator}version=1.0&origin=web&page=$page"

        val responseText = safeGet(url)
        if (!responseText.isNullOrBlank()) {
            try {
                val root = JSONObject(responseText)
                val dataArr = root.optJSONArray("data")
                    ?: root.optJSONArray("posts")
                    ?: root.optJSONArray("items")
                    ?: root.optJSONArray("results")

                if (dataArr != null) {
                    for (i in 0 until dataArr.length()) {
                        val obj = dataArr.optJSONObject(i) ?: continue
                        parseSearchItem(obj)?.let { itemsList.add(it) }
                    }
                }
            } catch (_: Exception) {}
        }

        return newHomePageResponse(request.name, itemsList, hasNext = itemsList.size >= 10)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = try {
            URLEncoder.encode(query.trim(), "UTF-8")
        } catch (_: Exception) {
            query.trim()
        }
        val url = "$mainUrl/wp-json/api/search?s=$encodedQuery&version=1.0&origin=web"
        val responseText = safeGet(url) ?: return emptyList()

        val results = mutableListOf<SearchResponse>()
        try {
            val root = JSONObject(responseText)
            val dataArr = root.optJSONArray("data")
                ?: root.optJSONArray("posts")
                ?: root.optJSONArray("items")
                ?: root.optJSONArray("results")

            if (dataArr != null) {
                for (i in 0 until dataArr.length()) {
                    val obj = dataArr.optJSONObject(i) ?: continue
                    parseSearchItem(obj)?.let { results.add(it) }
                }
            }
        } catch (_: Exception) {}

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val itemJson = if (url.trim().startsWith("{")) {
            try { JSONObject(url) } catch (_: Exception) { JSONObject() }
        } else {
            JSONObject().apply { put("imdb", url.substringAfter("imdb=").substringBefore("&")) }
        }

        val id = itemJson.optString("id")
        val title = itemJson.optString("title", "SuperCine Conteúdo")
        val poster = itemJson.optString("poster")
        val backdrop = itemJson.optString("backdrop")
        var plot = itemJson.optString("plot")
        val year = itemJson.optString("year").toIntOrNull()
        val ratingVal = itemJson.optString("rating").toDoubleOrNull()
        val runtimeVal = itemJson.optString("runtime").toIntOrNull()
        val imdb = itemJson.optString("imdb")
        val type = itemJson.optString("type", "movies")
        val isSeries = type.contains("tv") || type.contains("serie") || type.contains("anime")

        val genresList = mutableListOf<String>()
        val rawGenres = itemJson.optJSONArray("genres")
        if (rawGenres != null) {
            for (g in 0 until rawGenres.length()) {
                val gName = rawGenres.optString(g)
                if (gName.isNotBlank()) genresList.add(gName)
            }
        }

        var tmdbId = ""
        // Resolve TMDB ID from post redirect if available
        if (id.isNotBlank()) {
            try {
                val pUrl = "$mainUrl/?p=$id"
                val resp = app.get(pUrl, headers = defaultHeaders, followRedirects = true, timeout = 5)
                val finalUrl = resp.url
                val tmdbMatch = Regex("""/(?:movies|tvshows)/(\d+)""").find(finalUrl)
                if (tmdbMatch != null) {
                    tmdbId = tmdbMatch.groupValues[1]
                }
            } catch (_: Exception) {}
        }

        // Fetch synopsis and metadata from TMDb in Portuguese if plot is empty
        if (plot.isBlank() && tmdbId.isNotBlank()) {
            try {
                val tmdbType = if (isSeries) "tv" else "movie"
                val tmdbUrl = "https://www.themoviedb.org/$tmdbType/$tmdbId?language=pt-BR"
                val tmdbResp = app.get(tmdbUrl, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept-Language" to "pt-BR,pt;q=0.9"
                ), timeout = 5)
                if (tmdbResp.isSuccessful) {
                    val doc = tmdbResp.document
                    val ov = doc.selectFirst(".overview p, div.overview p, meta[property='og:description'], meta[name='description']")
                    val ovText = if (ov?.tagName() == "meta") ov.attr("content") else ov?.text()
                    if (!ovText.isNullOrBlank() && !ovText.startsWith("http")) {
                        plot = ovText.trim()
                    }
                    if (genresList.isEmpty()) {
                        doc.select(".genres a, span.genres a").forEach {
                            val gText = it.text().trim()
                            if (gText.isNotBlank()) genresList.add(gText)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (isSeries) {
            val episodesList = mutableListOf<Episode>()
            val embedUrl = "$mainUrl/embed-api/?imdb=$imdb&type=tvshows"
            val embedHtml = safeGet(embedUrl)

            var tmdb = tmdbId
            if (tmdb.isBlank() && !embedHtml.isNullOrBlank()) {
                val tmdbMatch = Regex("""tmdb\s*=\s*["']?(\d+)["']?""").find(embedHtml)
                tmdb = tmdbMatch?.groupValues?.get(1) ?: ""
            }

            if (tmdb.isNotBlank()) {
                val seasonsUrl = "$mainUrl/wp-json/api/tvshows?what=seasons&tmdb=$tmdb&version=1.0&origin=web"
                val seasonsText = safeGet(seasonsUrl)
                if (!seasonsText.isNullOrBlank()) {
                    try {
                        val sRoot = JSONObject(seasonsText)
                        val seasonsArr = sRoot.optJSONArray("seasons")
                        if (seasonsArr != null) {
                            for (s in 0 until seasonsArr.length()) {
                                val sObj = seasonsArr.optJSONObject(s) ?: continue
                                val seasonNum = sObj.optInt("season", s + 1)
                                val epsArr = sObj.optJSONArray("episodes") ?: continue

                                for (e in 0 until epsArr.length()) {
                                    val epObj = epsArr.optJSONObject(e) ?: continue
                                    val epNum = epObj.optInt("ep", e + 1)
                                    val rawEpTitle = epObj.optString("title")
                                    val epTitle = if (rawEpTitle.isBlank() || rawEpTitle.equals("null", ignoreCase = true)) {
                                        "Episódio $epNum"
                                    } else {
                                        rawEpTitle
                                    }
                                    val epPoster = epObj.optString("backdrop")

                                    val epDataObj = JSONObject().apply {
                                        put("type", "episode")
                                        put("tmdb", tmdb)
                                        put("season", seasonNum)
                                        put("episode", epNum)
                                        put("imdb", imdb)
                                    }

                                    episodesList.add(
                                        newEpisode(epDataObj.toString()) {
                                            this.name = epTitle
                                            this.season = seasonNum
                                            this.episode = epNum
                                            this.posterUrl = epPoster.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                        }
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            return newTvSeriesLoadResponse(title, url, if (type.contains("anime")) TvType.Anime else TvType.TvSeries, episodesList) {
                this.posterUrl = poster.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                this.backgroundPosterUrl = backdrop.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                this.plot = plot.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                this.tags = genresList.takeIf { it.isNotEmpty() }
                this.year = year
                this.duration = runtimeVal
                if (ratingVal != null && ratingVal > 0.0) this.score = Score.from10(ratingVal)
            }
        }

        val movieData = JSONObject().apply {
            put("type", "movie")
            put("imdb", imdb)
            put("title", title)
        }

        return newMovieLoadResponse(title, url, TvType.Movie, movieData.toString()) {
            this.posterUrl = poster.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            this.backgroundPosterUrl = backdrop.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            this.plot = plot.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            this.tags = genresList.takeIf { it.isNotEmpty() }
            this.year = year
            this.duration = runtimeVal
            if (ratingVal != null && ratingVal > 0.0) this.score = Score.from10(ratingVal)
        }
    }

    private fun unpack(packedJs: String): String {
        val regex = Regex("""\}\s*\(\s*['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split\(['"]\|['"]\)""")
        val match = regex.find(packedJs) ?: return packedJs
        val (pRaw, aStr, cStr, kRaw) = match.destructured
        var p = pRaw
        val a = aStr.toIntOrNull() ?: return packedJs
        var c = cStr.toIntOrNull() ?: return packedJs
        val k = kRaw.split("|")

        fun getToken(index: Int, radix: Int): String {
            val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            fun encode(num: Int, base: Int): String {
                return if (num <= 0) "" else encode(num / base, base) + chars[num % base]
            }
            return if (index < radix) {
                chars[index].toString()
            } else {
                encode(index, radix)
            }
        }

        while (c > 0) {
            c--
            if (c < k.size && k[c].isNotBlank()) {
                val token = if (a == 16) {
                    Integer.toHexString(c)
                } else if (a == 10) {
                    c.toString()
                } else {
                    getToken(c, a)
                }
                p = p.replace(Regex("""\b$token\b"""), k[c])
            }
        }
        return p
    }

    private suspend fun resolveAndExtract(
        targetUrl: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val urlsToTry = mutableListOf(targetUrl)

        if (targetUrl.contains("tln-earn.top/v/")) {
            val id = targetUrl.substringAfter("tln-earn.top/v/").substringBefore("?")
            urlsToTry.add("https://tln-hg.top/e/$id")
        } else if (targetUrl.contains("tln-earn.top/e/")) {
            val id = targetUrl.substringAfter("tln-earn.top/e/").substringBefore("?")
            urlsToTry.add("https://tln-hg.top/e/$id")
        }

        for (u in urlsToTry) {
            try {
                val extSuccess = loadExtractor(u, subtitleCallback, callback)
                if (extSuccess) {
                    found = true
                    break
                }

                val pageHtml = safeGet(u, referer = "https://supercine-tv.net/")
                if (!pageHtml.isNullOrBlank()) {
                    val unpacked = unpack(pageHtml)

                    val vttRegex = Regex("""(https?://[^\s"'<>]+\.(?:vtt|srt)[^\s"'<>]*)""")
                    vttRegex.findAll(unpacked).forEach { vttMatch ->
                        val subUrl = vttMatch.groupValues[1]
                        val subLang = if (subUrl.contains("_por") || subUrl.contains("pt")) "Português"
                        else if (subUrl.contains("_eng") || subUrl.contains("en")) "Inglês"
                        else if (subUrl.contains("_spa") || subUrl.contains("es")) "Espanhol"
                        else "Legenda"
                        subtitleCallback(newSubtitleFile(subLang, subUrl))
                    }

                    val streamRegex = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""")
                    val matchedStreams = streamRegex.findAll(unpacked).map { it.groupValues[1] }.distinct().toList()

                    for (streamUrl in matchedStreams) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$serverName ${if (streamUrl.contains(".m3u8")) "HLS" else "MP4"}",
                                url = streamUrl,
                                type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = u
                                this.headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                                    "Referer" to u
                                )
                            }
                        )
                        found = true
                    }

                    if (found) break
                }
            } catch (_: Exception) {}
        }

        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false
        try {
            val json = if (data.trim().startsWith("{")) JSONObject(data) else JSONObject()
            val dataType = json.optString("type", "movie")
            val imdb = json.optString("imdb")

            val playerTokens = mutableListOf<Pair<String, String>>()

            if (dataType == "episode") {
                val tmdb = json.optString("tmdb")
                val season = json.optInt("season", 1)
                val episode = json.optInt("episode", 1)

                val playerApiUrl = "$mainUrl/wp-json/api/tvshows?what=player&tmdb=$tmdb&season=$season&episode=$episode&version=1.0&origin=web"
                val playerResp = safeGet(playerApiUrl)
                if (!playerResp.isNullOrBlank()) {
                    val pRoot = JSONObject(playerResp)
                    val pArr = pRoot.optJSONArray("players")
                    if (pArr != null) {
                        for (i in 0 until pArr.length()) {
                            val pObj = pArr.optJSONObject(i) ?: continue
                            val token = pObj.optString("url")
                            val title = pObj.optString("title", "Player ${i + 1}")
                            val lang = pObj.optString("lang")
                            val nameTag = if (lang.isNotBlank()) "$title ($lang)" else title
                            if (token.isNotBlank()) {
                                playerTokens.add(nameTag to token)
                            }
                        }
                    }
                }
            } else {
                val embedUrl = "$mainUrl/embed-api/?imdb=$imdb&type=movies"
                val embedHtml = safeGet(embedUrl)
                if (!embedHtml.isNullOrBlank()) {
                    val regexServer = Regex("""data-server=["']([^"']+)["']""")
                    val matches = regexServer.findAll(embedHtml).map { it.groupValues[1] }.distinct().toList()
                    matches.forEachIndexed { index, token ->
                        playerTokens.add("Player ${index + 1}" to token)
                    }
                }
            }

            for ((serverName, token) in playerTokens) {
                val embedActionUrl = "$mainUrl/embed-api/?action=embed&url=$token"
                val actionHtml = safeGet(embedActionUrl, referer = "$mainUrl/embed-api/?imdb=$imdb")
                if (!actionHtml.isNullOrBlank()) {
                    val locRegex = Regex("""window\.location\.href\s*=\s*["']([^"']+)["']""")
                    val iframeRegex = Regex("""<iframe[^>]*src=["']([^"']+)["']""")
                    val locTarget = locRegex.find(actionHtml)?.groupValues?.get(1)
                    val iframeTarget = iframeRegex.find(actionHtml)?.groupValues?.get(1)

                    val targetUrl = locTarget ?: iframeTarget
                    if (!targetUrl.isNullOrBlank() && !targetUrl.contains("supercine-tv.net")) {
                        val success = resolveAndExtract(targetUrl, serverName, subtitleCallback, callback)
                        if (success) foundAny = true
                    }
                }
            }
        } catch (_: Exception) {}

        return foundAny
    }
}
