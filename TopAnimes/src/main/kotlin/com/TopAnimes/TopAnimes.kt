package com.TopAnimes

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import com.fasterxml.jackson.annotation.JsonProperty

class TopAnimes : MainAPI() {
    override var mainUrl = "https://topanimes.net"
    override var name = "TopAnimes"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val BROWSER_HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        val titleEl = this.selectFirst(".title, h3, h2")
        val rawTitle = titleEl?.text()?.trim() ?: a.text().trim()
        if (rawTitle.isBlank()) return null

        val title = rawTitle
            .replace(Regex("""^Assistir\s+""", RegexOption.IGNORE_CASE), "")
            .substringBefore(" Online")
            .substringBefore(" em HD")
            .trim()

        val img = this.selectFirst("img")
        val posterUrl = img?.let { 
            it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src") 
        }?.let { fixUrlNull(it) }

        val isMovie = href.contains("/filmes/") || rawTitle.contains("Filme", ignoreCase = true)
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newMovieSearchResponse(title, fixUrl(href), tvType) {
            this.posterUrl = posterUrl
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/animes/" to "Todos os Animes",
        "$mainUrl/filmes/" to "Filmes de Anime",
        "$mainUrl/tipo/legendado/" to "Animes Legendados",
        "$mainUrl/tipo/dublado/" to "Animes Dublados",
        "$mainUrl/tipo/donghua/" to "Animes Donghua (Chineses)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val doc = app.get(url, headers = BROWSER_HEADERS).document
        val items = doc.select("article.item, div.result-item, article.tvshows").mapNotNull { it.toSearchResult() }
        val hasNext = doc.selectFirst("a:contains(Próximo), a:contains(Next), .next, #paginador a") != null
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query}"
        val doc = app.get(url, headers = BROWSER_HEADERS).document
        return doc.select("article.item, div.result-item, article.tvshows, div.anime").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = BROWSER_HEADERS).document
        
        // 1. Título
        val rawTitle = doc.selectFirst("h1, .dataplus h1, .entry-title")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "Anime"

        val title = rawTitle
            .replace(Regex("""^Assistir\s+""", RegexOption.IGNORE_CASE), "")
            .substringBefore(" Online")
            .substringBefore(" em HD")
            .trim()

        // 2. Poster
        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")?.let { fixUrlNull(it) }
            ?: doc.selectFirst(".poster img, .imagen img")?.attr("src")?.let { fixUrlNull(it) }

        // 3. Sinopse (Plot)
        val description = doc.select("#dato-2 p, .wp-content p, .sinopse p, .description p, #info p")
            .map { it.text().trim() }
            .firstOrNull { p -> p.length > 15 && !p.contains("Assistir grátis", ignoreCase = true) && !p.contains("sinopse", ignoreCase = true) }
            ?: doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=\"description\"]")?.attr("content")?.trim()

        // 4. Tags / Gêneros + Áudio
        val audioTags = mutableListOf<String>()
        val pageText = doc.text()
        if (rawTitle.contains("dublado", ignoreCase = true) || pageText.contains("dublado", ignoreCase = true)) audioTags.add("Dublado")
        if (rawTitle.contains("legendado", ignoreCase = true) || pageText.contains("legendado", ignoreCase = true)) audioTags.add("Legendado")

        val tags = (doc.select(".sgeneros a, .dataplus a[href*=\"/category/\"], .dataplus a[href*=\"/genre/\"], .genres a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()) + audioTags

        // 5. Ano de Lançamento
        val year = doc.selectFirst(".year, span.year, .date, a[href*=\"/ano/\"]")?.text()
            ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }

        // 6. Episódios
        val episodes = doc.select("ul.episodios li a, #episodios a, a[href*=\"/episodio/\"]").mapNotNull { el ->
            val epHref = el.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!epHref.contains("/episodio/")) return@mapNotNull null

            val eNum = Regex("""episodio-(\d+)|\bE(\d+)\b""", RegexOption.IGNORE_CASE).find(epHref)?.let { m ->
                m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: m.groupValues.getOrNull(2)
            }?.toIntOrNull()

            var epName = el.text().trim()
                .replace(Regex("""^\d+\s*-\s*"""), "")
                .trim()

            if (epName.isBlank() || epName.matches(Regex("""^\d+$"""))) {
                epName = "Episódio ${eNum ?: 1}"
            }

            newEpisode(fixUrl(epHref)) {
                this.name = epName
                this.season = 1
                this.episode = eNum
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

        // 7. Recomendações
        val recommendations = doc.select(".relacionados a, #single_relacionados a, .related-posts a, article.item a, div.item a").mapNotNull { el ->
            val recHref = el.attr("href").takeIf { it.isNotBlank() && !it.startsWith("#") && (it.contains("/animes/") || it.contains("/filmes/")) } ?: return@mapNotNull null
            val imgEl = el.selectFirst("img")
            val recTitle = imgEl?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: el.selectFirst("h4, h3, h2, .title")?.text()?.trim() ?: return@mapNotNull null

            val recPoster = imgEl?.let { img ->
                img.attr("data-src").takeIf { s -> s.isNotBlank() } ?: img.attr("src")
            }?.let { fixUrlNull(it) }

            newMovieSearchResponse(recTitle, fixUrl(recHref), TvType.Anime) {
                this.posterUrl = recPoster
            }
        }.filter { it.url != url }.distinctBy { it.url }.take(20)

        val isMovie = episodes.isEmpty() || url.contains("/filmes/")

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.showStatus = ShowStatus.Ongoing
                this.recommendations = recommendations
            }
        }
    }

    private data class AlibabaSource(
        @JsonProperty("qualidade") val qualidade: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    private data class AlibabaApiResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("midias") val midias: List<AlibabaSource>? = null
    )

    data class DooPlayerAjaxResponse(
        val embed_url: String? = null,
        val type: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val docHtml = app.get(data, headers = BROWSER_HEADERS).text
        val doc = Jsoup.parse(docHtml)
        
        val allRawUrls = mutableListOf<String>()
        doc.select("iframe[src]").forEach { allRawUrls.add(it.attr("src")) }
        doc.select("[data-src]").forEach { allRawUrls.add(it.attr("data-src")) }
        doc.select("[data-embed]").forEach { allRawUrls.add(it.attr("data-embed")) }

        // Consulta todos os servidores cadastrados no DooPlayer
        doc.select(".dooplay_player_option").forEach { opt ->
            val post = opt.attr("data-post").takeIf { it.isNotBlank() }
            val nume = opt.attr("data-nume").takeIf { it.isNotBlank() }
            val type = opt.attr("data-type").takeIf { it.isNotBlank() }
            if (post != null && nume != null) {
                try {
                    val ajaxResp = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to post,
                            "nume" to nume,
                            "type" to (type ?: "tv")
                        ),
                        headers = BROWSER_HEADERS + mapOf("Referer" to data)
                    ).parsedSafe<DooPlayerAjaxResponse>()
                    ajaxResp?.embed_url?.let { if (it.isNotBlank()) allRawUrls.add(it) }
                } catch (_: Exception) {}
            }
        }

        val iframeUrls = allRawUrls.mapNotNull { raw ->
            val fixed = fixUrl(raw)
            if (fixed.contains("rogeriobetin.com") || fixed.contains("/bg/")) {
                val token = Regex("""[?&]id=([^&]+)""").find(fixed)?.groupValues?.getOrNull(1)
                if (token != null) {
                    "https://www.blogger.com/video.g?token=$token"
                } else fixed
            } else if (fixed.contains("aviso/?url=") || fixed.contains("url=")) {
                val target = Regex("""[?&]url=([^&]+)""").find(fixed)?.groupValues?.getOrNull(1)
                target?.let { 
                    try { java.net.URLDecoder.decode(it, "UTF-8").substringBefore("&poster=") } catch (_: Exception) { fixed }
                } ?: fixed
            } else fixed
        }.filter { 
            val cleanPath = it.substringBefore("?").lowercase()
            it.isNotBlank() && 
            !cleanPath.endsWith(".jpg") && 
            !cleanPath.endsWith(".png") && 
            !it.contains("topanimes.net/off/") &&
            !it.contains("strp2p") &&
            !it.contains("png.strp2p.com") &&
            !it.contains("mywallpaper-4k-image.net") &&
            !it.contains("api.anivideo.net")
        }.distinct()

        if (iframeUrls.isEmpty()) return false

        var found = false
        for (iframeUrl in iframeUrls) {
            try {
                val decodedIframe = fixUrl(iframeUrl)

                // 1. Caso prioritário: sk-api.alibabacdn.net API JSON direct (1080p, 720p, 360p)
                if (decodedIframe.contains("sk-api.alibabacdn.net") || decodedIframe.contains("mode=api2") || decodedIframe.contains("alibabacdn")) {
                    val apiUrl = if (decodedIframe.contains("mode=api2")) decodedIframe else "$decodedIframe&mode=api2"
                    try {
                        val apiResp = app.get(apiUrl, headers = BROWSER_HEADERS + mapOf("Referer" to mainUrl)).parsedSafe<AlibabaApiResponse>()
                        if (apiResp?.status == "success" && !apiResp.midias.isNullOrEmpty()) {
                            for (m in apiResp.midias) {
                                val streamUrl = m.url ?: continue
                                val qualLabel = when (m.qualidade?.uppercase()) {
                                    "SD" -> "1080p"
                                    "LD" -> "720p"
                                    "FD" -> "360p"
                                    else -> m.qualidade ?: "HD"
                                }
                                val qualityInt = when (qualLabel) {
                                    "1080p" -> Qualities.P1080.value
                                    "720p" -> Qualities.P720.value
                                    "360p" -> Qualities.P360.value
                                    else -> Qualities.Unknown.value
                                }
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "TopAnimes ($qualLabel)",
                                        url = streamUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = mainUrl
                                        this.quality = qualityInt
                                    }
                                )
                                found = true
                            }
                            if (found) continue
                        }
                    } catch (e: Exception) {
                        println("TopAnimes: erro ao ler API Alibaba JSON $apiUrl: ${e.message}")
                    }
                }

                // 2. Tenta extratores embutidos do CloudStream (Filemoon, Vidguard, Streamtape, Mixdrop, Vidstreaming, etc.)
                if (loadExtractor(decodedIframe, data, subtitleCallback, callback)) {
                    found = true
                    continue
                }

                // 2. Caso especial: antivirus2 com M3U8 no parametro ou HTML
                if (iframeUrl.contains("cdn_stream.m3u8") || iframeUrl.contains(".m3u8")) {
                    val directM3u8 = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(iframeUrl)?.groupValues?.getOrNull(1)
                    if (directM3u8 != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "TopAnimes (HD)",
                                url = directM3u8,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                        continue
                    }
                }

                // 3. Inspeção do HTML do Iframe para tags <source> ou links de mídia
                val iframeResp = app.get(iframeUrl, headers = BROWSER_HEADERS + mapOf("Referer" to data))
                val iframeHtml = iframeResp.text

                val m3u8Match = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""").find(iframeHtml)?.groupValues?.getOrNull(1)
                val sourceTag = Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.getOrNull(1)?.let { fixUrl(it) }
                val targetUrl = m3u8Match ?: sourceTag

                if (targetUrl != null) {
                    val isM3u8 = targetUrl.contains(".m3u8")
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "TopAnimes",
                            url = targetUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = iframeUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                }
            } catch (e: Exception) {
                println("TopAnimes: erro ao processar iframe $iframeUrl - ${e.message}")
                e.printStackTrace()
            }
        }
        return found
    }
}
