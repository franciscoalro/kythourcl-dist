package com.RedeCanais

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.Normalizer

class RedeCanais : MainAPI() {
    override var mainUrl = "https://www3.redecanais.vip"
    override var name = "RedeCanais"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
        TvType.AsianDrama
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val BROWSER_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://www3.redecanais.vip/",
            "Upgrade-Insecure-Requests" to "1"
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/filme/" to "Filmes Recentes",
        "$mainUrl/serie/" to "Séries Atualizadas",
        "$mainUrl/genero/acao/" to "Ação",
        "$mainUrl/genero/animacao/" to "Animação & Animes",
        "$mainUrl/genero/aventura/" to "Aventura",
        "$mainUrl/genero/comedia/" to "Comédia",
        "$mainUrl/genero/drama/" to "Drama",
        "$mainUrl/genero/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/genero/terror/" to "Terror",
        "$mainUrl/genero/crime/" to "Crime & Suspense",
        "$mainUrl/genero/romance/" to "Romance",
        "$mainUrl/genero/fantasia/" to "Fantasia",
        "$mainUrl/genero/familia/" to "Família & Kids",
        "$mainUrl/genero/documentario/" to "Documentário"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.removeSuffix("/")
        val url = if (page <= 1) {
            "$baseUrl/"
        } else {
            "$baseUrl/page/$page/"
        }

        val doc = app.get(url, headers = BROWSER_HEADERS).document
        val elements = doc.select(".items article, article.item, article[id^='post-'], .content article, article")
        val homeList = elements.mapNotNull { parseCard(it) }.distinctBy { it.url }

        val hasNext = hasNextPage(doc, page, homeList.size)
        return newHomePageResponse(
            listOf(HomePageList(request.name, homeList)),
            hasNext = hasNext
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val sanitizedQuery = formatSearchQuery(query)
        if (sanitizedQuery.isBlank()) return emptyList()

        val searchUrl = "$mainUrl/search/$sanitizedQuery/"

        return try {
            val doc = app.get(searchUrl, headers = BROWSER_HEADERS).document
            val elements = doc.select(".result-item, .search-page .result-item, article.item, .items article, article[id^='post-'], article")
            elements.mapNotNull { parseCard(it) }.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = BROWSER_HEADERS).document

        val rawTitle = doc.selectFirst(".sheader .data h1, h1.entry-title, h1")?.text()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "RedeCanais"

        val title = cleanMediaTitle(rawTitle)

        val poster = doc.selectFirst(".poster img, .imagen img, .cover img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
        } ?: doc.selectFirst("meta[property='og:image']")?.attr("content")

        val plot = doc.select(".sinopse p, .description p, .overview p, #info p")
            .map { it.text().trim() }
            .firstOrNull { it.length > 25 && !it.contains("Assistir", true) && !it.contains("RedeCanais", true) }
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")

        val year = doc.selectFirst(".sheader .date, .sheader span.year, .date, span.year, a[href*='/ano/']")?.text()
            ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }

        // Apenas gêneros do cabeçalho do conteúdo
        val tags = doc.select(".sheader .data .genres a, .sheader .sgeneros a, .sheader a[href*='/genero/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val rating = doc.selectFirst(".dt_rating_vgs, .rating, .imdb")?.text()
            ?.let { Regex("""[\d.]+""").find(it)?.value?.toDoubleOrNull() }
            ?.let { Score.from10(it) }

        // Extrai e higieniza episódios de séries
        val episodes = doc.select(".se-a ul.episodios li, .episodios li, a[href*='/episodio/']").mapNotNull { el ->
            val aTag = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNull null
            val epHref = fixUrl(aTag.attr("href"))
            if (!epHref.contains("/episodio/")) return@mapNotNull null

            val numerando = el.selectFirst(".numerando")?.text() ?: ""
            val (season, episode) = Regex("""(\d+)\s*[-xX]\s*(\d+)""").find(numerando)?.let {
                it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
            } ?: Regex("""[-_/](\d+)x(\d+)""").find(epHref)?.let {
                it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
            } ?: (1 to null)

            val titleDiv = el.selectFirst(".episodiotitle")
            val rawEpName = if (titleDiv != null) {
                val dateText = titleDiv.selectFirst("span.date, .date")?.text().orEmpty()
                titleDiv.text().replace(dateText, "").trim()
            } else {
                aTag.text().trim()
            }

            // Limpeza aprofundada de ruídos e datas
            val cleanEpName = rawEpName
                .replace(title, "", ignoreCase = true)
                .replace(Regex("""(?i)\d+ª\s*Temporada\s*Epis[oó]dio\s*\d+"""), "")
                .replace(Regex("""(?i)\bEpis[oó]dio\s*\d+\b"""), "")
                .replace(Regex("""^\d+\s*[-xX]\s*\d+\s*[-:]*\s*"""), "")
                .replace(Regex("""\b\d{2}/\d{2}/\d{4}\b"""), "")
                .replace(Regex("""^[\s\-–—:]+"""), "")
                .replace(Regex("""[\s\-–—:]+$"""), "")
                .trim()

            val finalEpName = if (cleanEpName.isBlank()) "Episódio ${episode ?: 1}" else cleanEpName

            val epThumb = el.selectFirst(".imagen img, img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }

            newEpisode(epHref) {
                this.name = finalEpName
                this.season = season
                this.episode = episode
                this.posterUrl = epThumb
            }
        }.distinctBy { it.data }

        val isMovie = episodes.isEmpty() || url.contains("/filme/")

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = optimizePosterUrl(poster.orEmpty())
                this.plot = plot
                this.tags = tags
                this.year = year
                this.score = rating
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = optimizePosterUrl(poster.orEmpty())
                this.plot = plot
                this.tags = tags
                this.year = year
                this.score = rating
            }
        }
    }

    data class MegaEmbedSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = BROWSER_HEADERS).document

        val options = doc.select("#playeroptionsul li, .dooplay_player_option")
        val isMovie = data.contains("/filme/") || !data.contains("/episodio/")
        val postType = if (isMovie) "movie" else "tv"

        var foundAny = false

        for (opt in options) {
            val postId = opt.attr("data-post").ifBlank { null } ?: continue
            val nume = opt.attr("data-nume").ifBlank { null } ?: continue
            val optType = opt.attr("data-type").ifBlank { postType }

            val embedUrl = resolveDooPlayerEmbed(postId, optType, nume, data) ?: continue

            // 1. Tratamento dedicado MegaEmbed
            if (embedUrl.contains("megaembed.com")) {
                if (extractMegaEmbed(embedUrl, subtitleCallback, callback)) {
                    foundAny = true
                    continue
                }
            }

            // 2. Tratamento dedicado PlayerFlix
            if (embedUrl.contains("playerflixapi.com")) {
                if (extractPlayerFlix(embedUrl, subtitleCallback, callback)) {
                    foundAny = true
                    continue
                }
            }

            // 3. Delegação para extratores nativos do CloudStream
            try {
                if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                    foundAny = true
                    continue
                }
            } catch (_: Exception) {}

            // 4. Fallback de streams diretos (.m3u8 / .mp4)
            try {
                if (extractGenericStream(embedUrl, callback)) {
                    foundAny = true
                }
            } catch (_: Exception) {}
        }

        return foundAny
    }

    private suspend fun extractMegaEmbed(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val cleanUrl = url.substringBefore("#")
            val html = app.get(cleanUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to mainUrl)).text

            val sourcesMatch = Regex("""var\s+sources\s*=\s*(\[\{.*?\}\]);""").find(html)?.groupValues?.getOrNull(1)
            if (!sourcesMatch.isNullOrBlank()) {
                val sources = tryParseJson<List<MegaEmbedSource>>(sourcesMatch)
                sources?.forEach { s ->
                    val file = s.file ?: return@forEach
                    val isM3u8 = file.contains(".m3u8")
                    val isMp4 = file.contains(".mp4")

                    if (isM3u8 || isMp4) {
                        callback.invoke(
                            newExtractorLink(
                                source = "MegaEmbed",
                                name = "MegaEmbed - ${s.label ?: "HD"}",
                                url = file,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://megaembed.com/")
                                this.quality = Qualities.P1080.value
                            }
                        )
                        found = true
                    } else {
                        try {
                            if (loadExtractor(file, cleanUrl, subtitleCallback, callback)) {
                                found = true
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
        return found
    }

    private suspend fun extractPlayerFlix(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val cleanUrl = url.substringBefore("#")
            val isSeries = cleanUrl.contains("/serie/")
            val idMatch = if (isSeries) {
                Regex("""/serie/([^/?#]+)/(\d+)/(\d+)""").find(cleanUrl)
            } else {
                Regex("""/filme/([^/?#]+)""").find(cleanUrl)
            }

            val ajaxUrl = if (isSeries && idMatch != null) {
                val id = idMatch.groupValues[1]
                val s = idMatch.groupValues[2]
                val e = idMatch.groupValues[3]
                "https://playerflixapi.com/pages/ajax.php?id=$id&type=tv&season=$s&episode=$e"
            } else if (idMatch != null) {
                val id = idMatch.groupValues[1]
                "https://playerflixapi.com/pages/ajax.php?id=$id&type=movie"
            } else null

            if (ajaxUrl != null) {
                val ajaxHtml = app.get(
                    ajaxUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to cleanUrl,
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).text

                val soup = Jsoup.parse(ajaxHtml)
                for (opt in soup.select(".player-option, [data-embed]")) {
                    val b64 = opt.attr("data-embed")
                    if (b64.isNotBlank()) {
                        val decoded = try {
                            String(Base64.decode(b64, Base64.DEFAULT))
                        } catch (_: Exception) { "" }

                        if (decoded.isNotBlank() && decoded.startsWith("http")) {
                            val isM3u8 = decoded.contains(".m3u8")
                            val isMp4 = decoded.contains(".mp4")

                            if (isM3u8 || isMp4) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = "PlayerFlix",
                                        name = "PlayerFlix - ${opt.text().trim()}",
                                        url = decoded,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to cleanUrl)
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                                found = true
                            } else {
                                try {
                                    if (loadExtractor(decoded, cleanUrl, subtitleCallback, callback)) {
                                        found = true
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return found
    }

    private suspend fun resolveDooPlayerEmbed(postId: String, type: String, nume: String, pageUrl: String): String? {
        try {
            val jsonUrl = "$mainUrl/wp-json/dooplayer/v1/$postId/$type/$nume"
            val jsonResp = app.get(
                jsonUrl,
                headers = BROWSER_HEADERS + mapOf("Referer" to pageUrl, "X-Requested-With" to "XMLHttpRequest")
            ).text

            val embedMatch = Regex(""""embed_url"\s*:\s*"([^"]+)"""").find(jsonResp)?.groupValues?.getOrNull(1)
            if (!embedMatch.isNullOrBlank()) {
                val clean = embedMatch.replace("\\/", "/")
                return if (clean.contains("<iframe")) {
                    Jsoup.parse(clean).selectFirst("iframe")?.attr("src") ?: clean
                } else clean
            }
        } catch (_: Exception) {}

        try {
            val ajaxResp = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to postId,
                    "nume" to nume,
                    "type" to type
                ),
                headers = BROWSER_HEADERS + mapOf("Referer" to pageUrl, "X-Requested-With" to "XMLHttpRequest")
            ).text

            val embedMatch = Regex(""""embed_url"\s*:\s*"([^"]+)"""").find(ajaxResp)?.groupValues?.getOrNull(1)
            if (!embedMatch.isNullOrBlank()) {
                val clean = embedMatch.replace("\\/", "/")
                return if (clean.contains("<iframe")) {
                    Jsoup.parse(clean).selectFirst("iframe")?.attr("src") ?: clean
                } else clean
            }
        } catch (_: Exception) {}

        return null
    }

    private suspend fun extractGenericStream(embedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val cleanUrl = embedUrl.substringBefore("#")
            val html = app.get(cleanUrl, headers = BROWSER_HEADERS + mapOf("Referer" to cleanUrl)).text

            val m3u8 = Regex("""["'](https?://[^\s"']+\.m3u8[^\s"']*)["']""").find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(html)?.groupValues?.getOrNull(1)

            val mp4 = Regex("""["'](https?://[^\s"']+\.mp4[^\s"']*)["']""").find(html)?.groupValues?.getOrNull(1)

            val streamUrl = m3u8 ?: mp4 ?: return false
            val isM3u8 = streamUrl.contains(".m3u8")

            val serverName = when {
                embedUrl.contains("superflixapi") -> "SuperFlix"
                embedUrl.contains("megaembed") -> "MegaEmbed"
                embedUrl.contains("playerflixapi") -> "PlayerFlix"
                embedUrl.contains("fembed") -> "FEmbed"
                embedUrl.contains("embedplayer") -> "EmbedPlayer"
                embedUrl.contains("embedplay") -> "EmbedPlay"
                embedUrl.contains("vsembed") -> "VSEmbed"
                else -> "RedeCanais Stream"
            }

            callback.invoke(
                newExtractorLink(
                    source = serverName,
                    name = "$serverName (${if (isM3u8) "HLS" else "MP4"})",
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to cleanUrl)
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun parseCard(element: Element): SearchResponse? {
        val aTag = element.selectFirst(".title a, .thumbnail a, h3 a, a[href*='/filme/'], a[href*='/serie/']")
            ?: element.selectFirst("div.poster a, a")
            ?: (if (element.tagName() == "a") element else null)
            ?: return null

        val link = aTag.attr("href").ifBlank { return null }
        if (link.endsWith("/filme/") || link.endsWith("/serie/") || link.contains("/genero/") || link.contains("/ano/")) {
            return null
        }
        val fullUrl = fixUrl(link)

        var rawTitle = ""
        val titleEl = element.selectFirst(".title a, .details .title, h3 a, h3, a[title]")
        if (titleEl != null) {
            rawTitle = titleEl.text().ifBlank { titleEl.attr("title") }
        }
        if (rawTitle.isBlank()) {
            rawTitle = aTag.attr("title")
        }
        if (rawTitle.isBlank()) {
            rawTitle = element.selectFirst("img")?.attr("alt").orEmpty()
        }

        val cleanTitle = cleanMediaTitle(rawTitle)
        if (cleanTitle.isBlank()) return null

        val rawPoster = element.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-original") }.ifBlank { img.attr("src") }
        }.orEmpty()
        val poster = optimizePosterUrl(rawPoster)

        val isSeries = fullUrl.contains("/serie/") || rawTitle.contains("Temporada", ignoreCase = true)
        val qualityText = element.selectFirst("span.quality, span.hd")?.text().orEmpty()
        val quality = if (qualityText.contains("4K", ignoreCase = true)) SearchQuality.UHD else SearchQuality.HD

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanTitle, fullUrl, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(cleanTitle, fullUrl, TvType.Movie) {
                this.posterUrl = poster
                this.quality = quality
            }
        }
    }

    private fun cleanMediaTitle(raw: String): String {
        return raw
            .replace(Regex("""(?i)\s*-\s*Rede\s*Canais.*$"""), "")
            .replace(Regex("""(?i)\s*\|\s*Rede\s*Canais.*$"""), "")
            .replace(Regex("""(?i)^Assistir\s+"""), "")
            .replace(Regex("""(?i)\s+Online(\s+Grátis|\s+em\s+HD)?\b"""), "")
            .replace(Regex("""(?i)\s*-\s*(Dublado|Legendado|Nacional|Dual\s*Áudio).*$"""), "")
            .replace(Regex("""(?i)\s*\((Dublado|Legendado|Nacional|Dual\s*Áudio)\)"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun optimizePosterUrl(url: String): String {
        if (url.isBlank()) return ""
        return if (url.contains("image.tmdb.org")) {
            url.replace("/w92/", "/w300/").replace("/w154/", "/w300/").replace("/w185/", "/w300/")
        } else {
            fixUrl(url)
        }
    }

    private fun formatSearchQuery(query: String): String {
        val normalized = Normalizer.normalize(query.trim(), Normalizer.Form.NFD)
        val withoutAccents = normalized.replace(Regex("""\p{InCombiningDiacriticalMarks}+"""), "")
        val clean = withoutAccents.replace(Regex("""[^a-zA-Z0-9\s\-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .lowercase()
        return URLEncoder.encode(clean, "UTF-8").replace("+", "-")
    }

    private fun hasNextPage(document: Document, currentPage: Int, itemCount: Int): Boolean {
        if (document.select("a.next, a.next.page-numbers, a[rel='next'], a:contains(Próximo)").isNotEmpty()) return true
        val nextPage = currentPage + 1
        if (document.select("a.page-numbers[href*='/page/$nextPage/']").isNotEmpty()) return true
        return itemCount >= 14
    }
}
