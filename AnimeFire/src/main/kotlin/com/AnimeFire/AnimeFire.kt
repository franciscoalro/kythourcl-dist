package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.text.Normalizer

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.one"
    override var name = "AnimeFire"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        // v152: site virou SPA Angular — HTML é shell vazio, conteúdo via api.animefire.one.
        // Home/busca/detalhe/episódio por JSON; espelhos failover mantidos.
        const val API_URL = "https://api.animefire.one"
        val MIRRORS = listOf(
            "https://animefire.one",
            "https://animefire.plus",
            "https://animefire.io"
        )
        @Volatile var activeMirrorIdx = 0

        private fun isBlockedResponse(code: Int, body: String): Boolean {
            if (code == 403 || code == 503) return true
            return body.contains("Just a moment", true) ||
                body.contains("Attention Required", true) ||
                body.contains("challenge-platform", true) ||
                body.contains("cf-error-details", true)
        }

        val JSON_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Origin" to "https://animefire.one",
            "Referer" to "https://animefire.one/"
        )

        /** GET JSON na api com retry (WAF barra ~2/3 por IP datacenter; insiste até passar). */
        suspend fun apiGet(path: String, maxTries: Int = 8): String? {
            repeat(maxTries) {
                try {
                    val res = app.get("$API_URL$path", headers = JSON_HEADERS, timeout = 20)
                    val body = try { res.text } catch (_: Throwable) { "" }
                    if (res.code == 200 && body.isNotBlank() && !isBlockedResponse(res.code, body)) {
                        return body
                    }
                } catch (_: Throwable) {}
            }
            return null
        }

        /** GET com failover de espelho: tenta o ativo, em 403/challenge rotaciona. */
        suspend fun mirrorGet(url: String, headers: Map<String, String>) = mirrorGetImpl(url, headers)

        private suspend fun mirrorGetImpl(url: String, headers: Map<String, String>) = app.get(urlForMirror(url, MIRRORS[activeMirrorIdx]), headers = headers).let { first ->
            val firstBody = try { first.text } catch (_: Throwable) { "" }
            if (!isBlockedResponse(first.code, firstBody)) return@let first
            // bloqueado: rotaciona espelhos
            var result = first
            for (i in 1 until MIRRORS.size) {
                activeMirrorIdx = (activeMirrorIdx + 1) % MIRRORS.size
                val base = MIRRORS[activeMirrorIdx]
                val fixed = urlForMirror(url, base)
                val h = headers.toMutableMap()
                h["Referer"] = "$base/"
                try {
                    val res = app.get(fixed, headers = h)
                    result = res
                    val body = try { res.text } catch (_: Throwable) { "" }
                    if (!isBlockedResponse(res.code, body)) break
                } catch (_: Throwable) {}
            }
            result
        }

        /** Reescreve qualquer espelho conhecido para a base dada. */
        fun urlForMirror(url: String, base: String): String {
            var out = url
            for (m in MIRRORS) {
                if (out.startsWith(m)) return base + out.substring(m.length)
            }
            return out
        }

        val BROWSER_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://animefire.one/"
        )

        val API_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "https://animefire.one/"
        )

        fun sanitizeQuery(query: String): String {
            if (query.isBlank()) return ""
            val normalized = Normalizer.normalize(query, Normalizer.Form.NFD)
                .replace("\\p{M}".toRegex(), "")
            val cleaned = normalized
                .replace("""[/\\:!?*~`"'+#@$%^&|]""".toRegex(), " ")
                .replace("""[^a-zA-Z0-9\s-]""".toRegex(), "")
            return cleaned.trim()
                .replace("""[\s-]+""".toRegex(), "-")
                .trim('-')
                .lowercase()
        }
    }

    // ---- v152: modelos da api.animefire.one ----
    data class AfTitles(
        @JsonProperty("BR") val br: String? = null,
        @JsonProperty("US") val us: String? = null,
        @JsonProperty("JP") val jp: String? = null
    )
    data class AfAnimeItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("titles") val titles: AfTitles? = null,
        @JsonProperty("audio") val audio: String? = null,
        @JsonProperty("poster_src") val poster: String? = null,
        @JsonProperty("status") val status: String? = null
    )
    data class AfAnimesResp(
        @JsonProperty("data") val data: List<AfAnimeItem>? = null
    )
    data class AfHomeResp(
        @JsonProperty("data") val data: AfHomeData? = null
    )
    data class AfCarousel(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("sub") val sub: String? = null,
        @JsonProperty("items") val items: List<AfAnimeItem>? = null
    )
    data class AfHomeData(
        @JsonProperty("carousels") val carousels: List<AfCarousel>? = null,
        @JsonProperty("hero") val hero: AfCarousel? = null
    )
    data class AfHero(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("titles") val titles: AfTitles? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("poster_src") val poster: String? = null,
        @JsonProperty("backdrop_src") val backdrop: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("audio") val audio: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("score") val score: Double? = null,
        @JsonProperty("format") val format: String? = null
    )
    data class AfEpisode(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("audio") val audio: String? = null,
        @JsonProperty("still_src") val still: String? = null,
        @JsonProperty("synopsis") val synopsis: String? = null
    )
    data class AfAnimeDetail(
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("hero") val hero: AfHero? = null,
        @JsonProperty("episodes") val episodes: List<AfEpisode>? = null
    )
    data class AfAnimeDetailResp(
        @JsonProperty("data") val data: AfAnimeDetail? = null
    )
    data class AfStream(
        @JsonProperty("audio") val audio: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("qualities") val qualities: List<String>? = null
    )
    data class AfEpisodeDetail(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("streams") val streams: List<AfStream>? = null
    )
    data class AfEpisodeResp(
        @JsonProperty("data") val data: AfEpisodeDetail? = null
    )

    private fun AfAnimeItem.toSearchResult(): SearchResponse? {
        val id = this.id ?: return null
        val title = this.titles?.br ?: this.titles?.us ?: this.titles?.jp ?: return null
        return newMovieSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
            this.posterUrl = this@toSearchResult.poster
        }
    }

    override val mainPage = mainPageOf(
        "api:/home" to "Últimos Lançamentos",
        "api:/animes/lancamentos" to "Em Lançamento",
        "api:/animes/em-breve" to "Em Breve",
        "api:/animes/filmes" to "Filmes"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a") ?: (if (this.tagName() == "a") this else return null)
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = this.selectFirst(".animeTitle, .anime-title, .title, h3, h2, .titAnime")?.text()?.trim()
            ?: this.attr("title").takeIf { it.isNotBlank() }?.substringBefore(" - Filme")?.trim()
            ?: link.text().trim()
        if (title.isBlank()) return null

        val imgTag = this.selectFirst("img") ?: link.selectFirst("img")
        val poster = imgTag?.let { 
            it.attr("data-src").takeIf { s -> s.isNotBlank() }
                ?: it.attr("data-original").takeIf { s -> s.isNotBlank() }
                ?: it.attr("src")
        }?.let { fixUrlNull(it) }

        val isMovie = href.contains("/filmes") || href.contains("filme") || href.contains("-movie")
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newMovieSearchResponse(title, fixUrl(href), tvType) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.removePrefix("api:")
        if (page > 1 && (path == "/home" || path == "/animes/lancamentos" || path == "/animes/em-breve")) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val paged = if (page > 1) {
            if (path.contains("?")) "$path&page=$page" else "$path?page=$page"
        } else path
        return try {
            val body = apiGet(paged) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
            val items = if (path == "/home") {
                val home = tryParseJson<AfHomeResp>(body)?.data
                ((home?.hero?.items.orEmpty()) + (home?.carousels.orEmpty().flatMap { it.items.orEmpty() })).distinctBy { it.id }
            } else {
                tryParseJson<AfAnimesResp>(body)?.data.orEmpty()
            }
            newHomePageResponse(
                request.name,
                items.mapNotNull { it.toSearchResult() },
                hasNext = page <= 1 && (path.startsWith("/animes/filmes") || path.startsWith("/animes?"))
            )
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val body = apiGet("/animes?q=${query.trim()}") ?: return emptyList()
            tryParseJson<AfAnimesResp>(body)?.data.orEmpty().mapNotNull { it.toSearchResult() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = Regex("""/anime/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.getOrNull(1)
            ?: throw ErrorLoadingException("AnimeFire: URL inválida")
        val body = apiGet("/anime/$id")
            ?: throw ErrorLoadingException("AnimeFire: API indisponível")
        val detail = tryParseJson<AfAnimeDetailResp>(body)?.data
            ?: throw ErrorLoadingException("AnimeFire: anime não encontrado")
        val hero = detail.hero
        val title = hero?.titles?.br ?: hero?.titles?.us ?: hero?.titles?.jp ?: "Anime"
        val episodes = detail.episodes.orEmpty().mapNotNull { ep ->
            val epId = ep.id ?: return@mapNotNull null
            val num = ep.number ?: 0
            newEpisode("$mainUrl/episode/$epId") {
                this.name = if (!ep.title.isNullOrBlank()) "E$num \u2014 ${ep.title}" else "Episódio $num"
                this.episode = num
                this.season = ep.season
                this.posterUrl = ep.still
                this.description = ep.synopsis
            }
        }.sortedBy { it.episode ?: 0 }
        val isMovie = detail.format == "movie" || episodes.size <= 1
        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = hero?.poster
                this.plot = hero?.synopsis
                this.tags = hero?.genres
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = hero?.poster
                this.plot = hero?.synopsis
                this.tags = hero?.genres
            }
        }
    }

    data class AnimeFireVideoItem(
        @JsonProperty("src") val src: String? = null,
        @JsonProperty("label") val label: String? = null
    )

    data class AnimeFireVideoResponse(
        @JsonProperty("data") val data: List<AnimeFireVideoItem>? = null,
        @JsonProperty("response") val response: Map<String, Any>? = null,
        @JsonProperty("token") val token: String? = null
    )

    private suspend fun extractBlogger(
        token: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (token.isBlank()) return false
        var extracted = false
        try {
            val rpcUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute?rpcids=WcwnYd&source-path=%2Fvideo.g&hl=pt-BR"
            val reqPayload = """[[["WcwnYd","[\"$token\",null,0]",null,"generic"]]]"""
            val response = app.post(
                rpcUrl,
                headers = mapOf(
                    "User-Agent" to (BROWSER_HEADERS["User-Agent"] ?: "Mozilla/5.0"),
                    "Referer" to "https://www.blogger.com/",
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8"
                ),
                data = mapOf("f.req" to reqPayload)
            )

            val text = response.text
            val jsonArrayMatch = Regex("""\[\["wrb\.fr","WcwnYd","(.*?)",null,null,null,"generic"\]\]""").find(text)
            val rawData = jsonArrayMatch?.groupValues?.getOrNull(1) ?: text

            val unescaped = rawData
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\u003d", "=")
                .replace("\\u0026", "&")

            val streamRegex = Regex("""\["(https:[^"]+googlevideo\.com[^"]+)",\s*\[(\d+)\]\]""")
            val matches = streamRegex.findAll(unescaped).toList()

            for (m in matches) {
                var streamUrl = m.groupValues[1]
                val itag = m.groupValues[2].toIntOrNull() ?: 22

                if (streamUrl.contains("\\u")) {
                    streamUrl = streamUrl.replace("\\u003d", "=").replace("\\u0026", "&")
                }

                val (qualityInt, label) = when (itag) {
                    37 -> Qualities.P1080.value to "1080p (FHD)"
                    22 -> Qualities.P720.value to "720p (HD)"
                    18 -> Qualities.P360.value to "360p (SD)"
                    else -> Qualities.P720.value to "HD"
                }

                val browserUa = BROWSER_HEADERS["User-Agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "AnimeFire Blogger ($label)",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.headers = mapOf(
                            "User-Agent" to browserUa,
                            "Accept" to "*/*"
                        )
                        this.quality = qualityInt
                    }
                )
                extracted = true
            }
        } catch (_: Exception) {}
        return extracted
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        // v152: data = "$mainUrl/episode/{epId}" — streams via api.animefire.one
        val epId = Regex("""/episode/([A-Za-z0-9_-]+)""").find(data)?.groupValues?.getOrNull(1)
        if (!epId.isNullOrBlank()) {
            try {
                val body = apiGet("/episode/$epId")
                val detail = body?.let { tryParseJson<AfEpisodeResp>(it)?.data }
                for (stream in detail?.streams.orEmpty()) {
                    val streamUrl = stream.url ?: continue
                    if (streamUrl.isBlank()) continue
                    for (q in stream.qualities.orEmpty().ifEmpty { listOf("HD") }) {
                        val qualityInt = when {
                            q.contains("1080", true) -> Qualities.P1080.value
                            q.contains("720", true) -> Qualities.P720.value
                            q.contains("480", true) -> Qualities.P480.value
                            q.contains("360", true) -> Qualities.P360.value
                            else -> Qualities.Unknown.value
                        }
                        // v153: stream.url da API é playlist HLS (player usa type
                        // application/vnd.apple.mpegurl) mesmo sem extensão .m3u8
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "AnimeFire ($q)",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                // v152: akumast.net valida Referer/Origin do site + UA mobile
                                this.referer = "$mainUrl/"
                                this.headers = mapOf(
                                    "User-Agent" to (JSON_HEADERS["User-Agent"] ?: "Mozilla/5.0"),
                                    "Referer" to "$mainUrl/",
                                    "Origin" to "https://animefire.one"
                                )
                                this.quality = qualityInt
                            }
                        )
                        found = true
                    }
                }
                // legendas: chapters/thumbnails ignorados (são sprites); API não expõe .srt aqui
            } catch (_: Exception) {}
            if (found) return true
        }

        // Fallback legado: tenta Blogger via página HTML (compat com URLs antigas /animes/slug/N)
        return try {
            legacyLoadLinks(data, isCasting, subtitleCallback, callback)
        } catch (_: Exception) {
            found
        }
    }

    private suspend fun legacyLoadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val doc = try {
            mirrorGet(data, BROWSER_HEADERS).document
        } catch (_: Exception) {
            return false
        }

        val bloggerTokens = mutableListOf<String>()
        doc.select("iframe[src*='blogger.com']").forEach { iframe ->
            val src = iframe.attr("src")
            Regex("""token=([A-Za-z0-9_-]+)""").find(src)?.groupValues?.getOrNull(1)?.let { bloggerTokens.add(it) }
        }
        Regex("""blogger\.com/video\.g\?token=([A-Za-z0-9_-]+)""").findAll(doc.html()).forEach { m ->
            m.groupValues.getOrNull(1)?.let { bloggerTokens.add(it) }
        }
        for (token in bloggerTokens.distinct()) {
            if (extractBlogger(token, callback)) found = true
        }

        doc.select("video source[src], video[src]").forEach { v ->
            val vSrc = v.attr("src").trim()
            if (vSrc.isNotBlank() && (vSrc.contains(".mp4") || vSrc.contains(".m3u8"))) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "AnimeFire Direct",
                        url = fixUrl(vSrc),
                        type = if (vSrc.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P720.value
                    }
                )
                found = true
            }
        }

        val iframes = doc.select("iframe[src]").mapNotNull {
            val src = it.attr("src")
            if (src.isNotBlank() && !src.contains("topanimes.net/off/") && !src.contains("youtube.googleapis.com") && !src.contains("blogger.com")) fixUrl(src) else null
        }.distinct()
        for (ifr in iframes) {
            try {
                if (loadExtractor(ifr, data, subtitleCallback, callback)) found = true
            } catch (_: Exception) {}
        }

        if (!found) {
            val interceptRegex = Regex("""https?://.*(?:googlevideo\.com/videoplayback|lightspeedst\.net|blogger\.com/video-play|.*\.mp4|.*\.m3u8).*""")
            val wvMirrors = (0 until MIRRORS.size).map { MIRRORS[(activeMirrorIdx + it) % MIRRORS.size] }
            for (wvBase in wvMirrors) {
                try {
                    val wvData = urlForMirror(data, wvBase)
                    val wvResp = app.get(
                        wvData,
                        headers = BROWSER_HEADERS.toMutableMap().also { it["Referer"] = "$wvBase/" },
                        interceptor = WebViewResolver(
                            interceptUrl = interceptRegex,
                            timeout = 15000L
                        )
                    )
                    val interceptedUrl = wvResp.url
                    val htmlContent = wvResp.text
                    val streamUrl = Regex("""https?://[^"'\s<>]*googlevideo\.com/videoplayback[^"'\s<>]*""").find(htmlContent)?.value
                        ?: Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(htmlContent)?.value
                        ?: Regex("""https?://[^"'\s<>]+\.mp4[^"'\s<>]*""").find(htmlContent)?.value
                        ?: interceptedUrl.takeIf { it.contains("videoplayback") || it.contains(".mp4") || it.contains(".m3u8") }
                    if (streamUrl != null && !streamUrl.contains("youtube.googleapis.com/embed") && !streamUrl.contains("blogger.com/video.g")) {
                        val isM3u8 = streamUrl.contains(".m3u8")
                        val isGoogleVideo = streamUrl.contains("googlevideo.com")
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = if (isGoogleVideo) "AnimeFire Google Player" else "AnimeFire Player",
                                url = streamUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = if (isGoogleVideo) "https://www.blogger.com/" else "$wvBase/"
                                this.quality = Qualities.P720.value
                            }
                        )
                        found = true
                        break
                    }
                } catch (_: Exception) {}
            }
        }
        return found
    }
}
