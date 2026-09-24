package com.VeloPlayTV

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonProperty
import org.json.JSONObject
import org.json.JSONArray

class VeloPlayTV : MainAPI() {
    override var name = "Velo Play TV"
    override var mainUrl = "https://update.castgrid.cyou"
    override var lang = "pt"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Live
    )

    private val appSlug = "veloplay"
    private val clientType = "android"

    // Lista de endpoints candidatos / espelhos para failover dinâmico
    private val hostCandidates = listOf(
        "https://update.castgrid.cyou",
        "https://api.koocan.com",
        "https://api.veloplay.com",
        "https://api.hwcty.info"
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "com.global.veloplaymob/1.2.0 (Linux; U; Android 10; Mobile)",
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "app" to appSlug,
        "client_type" to clientType
    )

    data class ApiResponse<T>(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("msg") val msg: String? = null,
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("data") val data: T? = null
    )

    data class AssetDto(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("score") val score: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("desc") val desc: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("seq") val seq: Int? = null
    )

    data class AssetListResponse(
        @JsonProperty("list") val list: List<AssetDto>? = null,
        @JsonProperty("assets") val assets: List<AssetDto>? = null,
        @JsonProperty("rows") val rows: List<AssetDto>? = null
    )

    data class AssetDetailDto(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("score") val score: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("desc") val desc: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("children") val children: List<AssetDto>? = null,
        @JsonProperty("brothers") val brothers: List<AssetDto>? = null
    )

    data class ChannelDto(
        @JsonProperty("channel_id") val channelId: String? = null,
        @JsonProperty("channelId") val altChannelId: String? = null,
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("icon") val icon: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("play_url") val playUrl: String? = null,
        @JsonProperty("stream_url") val streamUrl: String? = null
    )

    data class ChannelListResponse(
        @JsonProperty("channels") val channels: List<ChannelDto>? = null,
        @JsonProperty("list") val list: List<ChannelDto>? = null
    )

    data class StreamItem(
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class SubtitleDto(
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class PlayInfoDto(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("play_url") val playUrl: String? = null,
        @JsonProperty("stream_url") val streamUrl: String? = null,
        @JsonProperty("streams") val streams: List<StreamItem>? = null,
        @JsonProperty("subtitles") val subtitles: List<SubtitleDto>? = null
    )

    override val mainPage = mainPageOf(
        "/$appSlug/$clientType/mar/v1/assets?category_id=movie" to "Filmes",
        "/$appSlug/$clientType/mar/v1/assets?category_id=series" to "Séries",
        "/$appSlug/$clientType/stella/v1/channels" to "Canais Ao Vivo"
    )

    private suspend fun fetchWithFallback(path: String): String? {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        
        // Testa o host primário primeiro
        try {
            val primaryUrl = "$mainUrl$cleanPath"
            val resp = app.get(primaryUrl, headers = defaultHeaders, timeout = 6)
            if (resp.isSuccessful && resp.text.isNotBlank()) {
                return resp.text
            }
        } catch (_: Exception) {}

        // Failover para candidatos alternativos
        for (host in hostCandidates) {
            if (host == mainUrl) continue
            try {
                val candidateUrl = "$host$cleanPath"
                val resp = app.get(candidateUrl, headers = defaultHeaders, timeout = 4)
                if (resp.isSuccessful && resp.text.isNotBlank()) {
                    mainUrl = host // Atualiza dinamicamente para o host operacional
                    return resp.text
                }
            } catch (_: Exception) {}
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val isLive = path.contains("stella") || path.contains("channel")

        if (isLive) {
            val liveItems = mutableListOf<SearchResponse>()
            val raw = fetchWithFallback(path)
            if (!raw.isNullOrBlank()) {
                val parsed = tryParseJson<ApiResponse<ChannelListResponse>>(raw)
                val channels = parsed?.data?.channels ?: parsed?.data?.list ?: emptyList()

                channels.forEach { ch ->
                    val id = ch.channelId ?: ch.altChannelId ?: ch.id ?: return@forEach
                    val name = ch.name ?: ch.title ?: "Canal Ao Vivo"
                    val logo = ch.logo ?: ch.icon
                    val streamLink = ch.streamUrl ?: ch.playUrl ?: ch.url ?: "$mainUrl/$appSlug/$clientType/stella/v1/channel/$id/play"

                    liveItems.add(
                        newLiveSearchResponse(name, streamLink, TvType.Live) {
                            this.posterUrl = logo
                        }
                    )
                }
            }

            return newHomePageResponse(
                listOf(HomePageList(request.name, liveItems)),
                hasNext = false
            )
        }

        val homeItems = mutableListOf<SearchResponse>()
        val pageQuery = if (path.contains("?")) "$path&page=$page&page_size=20" else "$path?page=$page&page_size=20"
        val raw = fetchWithFallback(pageQuery)

        if (!raw.isNullOrBlank()) {
            val parsed = tryParseJson<ApiResponse<AssetListResponse>>(raw)
            val items = parsed?.data?.list ?: parsed?.data?.assets ?: parsed?.data?.rows ?: emptyList()

            items.forEach { item ->
                val id = item.id ?: return@forEach
                val title = item.title ?: item.name ?: return@forEach
                val poster = item.poster ?: item.cover
                val isSeries = item.type?.contains("series", ignoreCase = true) == true || item.season != null

                if (isSeries) {
                    homeItems.add(
                        newTvSeriesSearchResponse(title, "$mainUrl/$appSlug/$clientType/mar/v1/asset/$id/detail", TvType.TvSeries) {
                            this.posterUrl = poster
                        }
                    )
                } else {
                    homeItems.add(
                        newMovieSearchResponse(title, "$mainUrl/$appSlug/$clientType/mar/v1/asset/$id/detail", TvType.Movie) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, homeItems)),
            hasNext = homeItems.size >= 20
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchItems = mutableListOf<SearchResponse>()
        val path = "/$appSlug/$clientType/mar/v1/asset/search?keyword=$query"
        val raw = fetchWithFallback(path) ?: return emptyList()

        val parsed = tryParseJson<ApiResponse<AssetListResponse>>(raw)
        val items = parsed?.data?.list ?: parsed?.data?.assets ?: parsed?.data?.rows ?: emptyList()

        items.forEach { item ->
            val id = item.id ?: return@forEach
            val title = item.title ?: item.name ?: return@forEach
            val poster = item.poster ?: item.cover
            val isSeries = item.type?.contains("series", ignoreCase = true) == true || item.season != null

            if (isSeries) {
                searchItems.add(
                    newTvSeriesSearchResponse(title, "$mainUrl/$appSlug/$clientType/mar/v1/asset/$id/detail", TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                )
            } else {
                searchItems.add(
                    newMovieSearchResponse(title, "$mainUrl/$appSlug/$clientType/mar/v1/asset/$id/detail", TvType.Movie) {
                        this.posterUrl = poster
                    }
                )
            }
        }

        return searchItems
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("stella") || url.contains("channel") || url.contains(".m3u8")) {
            return newLiveStreamLoadResponse("Canal Ao Vivo", url, url)
        }

        val raw = if (url.startsWith("http")) {
            try {
                app.get(url, headers = defaultHeaders).text
            } catch (_: Exception) {
                val relPath = url.substringAfter(mainUrl, "")
                fetchWithFallback(relPath)
            }
        } else {
            fetchWithFallback(url)
        } ?: throw ErrorLoadingException("Não foi possível carregar detalhes do item")

        val parsed = tryParseJson<ApiResponse<AssetDetailDto>>(raw)?.data
            ?: throw ErrorLoadingException("Formato de resposta inválido")

        val id = parsed.id ?: ""
        val title = parsed.title ?: parsed.name ?: "Sem Título"
        val poster = parsed.poster ?: parsed.cover
        val plot = parsed.desc ?: parsed.description
        val year = parsed.year?.toIntOrNull()
        val isSeries = parsed.children?.isNotEmpty() == true || parsed.brothers?.isNotEmpty() == true || parsed.type?.contains("series", ignoreCase = true) == true

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val directChildren = parsed.children ?: parsed.brothers ?: emptyList()

            if (directChildren.isNotEmpty()) {
                directChildren.forEachIndexed { index, child ->
                    val childId = child.id ?: "$id-$index"
                    val epTitle = child.title ?: child.name ?: "Episódio ${index + 1}"
                    val epNum = child.episode ?: child.seq ?: (index + 1)
                    val seasonNum = child.season ?: 1

                    episodes.add(
                        newEpisode(
                            "$mainUrl/$appSlug/$clientType/mar/v1/asset/$childId/playinfo"
                        ) {
                            this.name = epTitle
                            this.episode = epNum
                            this.season = seasonNum
                            this.posterUrl = child.poster ?: child.cover ?: poster
                        }
                    )
                }
            } else {
                val childrenPath = "/$appSlug/$clientType/mar/v1/asset/$id/children"
                val childRaw = fetchWithFallback(childrenPath)
                if (!childRaw.isNullOrBlank()) {
                    val childParsed = tryParseJson<ApiResponse<AssetListResponse>>(childRaw)?.data
                    val fetchedChildren = childParsed?.list ?: childParsed?.assets ?: childParsed?.rows ?: emptyList()

                    fetchedChildren.forEachIndexed { index, child ->
                        val childId = child.id ?: "$id-$index"
                        val epTitle = child.title ?: child.name ?: "Episódio ${index + 1}"
                        val epNum = child.episode ?: child.seq ?: (index + 1)
                        val seasonNum = child.season ?: 1

                        episodes.add(
                            newEpisode(
                                "$mainUrl/$appSlug/$clientType/mar/v1/asset/$childId/playinfo"
                            ) {
                                this.name = epTitle
                                this.episode = epNum
                                this.season = seasonNum
                                this.posterUrl = child.poster ?: child.cover ?: poster
                            }
                        )
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/$appSlug/$clientType/mar/v1/asset/$id/playinfo") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
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
                )
            )
            return true
        }

        val raw = if (data.startsWith("http")) {
            try {
                app.get(data, headers = defaultHeaders).text
            } catch (_: Exception) {
                val relPath = data.substringAfter(mainUrl, "")
                fetchWithFallback(relPath)
            }
        } else {
            fetchWithFallback(data)
        } ?: return false

        val parsed = tryParseJson<ApiResponse<PlayInfoDto>>(raw)?.data

        val directUrl = parsed?.streamUrl ?: parsed?.playUrl ?: parsed?.url

        parsed?.subtitles?.forEach { sub ->
            val subUrl = sub.url ?: return@forEach
            subtitleCallback(
                SubtitleFile(
                    lang = sub.lang ?: sub.name ?: "Português",
                    url = subUrl
                )
            )
        }

        if (parsed?.streams?.isNotEmpty() == true) {
            parsed.streams.forEach { stream ->
                val streamLink = stream.url ?: return@forEach
                val quality = when (stream.quality) {
                    "1080p", "1080" -> Qualities.P1080.value
                    "720p", "720" -> Qualities.P720.value
                    "480p", "480" -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }
                callback(
                    newExtractorLink(
                        source = name,
                        name = "${name} ${stream.quality ?: ""}".trim(),
                        url = streamLink,
                        type = if (streamLink.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    ) {
                        this.quality = quality
                    }
                )
            }
            return true
        }

        if (!directUrl.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = directUrl,
                    type = if (directUrl.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        return false
    }
}
