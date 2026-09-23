package com.VeloPlayTV

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonProperty

class VeloPlayTV : MainAPI() {
    override var name = "Velo Play TV"
    override var mainUrl = "https://api.koocan.com"
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

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Mobile Safari/537.36",
        "Accept" to "application/json",
        "Content-Type" to "application/json"
    )

    data class ApiResponse<T>(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("msg") val msg: String? = null,
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
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null
    )

    data class AssetListResponse(
        @JsonProperty("list") val list: List<AssetDto>? = null,
        @JsonProperty("assets") val assets: List<AssetDto>? = null
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
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("children") val children: List<AssetDto>? = null
    )

    data class ChannelDto(
        @JsonProperty("channel_id") val channelId: String? = null,
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("icon") val icon: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("play_url") val playUrl: String? = null
    )

    data class ChannelListResponse(
        @JsonProperty("channels") val channels: List<ChannelDto>? = null,
        @JsonProperty("list") val list: List<ChannelDto>? = null
    )

    data class StreamItem(
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class PlayInfoDto(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("play_url") val playUrl: String? = null,
        @JsonProperty("stream_url") val streamUrl: String? = null,
        @JsonProperty("streams") val streams: List<StreamItem>? = null,
        @JsonProperty("subtitles") val subtitles: List<SubtitleDto>? = null
    )

    data class SubtitleDto(
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    override val mainPage = mainPageOf(
        "/$appSlug/$clientType/mar/v1/assets?category_id=movie" to "Filmes",
        "/$appSlug/$clientType/mar/v1/assets?category_id=series" to "Séries",
        "/$appSlug/$clientType/stella/v1/channels" to "Canais Ao Vivo"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.startsWith("http")) request.data else "$mainUrl${request.data}"

        if (request.data.contains("stella") || request.data.contains("channel")) {
            val res = app.get(url, headers = defaultHeaders).text
            val parsed = tryParseJson<ApiResponse<ChannelListResponse>>(res)
            val channels = parsed?.data?.channels ?: parsed?.data?.list ?: emptyList()

            val liveItems = channels.mapNotNull { ch ->
                val id = ch.channelId ?: ch.id ?: return@mapNotNull null
                val name = ch.name ?: ch.title ?: "Canal Ao Vivo"
                val logo = ch.logo ?: ch.icon
                val streamLink = ch.url ?: ch.playUrl ?: "$mainUrl/$appSlug/$clientType/stella/v1/channel/$id/play"

                newLiveSearchResponse(name, streamLink, TvType.Live) {
                    this.posterUrl = logo
                }
            }

            return newHomePageResponse(
                listOf(HomePageList(request.name, liveItems)),
                hasNext = false
            )
        }

        val res = app.get(url, headers = defaultHeaders).text
        val parsed = tryParseJson<ApiResponse<AssetListResponse>>(res)
        val items = parsed?.data?.list ?: parsed?.data?.assets ?: emptyList()

        val homeItems = items.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.title ?: item.name ?: return@mapNotNull null
            val poster = item.poster ?: item.cover
            val isSeries = item.type?.contains("series", ignoreCase = true) == true

            if (isSeries) {
                newTvSeriesSearchResponse(title, "$mainUrl/$appSlug/$clientType/mar/v1/asset/$id/detail", TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, "$mainUrl/$appSlug/$clientType/mar/v1/asset/$id/detail", TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, homeItems)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/$appSlug/$clientType/mar/v1/asset/search?keyword=$query"
        val res = app.get(searchUrl, headers = defaultHeaders).text
        val parsed = tryParseJson<ApiResponse<AssetListResponse>>(res)
        val items = parsed?.data?.list ?: parsed?.data?.assets ?: emptyList()

        return items.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.title ?: item.name ?: return@mapNotNull null
            val poster = item.poster ?: item.cover
            val isSeries = item.type?.contains("series", ignoreCase = true) == true

            if (isSeries) {
                newTvSeriesSearchResponse(title, "$mainUrl/$appSlug/$clientType/mar/v1/asset/$id/detail", TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, "$mainUrl/$appSlug/$clientType/mar/v1/asset/$id/detail", TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("stella") || url.contains("channel")) {
            return newLiveStreamLoadResponse("Canal Ao Vivo", url, url)
        }

        val res = app.get(url, headers = defaultHeaders).text
        val parsed = tryParseJson<ApiResponse<AssetDetailDto>>(res)?.data
            ?: throw ErrorLoadingException("Não foi possível carregar detalhes")

        val id = parsed.id ?: ""
        val title = parsed.title ?: parsed.name ?: "Sem Título"
        val poster = parsed.poster ?: parsed.cover
        val plot = parsed.desc
        val year = parsed.year?.toIntOrNull()
        val isSeries = parsed.children?.isNotEmpty() == true || parsed.type?.contains("series", ignoreCase = true) == true

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val directChildren = parsed.children ?: emptyList()

            if (directChildren.isNotEmpty()) {
                directChildren.forEachIndexed { index, child ->
                    val childId = child.id ?: "$id-$index"
                    val epTitle = child.title ?: child.name ?: "Episódio ${index + 1}"
                    val epNum = child.episode ?: (index + 1)
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
                // Tentar buscar lista de episódios da rota children
                val childrenUrl = "$mainUrl/$appSlug/$clientType/mar/v1/asset/$id/children"
                try {
                    val childRes = app.get(childrenUrl, headers = defaultHeaders).text
                    val childParsed = tryParseJson<ApiResponse<AssetListResponse>>(childRes)?.data
                    val fetchedChildren = childParsed?.list ?: childParsed?.assets ?: emptyList()

                    fetchedChildren.forEachIndexed { index, child ->
                        val childId = child.id ?: "$id-$index"
                        val epTitle = child.title ?: child.name ?: "Episódio ${index + 1}"
                        val epNum = child.episode ?: (index + 1)
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
                } catch (_: Exception) {}
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
                    type = ExtractorLinkType.M3U8
                )
            )
            return true
        }

        val res = app.get(data, headers = defaultHeaders).text
        val parsed = tryParseJson<ApiResponse<PlayInfoDto>>(res)?.data

        val directUrl = parsed?.streamUrl ?: parsed?.playUrl ?: parsed?.url

        parsed?.subtitles?.forEach { sub ->
            val subUrl = sub.url ?: return@forEach
            subtitleCallback(
                SubtitleFile(
                    lang = sub.lang ?: "Português",
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
                        type = ExtractorLinkType.M3U8
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
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        return false
    }
}
