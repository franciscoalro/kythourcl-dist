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
        "User-Agent" to "Dart/2.19 (dart:io)",
        "Accept" to "application/json",
        "Referer" to "https://supercine-tv.net/"
    )

    override val mainPage = mainPageOf(
        "/wp-json/api/filmes" to "Filmes",
        "/wp-json/api/series" to "Séries",
        "/wp-json/api/animes" to "Animes",
        "/wp-json/api/home" to "Destaques"
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

    private fun parseSearchItem(item: JSONObject): SearchResponse? {
        val id = item.optString("id").ifBlank { item.optString("ID") }
        if (id.isBlank() || id.equals("null", ignoreCase = true)) return null

        val rawTitle = item.optString("title").ifBlank { item.optString("post_title") }
        val title = if (rawTitle.isBlank() || rawTitle.equals("null", ignoreCase = true)) "Sem Título" else rawTitle
        val poster = item.optString("poster").ifBlank { item.optString("image_url") }.ifBlank { item.optString("thumbnail") }
        val typeStr = item.optString("type").ifBlank { item.optString("post_type") }.lowercase()

        val isSeries = typeStr.contains("serie") || typeStr.contains("tv") || typeStr.contains("anime")
        val tvType = if (typeStr.contains("anime")) {
            TvType.Anime
        } else if (isSeries) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        val detailEndpoint = if (isSeries) {
            "/wp-json/api/serieDetail?post_id=$id"
        } else {
            "/wp-json/api/movieDetail?post_id=$id"
        }
        val detailUrl = "$mainUrl$detailEndpoint"

        return if (isSeries) {
            newTvSeriesSearchResponse(title, detailUrl, tvType) {
                this.posterUrl = poster.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            }
        } else {
            newMovieSearchResponse(title, detailUrl, tvType) {
                this.posterUrl = poster.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val itemsList = mutableListOf<SearchResponse>()
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "$mainUrl${request.data}${separator}page=$page&showposts=60&version=64"

        val responseText = safeGet(url)
        if (!responseText.isNullOrBlank()) {
            try {
                if (responseText.trim().startsWith("[")) {
                    val arr = JSONArray(responseText)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        parseSearchItem(obj)?.let { itemsList.add(it) }
                    }
                } else if (responseText.trim().startsWith("{")) {
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
        val url = "$mainUrl/wp-json/api/search?s=$encodedQuery&showposts=30&version=64"
        val responseText = safeGet(url) ?: return emptyList()

        val results = mutableListOf<SearchResponse>()
        try {
            if (responseText.trim().startsWith("[")) {
                val arr = JSONArray(responseText)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    parseSearchItem(obj)?.let { results.add(it) }
                }
            } else if (responseText.trim().startsWith("{")) {
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
            }
        } catch (_: Exception) {}

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val separator = if (url.contains("?")) "&" else "?"
        val requestUrl = if (url.contains("version=")) url else "$url${separator}version=64"
        val responseText = safeGet(requestUrl) ?: throw ErrorLoadingException("Falha ao carregar detalhes")

        val json = JSONObject(responseText)
        val rawTitle = json.optString("title").ifBlank { json.optString("post_title") }
        val title = if (rawTitle.isBlank() || rawTitle.equals("null", ignoreCase = true)) "SuperCine Conteúdo" else rawTitle
        val poster = json.optString("poster").ifBlank { json.optString("image_url") }.ifBlank { json.optString("thumbnail") }
        val banner = json.optString("backdrop").ifBlank { json.optString("banner") }
        val plot = json.optString("description").ifBlank { json.optString("sinopse") }.ifBlank { json.optString("post_content") }
        val year = json.optString("year").ifBlank { json.optString("ano") }.toIntOrNull()
        val ratingVal = json.optString("rating").ifBlank { json.optString("nota") }.toDoubleOrNull()

        val seasonsArr = json.optJSONArray("seasons") ?: json.optJSONArray("temporadas")
        val isSeries = seasonsArr != null && seasonsArr.length() > 0

        if (isSeries) {
            val episodesList = mutableListOf<Episode>()
            for (s in 0 until seasonsArr.length()) {
                val seasonObj = seasonsArr.optJSONObject(s) ?: continue
                val seasonNum = seasonObj.optInt("season", seasonObj.optInt("num", s + 1))
                val epsArr = seasonObj.optJSONArray("episodes") ?: seasonObj.optJSONArray("episodios") ?: continue

                for (e in 0 until epsArr.length()) {
                    val epObj = epsArr.optJSONObject(e) ?: continue
                    val epNum = epObj.optInt("episode", epObj.optInt("num", e + 1))
                    val rawEpTitle = epObj.optString("title").ifBlank { epObj.optString("name") }
                    val epTitle = if (rawEpTitle.isBlank() || rawEpTitle.equals("null", ignoreCase = true)) {
                        "Episódio $epNum"
                    } else {
                        rawEpTitle
                    }
                    val epPoster = epObj.optString("poster").ifBlank { epObj.optString("image") }
                    val epData = epObj.toString()

                    episodesList.add(
                        newEpisode(epData) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = epPoster.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                this.backgroundPosterUrl = banner.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                this.plot = plot.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                this.year = year
                if (ratingVal != null && ratingVal > 0.0) this.score = Score.from10(ratingVal)
            }
        }

        // Caso seja filme, passamos o objeto JSON completo dos dados para o loadLinks
        return newMovieLoadResponse(title, url, TvType.Movie, json.toString()) {
            this.posterUrl = poster.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            this.backgroundPosterUrl = banner.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            this.plot = plot.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            this.year = year
            if (ratingVal != null && ratingVal > 0.0) this.score = Score.from10(ratingVal)
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
            val playerUrls = mutableListOf<String>()

            // 1. Extrai links de players diretos ou arrays de servidores
            val directPlayers = listOf(
                "player", "url", "stream_url", "embed_url", "streamtape",
                "streamwish", "doodstream", "vidhide", "fembed"
            )
            for (key in directPlayers) {
                val link = json.optString(key)
                if (link.isNotBlank() && !link.equals("null", ignoreCase = true) && link.startsWith("http")) {
                    playerUrls.add(link)
                }
            }

            val playersArr = json.optJSONArray("players")
                ?: json.optJSONArray("servers")
                ?: json.optJSONArray("links")
            if (playersArr != null) {
                for (i in 0 until playersArr.length()) {
                    val pObj = playersArr.optJSONObject(i)
                    if (pObj != null) {
                        val pUrl = pObj.optString("url").ifBlank { pObj.optString("link") }
                        if (pUrl.isNotBlank() && !pUrl.equals("null", ignoreCase = true) && pUrl.startsWith("http")) {
                            playerUrls.add(pUrl)
                        }
                    } else {
                        val pUrl = playersArr.optString(i)
                        if (pUrl.isNotBlank() && !pUrl.equals("null", ignoreCase = true) && pUrl.startsWith("http")) {
                            playerUrls.add(pUrl)
                        }
                    }
                }
            }

            // 2. Itera sobre os players e dispara os extratores
            for (playerUrl in playerUrls.distinct()) {
                val success = loadExtractor(playerUrl, subtitleCallback, callback)
                if (success) {
                    foundAny = true
                } else {
                    // Fallback para o endpoint de extração do backend
                    val encoded = try { URLEncoder.encode(playerUrl, "UTF-8") } catch (_: Exception) { playerUrl }
                    val extractorResp = safeGet("$mainUrl/wp-json/site/extractor?url=$encoded")
                    if (!extractorResp.isNullOrBlank()) {
                        try {
                            val extJson = JSONObject(extractorResp)
                            val resolvedUrl = extJson.optString("url").ifBlank { extJson.optString("stream") }
                            if (resolvedUrl.isNotBlank() && !resolvedUrl.equals("null", ignoreCase = true)) {
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = name,
                                        url = resolvedUrl,
                                        type = if (resolvedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "$mainUrl/"
                                    }
                                )
                                foundAny = true
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}

        return foundAny
    }
}
