package com.VeloPlayTV

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

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

    private var authToken: String? = null

    private suspend fun getAuthHeaders(): Map<String, String> {
        var token = authToken
        if (token.isNullOrBlank()) {
            token = performLogin()
        }
        return mapOf(
            "User-Agent" to "Velo Play/1.2.0 (Linux; Android 11; redroid11_x86_64) com.global.veloplaytv/10200",
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
                    "User-Agent" to "Velo Play/1.2.0 (Linux; Android 11; redroid11_x86_64) com.global.veloplaytv/10200",
                    "Content-Type" to "application/json; charset=UTF-8",
                    "Accept-Language" to "pt-BR"
                ),
                timeout = 10
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
            var resp = app.get(fullUrl, headers = headers, timeout = 10)
            if (resp.code == 401) {
                performLogin()
                val newHeaders = getAuthHeaders()
                resp = app.get(fullUrl, headers = newHeaders, timeout = 10)
            }
            if (resp.isSuccessful) resp.text else null
        } catch (_: Exception) {
            null
        }
    }

    // Data Transfer Objects
    data class SlotItemDto(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("refer") val refer: String? = null,
        @JsonProperty("pic") val pic: String? = null,
        @JsonProperty("title") val title: String? = null
    )

    data class SlotDto(
        @JsonProperty("seq") val seq: Int? = null,
        @JsonProperty("items") val items: List<SlotItemDto>? = null
    )

    data class RecommendationResponse(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("msg") val msg: String? = null,
        @JsonProperty("slots") val slots: List<SlotDto>? = null
    )

    data class SearchAssetDto(
        @JsonProperty("_id") val id: String? = null,
        @JsonProperty("_type") val type: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("score") val score: Any? = null,
        @JsonProperty("series_status") val seriesStatus: String? = null,
        @JsonProperty("posters") val posters: List<String>? = null,
        @JsonProperty("has_subtitle") val hasSubtitle: Boolean? = null
    )

    data class SearchResponseDto(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("msg") val msg: String? = null,
        @JsonProperty("total") val total: Int? = null,
        @JsonProperty("assets") val assets: List<SearchAssetDto>? = null
    )

    data class ImageDto(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class ActorDto(
        @JsonProperty("_id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("headshot") val headshot: String? = null
    )

    data class BrotherDto(
        @JsonProperty("_id") val id: String? = null,
        @JsonProperty("title") val title: String? = null
    )

    data class ChildItemDto(
        @JsonProperty("_id") val id: String? = null,
        @JsonProperty("seq") val seq: Int? = null,
        @JsonProperty("title") val title: String? = null
    )

    data class ChildrenContainerDto(
        @JsonProperty("total") val total: Int? = null,
        @JsonProperty("seq_s") val seqS: Int? = null,
        @JsonProperty("seq_e") val seqE: Int? = null,
        @JsonProperty("items") val items: List<ChildItemDto>? = null
    )

    data class AssetDetailItemDto(
        @JsonProperty("_id") val id: String? = null,
        @JsonProperty("_type") val type: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("score") val score: Any? = null,
        @JsonProperty("release_at") val releaseAt: String? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("images") val images: List<ImageDto>? = null,
        @JsonProperty("actors") val actors: List<ActorDto>? = null,
        @JsonProperty("brothers") val brothers: List<BrotherDto>? = null,
        @JsonProperty("children") val children: ChildrenContainerDto? = null
    )

    data class AssetDetailResponse(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("msg") val msg: String? = null,
        @JsonProperty("asset") val asset: AssetDetailItemDto? = null
    )

    data class SubtitleItemDto(
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class PlayStreamItemDto(
        @JsonProperty("resolution") val resolution: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("audio_langs") val audioLangs: List<String>? = null
    )

    data class PlayInfoContainerDto(
        @JsonProperty("subtitles") val subtitles: List<SubtitleItemDto>? = null,
        @JsonProperty("play") val play: List<PlayStreamItemDto>? = null
    )

    data class PlayInfoResponse(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("msg") val msg: String? = null,
        @JsonProperty("_id") val id: String? = null,
        @JsonProperty("info") val info: PlayInfoContainerDto? = null
    )

    data class ChannelStreamDto(
        @JsonProperty("_id") val id: String? = null,
        @JsonProperty("resolution") val resolution: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class ChannelItemDto(
        @JsonProperty("_id") val id: String? = null,
        @JsonProperty("display_name") val displayName: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("resolution") val resolution: String? = null,
        @JsonProperty("streams") val streams: List<ChannelStreamDto>? = null
    )

    data class ChannelListResponse(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("msg") val msg: String? = null,
        @JsonProperty("channels") val channels: List<ChannelItemDto>? = null
    )

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
                val parsed = tryParseJson<ChannelListResponse>(raw)
                val channels = parsed?.channels ?: emptyList()

                channels.forEach { ch ->
                    val id = ch.id ?: return@forEach
                    val name = ch.displayName ?: "Canal Ao Vivo"
                    val logo = ch.logo ?: ch.poster
                    val streamLink = ch.streams?.firstOrNull()?.url ?: "$mainUrl/stella/v1/channel/$id/play"

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
        val raw = safeGet(path)

        if (!raw.isNullOrBlank()) {
            val parsed = tryParseJson<RecommendationResponse>(raw)
            val slots = parsed?.slots ?: emptyList()

            slots.forEach { slot ->
                slot.items?.forEach { item ->
                    val id = item.refer ?: return@forEach
                    val title = item.title ?: return@forEach
                    val poster = item.pic
                    val isSeries = path.contains("FFDE") || path.contains("hgKe") || title.contains("Temp.", ignoreCase = true)

                    val type = if (isSeries) TvType.TvSeries else TvType.Movie
                    if (type == TvType.TvSeries) {
                        homeItems.add(
                            newTvSeriesSearchResponse(title, "$mainUrl/mar/v1/asset/$id/detail", TvType.TvSeries) {
                                this.posterUrl = poster
                            }
                        )
                    } else {
                        homeItems.add(
                            newMovieSearchResponse(title, "$mainUrl/mar/v1/asset/$id/detail", TvType.Movie) {
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, homeItems)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchItems = mutableListOf<SearchResponse>()
        val path = "/mar/v1/asset/search?q=$query&page=1&page_size=24"
        val raw = safeGet(path) ?: return emptyList()

        val parsed = tryParseJson<SearchResponseDto>(raw)
        val items = parsed?.assets ?: emptyList()

        items.forEach { item ->
            val id = item.id ?: return@forEach
            val title = item.title ?: return@forEach
            val poster = item.posters?.firstOrNull()
            val isSeries = item.type.equals("SEASON", ignoreCase = true) || item.seriesStatus?.isNotBlank() == true || title.contains("Temp.", ignoreCase = true)

            if (isSeries) {
                searchItems.add(
                    newTvSeriesSearchResponse(title, "$mainUrl/mar/v1/asset/$id/detail", TvType.TvSeries) {
                        this.posterUrl = poster
                    }
                )
            } else {
                searchItems.add(
                    newMovieSearchResponse(title, "$mainUrl/mar/v1/asset/$id/detail", TvType.Movie) {
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

        val raw = safeGet(url) ?: throw ErrorLoadingException("Não foi possível carregar detalhes do item")
        val parsed = tryParseJson<AssetDetailResponse>(raw)?.asset
            ?: throw ErrorLoadingException("Formato de resposta inválido")

        val id = parsed.id ?: ""
        val title = parsed.title ?: "Sem Título"
        val poster = parsed.images?.firstOrNull { it.type == "poster" }?.url
            ?: parsed.images?.firstOrNull { it.type == "icon" }?.url
            ?: parsed.images?.firstOrNull()?.url
        val plot = parsed.description
        val year = parsed.releaseAt?.take(4)?.toIntOrNull()
        val isSeries = parsed.type.equals("SEASON", ignoreCase = true) || parsed.children?.items?.isNotEmpty() == true || parsed.brothers?.isNotEmpty() == true

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val childItems = parsed.children?.items ?: emptyList()

            if (childItems.isNotEmpty()) {
                childItems.forEach { child ->
                    val childId = child.id ?: return@forEach
                    val epNum = child.seq ?: 1
                    val epTitle = child.title ?: "Episódio $epNum"

                    episodes.add(
                        newEpisode(
                            "$mainUrl/mar/v1/asset/$childId/playinfo"
                        ) {
                            this.name = epTitle
                            this.episode = epNum
                            this.season = 1
                            this.posterUrl = poster
                        }
                    )
                }
            } else {
                // Fallback direct children endpoint
                val childRaw = safeGet("/mar/v1/asset/$id/children")
                if (!childRaw.isNullOrBlank()) {
                    val childParsed = tryParseJson<ChildrenContainerDto>(childRaw)
                    childParsed?.items?.forEach { child ->
                        val childId = child.id ?: return@forEach
                        val epNum = child.seq ?: 1
                        val epTitle = child.title ?: "Episódio $epNum"

                        episodes.add(
                            newEpisode(
                                "$mainUrl/mar/v1/asset/$childId/playinfo"
                            ) {
                                this.name = epTitle
                                this.episode = epNum
                                this.season = 1
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = parsed.tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/mar/v1/asset/$id/playinfo") {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = parsed.tags
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

        val raw = safeGet(data) ?: return false
        val parsed = tryParseJson<PlayInfoResponse>(raw)?.info ?: return false

        parsed.subtitles?.forEach { sub ->
            val subUrl = sub.url ?: return@forEach
            subtitleCallback(
                SubtitleFile(
                    lang = sub.lang ?: "Português",
                    url = subUrl
                )
            )
        }

        parsed.play?.forEach { stream ->
            val streamUrl = stream.url ?: return@forEach
            val quality = when (stream.resolution?.lowercase()) {
                "1080p", "1080" -> Qualities.P1080.value
                "720p", "720" -> Qualities.P720.value
                "480p", "480" -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }
            val audioDesc = if (stream.audioLangs?.isNotEmpty() == true) " [${stream.audioLangs.joinToString(",")}]" else ""
            callback(
                newExtractorLink(
                    source = name,
                    name = "${name} ${stream.resolution ?: ""}$audioDesc".trim(),
                    url = streamUrl,
                    type = if (streamUrl.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                ) {
                    this.quality = quality
                }
            )
        }

        return parsed.play?.isNotEmpty() == true
    }
}
