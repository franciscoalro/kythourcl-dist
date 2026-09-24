package com.VeloPlayTV

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class VeloPlayTV : MainAPI() {
    override var name = "Velo Play TV"
    override var mainUrl = "https://fastcdn.bond/velo/tv"
    override var lang = "pt"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Live
    )

    private val userAgent = "Velo Play/1.2.0 (Linux; Android 11; redroid11_x86_64) com.global.veloplaytv/10200"
    private val posterHeadersMap = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "https://fastcdn.bond/"
    )

    private var authToken: String? = null

    private suspend fun getAuthHeaders(): Map<String, String> {
        var token = authToken
        if (token.isNullOrBlank()) {
            token = performLogin()
        }
        return mapOf(
            "User-Agent" to userAgent,
            "Accept-Language" to "pt-BR",
            "Authorization" to (if (!token.isNullOrBlank()) "Bearer $token" else "")
        )
    }

    private suspend fun performLogin(): String? {
        try {
            val payload = JSONObject().apply {
                put("canal_code", "75qC")
                put("device_id", "ee612a7d-a900-4860-91ad-5a31468cc312")
                put("password", "pbkdf2_sha256\$1200000\$TiUO0Gu2Ttcb45XR4xPvmC\$Ry1LAe8SayrzAhKgyp0PIMSp9HDnjXbQ2JRC2OFpL+0=")
                put("username", "sZJ3Z9pEyN")
            }.toString()

            val resp = app.post(
                "$mainUrl/user/v1/login",
                requestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaType()),
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Content-Type" to "application/json; charset=UTF-8",
                    "Accept-Language" to "pt-BR"
                ),
                timeout = 15
            )
            if (resp.isSuccessful) {
                val json = JSONObject(resp.text)
                val access = json.optJSONObject("token")?.optString("access")
                if (!access.isNullOrBlank()) {
                    authToken = access
                    return access
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private suspend fun safeGet(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl${if (url.startsWith("/")) "" else "/"}$url"
        return try {
            val headers = getAuthHeaders()
            var resp = app.get(fullUrl, headers = headers, timeout = 15)
            if (resp.code == 401) {
                performLogin()
                val newHeaders = getAuthHeaders()
                resp = app.get(fullUrl, headers = newHeaders, timeout = 15)
            }
            if (resp.isSuccessful) resp.text else null
        } catch (_: Exception) {
            null
        }
    }

    override val mainPage = mainPageOf(
        "/mar/v1/category/nELh/recommendations" to "Filmes",
        "/mar/v1/category/FFDE/recommendations" to "Séries",
        "/mar/v1/category/r5Vv/recommendations" to "Infantil",
        "/mar/v1/category/hgKe/recommendations" to "Animes",
        "/stella/v1/channels" to "Canais Ao Vivo"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val isLive = path.contains("stella") || path.contains("channels")

        if (isLive) {
            val liveItems = mutableListOf<SearchResponse>()
            val raw = safeGet(path)
            if (!raw.isNullOrBlank()) {
                try {
                    val json = JSONObject(raw)
                    val channels = json.optJSONArray("channels") ?: JSONArray()
                    for (i in 0 until channels.length()) {
                        val ch = channels.optJSONObject(i) ?: continue
                        val id = ch.optString("_id")
                        if (id.isBlank()) continue
                        val name = ch.optString("display_name", "Canal Ao Vivo")
                        val logo = ch.optString("logo").ifBlank { ch.optString("poster") }
                        
                        var streamLink = "$mainUrl/stella/v1/channel/$id/play"
                        val streams = ch.optJSONArray("streams")
                        if (streams != null && streams.length() > 0) {
                            val firstStream = streams.optJSONObject(0)
                            val directUrl = firstStream?.optString("url")
                            if (!directUrl.isNullOrBlank()) {
                                streamLink = directUrl
                            }
                        }

                        liveItems.add(
                            newLiveSearchResponse(name, streamLink, TvType.Live) {
                                this.posterUrl = logo
                                this.posterHeaders = posterHeadersMap
                            }
                        )
                    }
                } catch (_: Exception) {}
            }

            return newHomePageResponse(
                request.name,
                liveItems,
                hasNext = false
            )
        }

        val homeItems = mutableListOf<SearchResponse>()
        val raw = safeGet(path)

        if (!raw.isNullOrBlank()) {
            try {
                val json = JSONObject(raw)
                val slots = json.optJSONArray("slots") ?: JSONArray()
                for (s in 0 until slots.length()) {
                    val slot = slots.optJSONObject(s) ?: continue
                    val items = slot.optJSONArray("items") ?: JSONArray()
                    for (it in 0 until items.length()) {
                        val item = items.optJSONObject(it) ?: continue
                        val id = item.optString("refer")
                        val title = item.optString("title")
                        val pic = item.optString("pic")
                        if (id.isBlank() || title.isBlank()) continue

                        val isSeries = path.contains("FFDE") || path.contains("hgKe") || title.contains("Temp.", ignoreCase = true)
                        val type = if (isSeries) TvType.TvSeries else TvType.Movie

                        if (type == TvType.TvSeries) {
                            homeItems.add(
                                newTvSeriesSearchResponse(title, "$mainUrl/mar/v1/asset/$id/detail", TvType.TvSeries) {
                                    this.posterUrl = pic
                                    this.posterHeaders = posterHeadersMap
                                }
                            )
                        } else {
                            homeItems.add(
                                newMovieSearchResponse(title, "$mainUrl/mar/v1/asset/$id/detail", TvType.Movie) {
                                    this.posterUrl = pic
                                    this.posterHeaders = posterHeadersMap
                                }
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return newHomePageResponse(
            request.name,
            homeItems,
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val searchItems = mutableListOf<SearchResponse>()
        val encodedQuery = try {
            URLEncoder.encode(trimmed, "UTF-8")
        } catch (_: Exception) {
            trimmed
        }

        val path = "/mar/v1/asset/search?q=$encodedQuery&page=1&page_size=30"
        val raw = safeGet(path) ?: return emptyList()

        try {
            val json = JSONObject(raw)
            val assets = json.optJSONArray("assets") ?: JSONArray()

            for (i in 0 until assets.length()) {
                val item = assets.optJSONObject(i) ?: continue
                val id = item.optString("_id")
                val title = item.optString("title")
                if (id.isBlank() || title.isBlank()) continue

                val itemType = item.optString("_type")
                val seriesStatus = item.optString("series_status")
                val isSeries = itemType.equals("SEASON", ignoreCase = true) || seriesStatus.isNotBlank() || title.contains("Temp.", ignoreCase = true)

                var poster: String? = null
                val postersArr = item.optJSONArray("posters")
                if (postersArr != null && postersArr.length() > 0) {
                    poster = postersArr.optString(0)
                }

                if (isSeries) {
                    searchItems.add(
                        newTvSeriesSearchResponse(title, "$mainUrl/mar/v1/asset/$id/detail", TvType.TvSeries) {
                            this.posterUrl = poster
                            this.posterHeaders = posterHeadersMap
                        }
                    )
                } else {
                    searchItems.add(
                        newMovieSearchResponse(title, "$mainUrl/mar/v1/asset/$id/detail", TvType.Movie) {
                            this.posterUrl = poster
                            this.posterHeaders = posterHeadersMap
                        }
                    )
                }
            }
        } catch (_: Exception) {}

        return searchItems
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("stella") || url.contains("channel") || url.contains(".m3u8")) {
            return newLiveStreamLoadResponse("Canal Ao Vivo", url, url)
        }

        val raw = safeGet(url) ?: throw ErrorLoadingException("Não foi possível carregar detalhes do item")
        val json = JSONObject(raw)
        val asset = json.optJSONObject("asset") ?: throw ErrorLoadingException("Formato de resposta inválido")

        val id = asset.optString("_id")
        val title = asset.optString("title", "Sem Título")
        val plot = asset.optString("description")
        val releaseAt = asset.optString("release_at")
        val year = releaseAt.take(4).toIntOrNull()
        val assetType = asset.optString("_type")

        val tags = mutableListOf<String>()
        val tagsArr = asset.optJSONArray("tags")
        if (tagsArr != null) {
            for (i in 0 until tagsArr.length()) {
                val t = tagsArr.optString(i)
                if (t.isNotBlank()) tags.add(t)
            }
        }

        var posterUrl: String? = null
        val imagesArr = asset.optJSONArray("images")
        if (imagesArr != null) {
            // Priority: icon (vertical) -> poster (horizontal) -> first available
            for (i in 0 until imagesArr.length()) {
                val img = imagesArr.optJSONObject(i) ?: continue
                if (img.optString("type") == "icon") {
                    posterUrl = img.optString("url")
                    break
                }
            }
            if (posterUrl.isNullOrBlank()) {
                for (i in 0 until imagesArr.length()) {
                    val img = imagesArr.optJSONObject(i) ?: continue
                    if (img.optString("type") == "poster") {
                        posterUrl = img.optString("url")
                        break
                    }
                }
            }
            if (posterUrl.isNullOrBlank() && imagesArr.length() > 0) {
                posterUrl = imagesArr.optJSONObject(0)?.optString("url")
            }
        }

        val childrenObj = asset.optJSONObject("children")
        val childrenArr = childrenObj?.optJSONArray("items")
        val brothersArr = asset.optJSONArray("brothers")

        val isSeries = assetType.equals("SEASON", ignoreCase = true) ||
                (childrenArr != null && childrenArr.length() > 0) ||
                (brothersArr != null && brothersArr.length() > 0)

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            if (childrenArr != null && childrenArr.length() > 0) {
                for (i in 0 until childrenArr.length()) {
                    val child = childrenArr.optJSONObject(i) ?: continue
                    val childId = child.optString("_id")
                    if (childId.isBlank()) continue
                    val epNum = child.optInt("seq", i + 1)
                    val epTitle = child.optString("title").ifBlank { "Episódio $epNum" }

                    episodes.add(
                        newEpisode("$mainUrl/mar/v1/asset/$childId/playinfo") {
                            this.name = epTitle
                            this.episode = epNum
                            this.season = 1
                            this.posterUrl = posterUrl
                        }
                    )
                }
            } else {
                // Fallback direct children endpoint
                val childRaw = safeGet("/mar/v1/asset/$id/children")
                if (!childRaw.isNullOrBlank()) {
                    try {
                        val cJson = JSONObject(childRaw)
                        val cArr = cJson.optJSONArray("items")
                        if (cArr != null) {
                            for (i in 0 until cArr.length()) {
                                val child = cArr.optJSONObject(i) ?: continue
                                val childId = child.optString("_id")
                                if (childId.isBlank()) continue
                                val epNum = child.optInt("seq", i + 1)
                                val epTitle = child.optString("title").ifBlank { "Episódio $epNum" }

                                episodes.add(
                                    newEpisode("$mainUrl/mar/v1/asset/$childId/playinfo") {
                                        this.name = epTitle
                                        this.episode = epNum
                                        this.season = 1
                                        this.posterUrl = posterUrl
                                    }
                                )
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterHeadersMap
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/mar/v1/asset/$id/playinfo") {
                this.posterUrl = posterUrl
                this.posterHeaders = posterHeadersMap
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("http") && (data.contains(".m3u8") || data.contains(".mp4") || data.contains(".ts"))) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = data,
                    type = if (data.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf("User-Agent" to userAgent)
                }
            )
            return true
        }

        val raw = safeGet(data) ?: return false
        try {
            val json = JSONObject(raw)
            val info = json.optJSONObject("info") ?: return false

            val subsArr = info.optJSONArray("subtitles")
            if (subsArr != null) {
                for (i in 0 until subsArr.length()) {
                    val sub = subsArr.optJSONObject(i) ?: continue
                    val subUrl = sub.optString("url")
                    if (subUrl.isNotBlank()) {
                        subtitleCallback(
                            SubtitleFile(
                                lang = sub.optString("lang", "Português"),
                                url = subUrl
                            )
                        )
                    }
                }
            }

            val playArr = info.optJSONArray("play")
            var foundLinks = false
            if (playArr != null) {
                for (i in 0 until playArr.length()) {
                    val stream = playArr.optJSONObject(i) ?: continue
                    val streamUrl = stream.optString("url")
                    if (streamUrl.isBlank()) continue

                    val resStr = stream.optString("resolution")
                    val quality = when (resStr.lowercase()) {
                        "1080p", "1080" -> Qualities.P1080.value
                        "720p", "720" -> Qualities.P720.value
                        "480p", "480" -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }

                    val langs = mutableListOf<String>()
                    val langArr = stream.optJSONArray("audio_langs")
                    if (langArr != null) {
                        for (j in 0 until langArr.length()) {
                            val l = langArr.optString(j)
                            if (l.isNotBlank()) langs.add(l)
                        }
                    }
                    val audioDesc = if (langs.isNotEmpty()) " [${langs.joinToString(",")}]" else ""

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name ${resStr.ifBlank { "Stream" }}$audioDesc".trim(),
                            url = streamUrl,
                            type = if (streamUrl.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                        ) {
                            this.quality = quality
                            this.headers = mapOf("User-Agent" to userAgent)
                        }
                    )
                    foundLinks = true
                }
            }
            return foundLinks
        } catch (_: Exception) {
            return false
        }
    }
}
