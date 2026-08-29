package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.text.Normalizer

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
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
        val BROWSER_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://animefire.io/"
        )

        val API_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "https://animefire.io/"
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

    override val mainPage = mainPageOf(
        "$mainUrl/home/" to "Últimos Lançamentos",
        "$mainUrl/em-lancamento/" to "Em Lançamento",
        "$mainUrl/top-animes/" to "Top Animes",
        "$mainUrl/lista-de-animes-dublados/" to "Animes Dublados",
        "$mainUrl/lista-de-animes-legendados/" to "Animes Legendados",
        "$mainUrl/lista-de-filmes-dublados/" to "Filmes Dublados",
        "$mainUrl/lista-de-filmes-legendados/" to "Filmes Legendados"
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
        val url = if (page <= 1) {
            if (request.data == "$mainUrl/home/") mainUrl else request.data.removeSuffix("/")
        } else {
            "${request.data.removeSuffix("/")}/$page"
        }

        return try {
            val doc = app.get(url, headers = BROWSER_HEADERS).document
            val items = doc.select("article.card, .cardUltimosEps, .divCardUltimosEps, .anime-item, .row article, div.card, .divCardTop").mapNotNull {
                it.toSearchResult()
            }.distinctBy { it.url }

            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanSlug = sanitizeQuery(query)
        if (cleanSlug.isBlank()) return emptyList()

        return try {
            val url = "$mainUrl/pesquisar/$cleanSlug"
            val doc = app.get(url, headers = BROWSER_HEADERS).document

            doc.select("article.card, .cardUltimosEps, .divCardUltimosEps, .anime-item, .row article, div.card, .divCardTop").mapNotNull {
                it.toSearchResult()
            }.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url, headers = BROWSER_HEADERS).document

        // Se a URL for de um episódio específico (ex: /animes/slug/9) e houver link para 'todos-os-episodios', carrega a página completa da série
        val todosOsEpsHref = doc.selectFirst("a[href*='todos-os-episodios']")?.attr("href")
        if (!todosOsEpsHref.isNullOrBlank() && Regex("""/\d+$""").containsMatchIn(url)) {
            try {
                val seriesDoc = app.get(fixUrl(todosOsEpsHref), headers = BROWSER_HEADERS).document
                doc = seriesDoc
            } catch (_: Exception) {}
        }

        val rawTitle = doc.selectFirst("h1.anime-title, h1.title, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Anime"
        val title = rawTitle
            .replace(Regex(""" - Todos os Epis[óo]dios.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex(""" - Epis[óo]dio \d+.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Assistir\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+-\s+AnimeFire$""", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = doc.selectFirst(".anime-cover img, .poster img, img[src*='/animes/']")?.attr("data-src")
            ?: doc.selectFirst(".anime-cover img, .poster img, img[src*='/animes/']")?.attr("data-original")
            ?: doc.selectFirst(".anime-cover img, .poster img, img[src*='/animes/']")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst(".divSinopse .spanAnimeInfo, .divSinopse, .sinopse, .description, .anime-description")?.text()
            ?.replace(Regex("""^Sinopse:\s*""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("""Este site não hospeda nenhum vídeo.*""", RegexOption.IGNORE_CASE), "")
            ?.trim()

        val genres = doc.select("a[href*='/genero/'], .genre, .genres a, .badge-genre").map { it.text().trim() }

        val episodes = doc.select(".div_video_list a, .list_episodes a, a[href*='/animes/'], .div_link_video_list a, .divCardUltimosEps a").mapNotNull { ep ->
            val href = ep.attr("href")
            val rawText = ep.text().trim()
            if (href.isBlank() || !Regex("""/\d+$""").containsMatchIn(href)) {
                null
            } else {
                val epNumber = Regex("""/(\d+)$""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val epName = if (rawText.isNotBlank()) rawText else "Episódio ${epNumber ?: ""}"
                newEpisode(fixUrl(href)) {
                    this.name = epName
                    this.episode = epNumber
                }
            }
        }.distinctBy { it.data }.sortedBy { it.episode ?: 0 }

        // Fallback: se nenhum episódio foi extraído mas a URL atual termina com número de episódio
        val finalEpisodes = if (episodes.isEmpty() && Regex("""/\d+$""").containsMatchIn(url)) {
            val epNum = Regex("""/(\d+)$""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            listOf(
                newEpisode(url) {
                    this.name = "Episódio $epNum"
                    this.episode = epNum
                }
            )
        } else {
            episodes
        }

        val isMovie = url.contains("/filmes") 
            || url.contains("filme") 
            || url.contains("-movie") 
            || url.contains("-film") 
            || rawTitle.contains("- Filme", ignoreCase = true)
            || rawTitle.contains("Filme", ignoreCase = true)
            || (finalEpisodes.size <= 1 && (url.contains("filme") || url.contains("movie") || url.contains("film")))

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, finalEpisodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, finalEpisodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
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
        val doc = try {
            app.get(data, headers = BROWSER_HEADERS).document
        } catch (e: Exception) {
            return false
        }

        val slugMatch = Regex("""/animes/([^/]+)/(\d+)""").find(data)
        val slug = slugMatch?.groupValues?.getOrNull(1)
        val ep = slugMatch?.groupValues?.getOrNull(2)

        // 1. Extração direta de tokens Blogger (GoogleVideo)
        val bloggerTokens = mutableListOf<String>()
        doc.select("iframe[src*='blogger.com']").forEach { iframe ->
            val src = iframe.attr("src")
            val tokenMatch = Regex("""token=([A-Za-z0-9_-]+)""").find(src)
            tokenMatch?.groupValues?.getOrNull(1)?.let { bloggerTokens.add(it) }
        }
        Regex("""blogger\.com/video\.g\?token=([A-Za-z0-9_-]+)""").findAll(doc.html()).forEach { m ->
            m.groupValues.getOrNull(1)?.let { bloggerTokens.add(it) }
        }

        for (token in bloggerTokens.distinct()) {
            if (extractBlogger(token, callback)) {
                found = true
            }
        }

        // 2. Extração direta via API JSON da CDN nativa (lightspeedst.net)
        val candidateApis = mutableListOf<String>()
        val dataVideoSrc = doc.selectFirst("video[data-video-src]")?.attr("data-video-src")
        if (!dataVideoSrc.isNullOrBlank()) {
            candidateApis.add(dataVideoSrc.substringBefore("?"))
            candidateApis.add(dataVideoSrc)
        }
        if (!slug.isNullOrBlank() && !ep.isNullOrBlank()) {
            candidateApis.add("$mainUrl/video/$slug/$ep")
            candidateApis.add("$mainUrl/video/$slug/$ep?tempsubs=1")
            candidateApis.add("$mainUrl/api/video/$slug/$ep")
        }

        for (apiUrl in candidateApis.distinct()) {
            try {
                val response = app.get(
                    apiUrl,
                    headers = API_HEADERS + mapOf("Referer" to data)
                )

                if (response.text.trim().startsWith("{")) {
                    val apiResp = response.parsedSafe<AnimeFireVideoResponse>()

                    if (!apiResp?.data.isNullOrEmpty()) {
                        for (item in apiResp.data) {
                            val videoUrl = item.src ?: continue
                            val label = item.label ?: "HD"
                            val qualityInt = when (label.lowercase()) {
                                "1080p", "f-hd", "fhd" -> Qualities.P1080.value
                                "720p", "hd"          -> Qualities.P720.value
                                "480p", "sd", "360p"   -> Qualities.P360.value
                                else                  -> Qualities.Unknown.value
                            }

                            val isM3u8 = videoUrl.contains(".m3u8")
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "AnimeFire CDN ($label)",
                                    url = videoUrl,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = qualityInt
                                }
                            )
                            found = true
                        }
                        if (found) break
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Extração de tags <video> ou <source> no DOM
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

        // 4. Extração de iframes de terceiros (Sendvid, Streamwish, Filemoon, Mixdrop)
        val iframes = doc.select("iframe[src]").mapNotNull {
            val src = it.attr("src").trim()
            if (src.isNotBlank() && !src.contains("topanimes.net/off/") && !src.contains("youtube.googleapis.com") && !src.contains("blogger.com")) fixUrl(src) else null
        }.distinct()

        for (ifr in iframes) {
            try {
                if (loadExtractor(ifr, data, subtitleCallback, callback)) {
                    found = true
                }
            } catch (_: Exception) {}
        }

        // 5. Mecanismo de WebViewResolver na página original do episódio (Fallback)
        if (!found) {
            val interceptRegex = Regex("""https?://.*(?:googlevideo\.com/videoplayback|lightspeedst\.net|blogger\.com/video-play|.*\.mp4|.*\.m3u8).*""")

            try {
                val wvResp = app.get(
                    data,
                    headers = BROWSER_HEADERS,
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
                            this.referer = if (isGoogleVideo) "https://www.blogger.com/" else "$mainUrl/"
                            this.quality = Qualities.P720.value
                        }
                    )
                    found = true
                }
            } catch (_: Exception) {}
        }

        return found
    }
}
