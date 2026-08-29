package com.NetCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.network.CloudflareKiller
import android.webkit.CookieManager
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import android.util.Base64

class NetCine : MainAPI() {
    override var mainUrl = "https://nnn1.lat"
    override var name = "NetCine"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie
    )

    companion object {
        private const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"
        private val BROWSER_HEADERS = mapOf("User-Agent" to UA, "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8")
        val iframeRegex = Regex("""<div\s+id="(play-\d+)"[^>]*>.*?<iframe\s+src="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
        val labelRegex = Regex("""label\s*:\s*["']([^"']+)["']""")
        val videoSourceRegex = Regex("""href\s*=\s*["']([^"']*(?:hls\.php|hlsarchive\.php\?hls|gc\d+\.php|playerarchive\.php)[^"']*)["']""")
        val nextRegex = Regex("""next|pr[oó]ximo""", RegexOption.IGNORE_CASE)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2")?.text()?.trim() ?: this.selectFirst(".title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val url = fixUrl(href)
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src")
        }?.let { fixUrlNull(it) }
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/ultimos-filmes/" to "Últimos Filmes",
        "$mainUrl/tvshows/" to "Séries Atualizadas",
        "$mainUrl/category/animacao/" to "Animações e Desenhos",
        "$mainUrl/category/acao/" to "Filmes de Ação",
        "$mainUrl/category/comedia/" to "Filmes de Comédia",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val doc = app.get(url, headers = BROWSER_HEADERS).document
        val items = doc.select("div.movie").mapNotNull { it.toSearchResult() }
        val fallback = if (items.isEmpty()) {
            doc.select("#box_movies .movie").mapNotNull { it.toSearchResult() }
        } else items
        val hasNext = doc.selectFirst("a:contains(Próximo), a:contains(Next), .next, #paginador a") != null
        return newHomePageResponse(request.name, fallback, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query}"
        val doc = app.get(url, headers = BROWSER_HEADERS).document
        val results = doc.select("div.movie").mapNotNull { it.toSearchResult() }
        if (results.isNotEmpty()) return results
        return doc.select("#box_movies .movie, .items .movie").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = BROWSER_HEADERS).document
        
        // 1. Título com limpeza rigorosa de SEO e marcas d'água
        val rawTitle = doc.selectFirst(".dataplus h1, h1, .data h1, .entry-title, .title")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("title")?.text() ?: "NetCine"

        val title = rawTitle
            .replace(Regex("""^Assistir\s+""", RegexOption.IGNORE_CASE), "")
            .substringBefore(" Online")
            .substringBefore(" em HD")
            .substringBefore(" no NetCine")
            .substringBefore(" - NetCine")
            .substringBefore(" Dublado")
            .substringBefore(" Legendado")
            .trim()

        // 2. Capa (Poster) com fallbacks para meta tags e seletores CSS de imagem
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
            ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")?.let { fixUrlNull(it) }
            ?: doc.selectFirst(".poster img, .imagen img, img[src*=\"wp-content/uploads\"]")?.attr("src")?.let { fixUrlNull(it) }

        // 3. Sinopse (Plot) sem textos promocionais/SEO ou listas de atores
        val description = doc.select("#dato-2 p, .sinopse p")
            .map { it.text().trim() }
            .firstOrNull { p -> p.length > 20 && !p.contains("sinopse", ignoreCase = true) }
            ?: doc.select(".dataplus p, .wp-content p, .description, .contenido p, #info p")
                .map { it.text().trim() }
                .firstOrNull { p -> 
                    p.length > 35 && 
                    !p.contains("Assistir grátis", ignoreCase = true) && 
                    !p.contains("sem propagandas", ignoreCase = true) &&
                    !p.contains("Últimas Atualizações", ignoreCase = true) &&
                    !p.matches(Regex(""".*\b\d+\s*min\b.*""", RegexOption.IGNORE_CASE)) &&
                    !p.matches(Regex("""^[A-Z][a-zà-ú]+(?:\s+[A-Z][a-zà-ú]+)*(?:\s*,\s*[A-Z][a-zà-ú]+(?:\s+[A-Z][a-zà-ú]+)*)+$"""))
                }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                ?.takeIf { !it.contains("Assistir grátis", ignoreCase = true) && !it.contains("sem propagandas", ignoreCase = true) }
            ?: doc.select("p").map { it.text().trim() }.firstOrNull { it.length > 40 }

        // 4. Tags / Gêneros + Indicadores de Áudio (Dublado / Legendado)
        val audioTags = mutableListOf<String>()
        val pageText = doc.text()
        if (rawTitle.contains("dublado", ignoreCase = true) || pageText.contains("dublado", ignoreCase = true)) audioTags.add("Dublado")
        if (rawTitle.contains("legendado", ignoreCase = true) || pageText.contains("legendado", ignoreCase = true)) audioTags.add("Legendado")
        if (rawTitle.contains("nacional", ignoreCase = true) || pageText.contains("nacional", ignoreCase = true)) audioTags.add("Nacional")

        val tags = (doc.select(".sgeneros a, .dataplus a[href*=\"/category/\"], .dataplus a[href*=\"/genre/\"], .scontent .generos a, .genres a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .takeIf { it.isNotEmpty() && it.size < 15 }
            ?: doc.select(".generos a").map { it.text().trim() }.filter { it.isNotBlank() }.take(5)) + audioTags

        // 5. Ano de lançamento
        val year = doc.selectFirst(".year, span.year, .date, a[href*=\"/ano-lancamento/\"]")?.text()
            ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }
            ?: Regex("""\b(19|20)\d{2}\b""").find(doc.text())?.value?.toIntOrNull()

        // 6. Avaliação / Score (IMDB)
        val score = doc.selectFirst(".imdb, .rating, .vote, .score")?.text()
            ?.let { Regex("""[\d.]+""").find(it)?.value?.toDoubleOrNull() }
            ?.let { Score.from10(it) }

        // 7. Extração e limpeza de episódios para Séries
        val episodes = doc.select(".episodios li a, ul.episodios a, .se-a a, a[href*=\"/episode/\"], .les-content a").mapNotNull { el ->
            val epHref = el.attr("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!epHref.contains("/episode/")) return@mapNotNull null
            
            val sNum = Regex("""(\d+)x\d+|\bS(\d+)E""", RegexOption.IGNORE_CASE).find(epHref)?.let { m ->
                m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: m.groupValues.getOrNull(2)
            }?.toIntOrNull()
            
            val eNum = Regex("""\d+x(\d+)|\bE(\d+)\b""", RegexOption.IGNORE_CASE).find(epHref)?.let { m ->
                m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: m.groupValues.getOrNull(2)
            }?.toIntOrNull()

            var epName = el.text().trim()
                .replace(Regex("""^\d+\s*-\s*\d+\s*"""), "")
                .replace(Regex("""\s*\d+\s*min$""", RegexOption.IGNORE_CASE), "")
                .trim()

            if (epName.isBlank() || epName.matches(Regex("""^\d+$"""))) {
                epName = "Episódio ${eNum ?: 1}"
            }

            newEpisode(fixUrl(epHref)) {
                this.name = epName
                this.season = sNum ?: 1
                this.episode = eNum
            }
        }.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

        val isTvShow = episodes.isNotEmpty() || url.contains("/tvshows/") || doc.selectFirst("a:contains(Episódios)") != null
        
        // 8. Recomendações ("Mais como este") nativas - extrai da div.relacionados
        val recommendations = doc.select(".relacionados a, #single_relacionados a, div.movie").mapNotNull { el ->
            val recHref = el.attr("href")?.takeIf { 
                it.isNotBlank() && !it.startsWith("#") && !it.contains("/category/") && !it.contains("/ano-lancamento/") 
            } ?: return@mapNotNull null
            
            val imgEl = el.selectFirst("img")
            var recTitle = imgEl?.attr("alt")?.trim()?.takeIf { it.isNotBlank() && !it.contains("NetCine", ignoreCase = true) }
            if (recTitle == null) {
                recTitle = el.selectFirst("h4, h3, h2, .title")?.text()?.trim()
            }
            if (recTitle.isNullOrBlank()) return@mapNotNull null

            val recPoster = imgEl?.let { img ->
                img.attr("data-src").takeIf { s -> s.isNotBlank() } ?: img.attr("src")
            }?.let { fixUrlNull(it) }

            newMovieSearchResponse(recTitle, fixUrl(recHref), TvType.Movie) {
                this.posterUrl = recPoster
            }
        }.filter { it.url != url }.distinctBy { it.url }.take(24)

        // 9. Duração em minutos (para filmes)
        val durationMin = doc.selectFirst(".duration, span.runtime, .dataplus, #dato-1")?.text()
            ?.let { Regex("""\b(\d+)\s*min\b""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        // 10. Trailer do YouTube (opcional e seguro)
        val trailerUrl = doc.selectFirst("a[href*=\"youtube.com\"], a[href*=\"youtu.be\"], iframe[src*=\"youtube.com\"]")
            ?.let { el -> el.attr("href").takeIf { h -> h.isNotBlank() } ?: el.attr("src") }
            ?.let { fixUrlNull(it) }

        return if (isTvShow) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.score = score
                this.showStatus = ShowStatus.Ongoing
                this.recommendations = recommendations
                if (trailerUrl != null) this.addTrailer(trailerUrl)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.score = score
                this.duration = durationMin
                this.recommendations = recommendations
                if (trailerUrl != null) this.addTrailer(trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val docHtml = app.get(data, headers = BROWSER_HEADERS).text
        // Definitivo v16: pula captcha de página (matrix-revolutions/?captcha_img=1 é sempre HTML 180668) - bypass direto para iframes/hls.php
        // Se houver captcha na página sem iframe, tenta direto nos iframes abaixo
        // Bypass 1: tenta m3u8 direto no html da página (sem iframe) - bypass captcha para alguns mirrors
        Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(docHtml)?.value?.let { directM3u8 ->
            callback.invoke(
                newExtractorLink(source = name, name = "NetCine", url = directM3u8, type = ExtractorLinkType.M3U8) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
        val iframeMatches = iframeRegex.findAll(docHtml).toList()
        val iframeUrls = if (iframeMatches.isNotEmpty()) {
            iframeMatches.map { it.groupValues[2] }.map { fixUrl(it) }
        } else {
            val doc = Jsoup.parse(docHtml)
            // séries usam player-menu #play-1 iframe, filmes usam media-player - inclui 1xbet/gc2/nv32 para séries
            doc.select("iframe[src]").map { fixUrl(it.attr("src")) }.filter { it.contains("media-player") || it.contains("player") || it.contains("cdn") || it.contains("embed") || it.contains("1xbet") || it.contains("gc") || it.contains("nv32") }
                .ifEmpty { doc.select("iframe[src]").map { fixUrl(it.attr("src")) } }
        }
        if (iframeUrls.isEmpty()) {
            // fallback: tenta data-src ou data-embed em divs play-*
            val doc = Jsoup.parse(docHtml)
            val embedUrls = doc.select("[data-embed], [data-src]").mapNotNull { el ->
                el.attr("data-embed").takeIf { it.isNotBlank() } ?: el.attr("data-src").takeIf { it.isNotBlank() }
            }.map { fixUrl(it) }
            if (embedUrls.isNotEmpty()) {
                // tenta como iframe
                for (eu in embedUrls) {
                    try {
                        val r = app.get(eu, headers = BROWSER_HEADERS + mapOf("Referer" to data))
                        val m3u8 = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(r.text)?.value
                        if (m3u8 != null) {
                            callback.invoke(newExtractorLink(source = name, name = "NetCine", url = m3u8, type = ExtractorLinkType.M3U8) {
                                this.referer = eu; this.quality = Qualities.Unknown.value
                            })
                            return true
                        }
                    } catch (_: Exception) {}
                }
            }
            return false
        }
        var found = false
        for (iframeUrl in iframeUrls) {
            try {
                val headers = BROWSER_HEADERS + mapOf("Referer" to data)
                val iframeResp = app.get(iframeUrl, headers = headers)
                val iframeHtml = iframeResp.text
                val iframeCookies = extractCookiesFromResponse(iframeResp.headers)
                // se iframe está indisponível, tenta próximo (ex: twddd 01x01 nv32)
                if (iframeHtml.contains("Conteúdo Indisponível", ignoreCase = true) || iframeHtml.contains("indisponível", ignoreCase = true)) {
                    continue
                }
                if (iframeHtml.contains("captcha", ignoreCase = true) || iframeHtml.contains("Verificação Humana")) {
                    val solved = handleCaptcha(iframeUrl, iframeHtml, data, iframeCookies, callback)
                    if (solved) found = true
                    continue
                }
                val videoSources = videoSourceRegex.findAll(iframeHtml).map { fixUrl(it.groupValues[1].replace("&amp;", "&")) }.toList()
                val candidates = if (videoSources.isNotEmpty()) videoSources else listOf(iframeUrl)
                for (videoUrl in candidates) {
                    val label = extractLabel(iframeHtml, videoUrl) ?: "NetCine"
                    val finalResp = app.get(videoUrl, headers = BROWSER_HEADERS + mapOf("Referer" to iframeUrl))
                    val finalHtml = finalResp.text
                    val finalCookies = mergeCookies(iframeCookies, extractCookiesFromResponse(finalResp.headers))
                    if (finalHtml.contains("captcha", ignoreCase = true) || finalHtml.contains("Verificação Humana")) {
                        val solvedInner = handleCaptcha(videoUrl, finalHtml, iframeUrl, finalCookies, callback)
                        if (solvedInner) found = true
                        continue
                    }
                    // 1. Extração de <source src="..."> (ex: vid.php retornando .mp4 do BunnyCDN)
                    val sourceTag = Regex("""<source[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(finalHtml)?.groupValues?.getOrNull(1)?.let { fixUrl(it.replace("&amp;", "&")) }
                    if (sourceTag != null) {
                        val isM3u8 = sourceTag.contains(".m3u8")
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = label,
                                url = sourceTag,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = videoUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                        continue
                    }

                    // 2. Extração de link de mídia direto (m3u8 ou mp4) no HTML
                    val directMedia = Regex("""https?://[^"'\s<>]+\.(?:m3u8|mp4)[^"'\s<>]*""").find(finalHtml)?.value
                    if (directMedia != null) {
                        val isM3u8 = directMedia.contains(".m3u8")
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = label,
                                url = directMedia,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = videoUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                        continue
                    }

                    // 3. Se for link direto de mídia
                    if (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4")) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = label,
                                url = videoUrl,
                                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = iframeUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                        continue
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                continue
            }
        }
        return found
    }

    private fun getAudioLabel(vararg sources: String?): String {
        for (s in sources) {
            if (s.isNullOrBlank()) continue
            if (s.contains("DUB", ignoreCase = true) || s.contains("dublado", ignoreCase = true)) return "Dublado"
            if (s.contains("LEG", ignoreCase = true) || s.contains("legendado", ignoreCase = true)) return "Legendado"
            if (s.contains("NAC", ignoreCase = true) || s.contains("nacional", ignoreCase = true)) return "Nacional"
        }
        return ""
    }

    private fun extractLabel(html: String, videoUrl: String): String? {
        val audio = getAudioLabel(videoUrl, html)
        if (audio.isNotBlank()) return "NetCine ($audio)"
        val playLabel = Regex("""play-\d+[^>]*>.*?([A-Z]{2,})""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.getOrNull(1)
        if (playLabel != null) return "NetCine ($playLabel)"
        val pParam = Regex("""[?&]p=([^&"']+)""").find(videoUrl)?.groupValues?.getOrNull(1)
        return pParam?.let { "NetCine ($it)" }
    }

    private fun extractCookiesFromResponse(headers: okhttp3.Headers): String {
        return try {
            headers.values("Set-Cookie")
                .map { it.substringBefore(";").trim() }
                .filter { it.isNotBlank() }
                .joinToString("; ")
        } catch (_: Exception) { "" }
    }

    private fun mergeCookies(oldCookie: String, newCookie: String): String {
        if (oldCookie.isBlank()) return newCookie
        if (newCookie.isBlank()) return oldCookie
        val map = mutableMapOf<String, String>()
        (oldCookie.split(";") + newCookie.split(";")).forEach { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) {
                map[kv[0].trim()] = kv[1].trim()
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * Captcha 100% dentro do app (sem tesseract nativo).
     * Fluxo cs3: GET imagem ?captcha_img=1 -> POST ocr.space (helloworld) -> POST captcha_input -> extrai m3u8.
     * Funciona no CloudStream pois usa apenas NiceHttp (app.get/app.post) e Base64 do Android.
     */
    private suspend fun handleCaptcha(
        captchaPageUrl: String,
        captchaHtml: String,
        referer: String,
        passedCookies: String = "",
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var curUrl = captchaPageUrl
        var curHtml = captchaHtml
        var sessionCookies = passedCookies
        val androidCookie = try {
            CookieManager.getInstance().getCookie(curUrl) ?: ""
        } catch (_: Exception) { "" }
        if (androidCookie.isNotBlank()) {
            sessionCookies = mergeCookies(sessionCookies, androidCookie)
        }
        val basePostData = mutableMapOf<String, String>()
        val initialDoc = Jsoup.parse(captchaHtml)
        initialDoc.select("form input[type=hidden]").forEach { inp ->
            val n = inp.attr("name"); val v = inp.attr("value")
            if (n.isNotBlank()) basePostData[n] = v
        }
        val baseAction = initialDoc.selectFirst("form")?.attr("action")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: captchaPageUrl

        repeat(3) { attempt ->
        try {
            // 1. Acha a URL da imagem (usa curHtml/curUrl para retry)
            val imgSrcMatch = Regex("""src\s*=\s*["']([^"']*\?captcha_img=1[^"']*)["']""", RegexOption.IGNORE_CASE).find(curHtml)
            val imgSrcRaw = imgSrcMatch?.groupValues?.getOrNull(1) ?: "?captcha_img=1"
            val captchaImgUrl = when {
                imgSrcRaw.startsWith("http") -> imgSrcRaw
                imgSrcRaw.startsWith("//") -> "https:$imgSrcRaw"
                imgSrcRaw.startsWith("/") -> {
                    val base = curUrl.substringBefore("/", ).let { 
                        Regex("""https?://[^/]+""").find(curUrl)?.value ?: mainUrl
                    }
                    if (imgSrcRaw.startsWith("?")) curUrl.substringBefore("?") + imgSrcRaw
                    else base + imgSrcRaw
                }
                imgSrcRaw.startsWith("?") -> curUrl.substringBefore("?") + imgSrcRaw
                else -> fixUrl(imgSrcRaw)
            }
            val androidCookie = try {
                CookieManager.getInstance().getCookie(curUrl) ?: ""
            } catch (_: Exception) { "" }
            if (androidCookie.isNotBlank()) {
                sessionCookies = mergeCookies(sessionCookies, androidCookie)
            }

            val sessionHeaders = BROWSER_HEADERS + mapOf("Referer" to curUrl) +
                if (sessionCookies.isNotBlank()) mapOf("Cookie" to sessionCookies) else emptyMap()

            // 2. Baixa imagem com validação PNG e sessão compartilhada
            val imgResp = try {
                app.get(
                    captchaImgUrl,
                    headers = sessionHeaders + mapOf("Accept" to "image/png,*/*;q=0.8", "Accept-Language" to "pt-BR,pt;q=0.9")
                )
            } catch (e: Exception) {
                println("NetCine: falha ao baixar captcha ${e.message}")
                return@repeat
            }
            val imgSetCookie = extractCookiesFromResponse(imgResp.headers)
            if (imgSetCookie.isNotBlank()) {
                sessionCookies = mergeCookies(sessionCookies, imgSetCookie)
            }
            var imageBytes = imgResp.body.bytes().also { println("NetCine: captcha PNG baixado ${it.size} bytes header ${it.take(4).joinToString { "%02X".format(it) }}") }
            // valida PNG magic 89 50 4E 47, se falhar tenta sem BROWSER_HEADERS (apenas Referer) para evitar CF
            val isPng = imageBytes.size >= 100 && imageBytes[0] == 0x89.toByte() && imageBytes[1] == 0x50.toByte() && imageBytes[2] == 0x4E.toByte() && imageBytes[3] == 0x47.toByte()
            if (!isPng) {
                val headerHex = imageBytes.take(4).joinToString(", ") {"%02X".format(it)}
                val preview = String(imageBytes.take(600).toByteArray()).replace("\n"," ").take(600)
                println("NetCine: imagem não é PNG válido (${imageBytes.size} bytes, header $headerHex) preview ${preview.take(300)}, tentando fallback")
                try {
                    val fb = app.get(captchaImgUrl, headers = mapOf("Referer" to curUrl)).body.bytes()
                    if (fb.isNotEmpty() && fb.size >= 100 && fb[0] == 0x89.toByte() && fb[1] == 0x50.toByte()) {
                        imageBytes = fb
                        println("NetCine: fallback PNG OK ${fb.size} bytes")
                    } else {
                        println("NetCine: fallback também não é PNG ${fb.size}")
                        return@repeat
                    }
                } catch (e: Exception) {
                    println("NetCine: fallback falhou ${e.message}")
                    return@repeat
                }
            }

            // 3. OCR via ocr.space (funciona dentro do cs3 sem binário nativo)
            val solved = solveCaptchaWithOcr(imageBytes) ?: run {
                // fallback: tenta tesseract local se existir (raro)
                runTesseractOcr(imageBytes)
            }
            if (solved.isNullOrBlank() || solved.length !in 4..8) {
                println("NetCine: OCR falhou ou texto inválido: $solved")
                return@repeat
            }
            // NÃO fazer uppercase: captcha é case-sensitive (testes: yavtc OK, YAVTC falha)
            val clean = solved.replace(Regex("[^A-Za-z0-9]"), "").trim().take(6)
            if (clean.length < 4) return@repeat
            println("NetCine: Captcha OCR -> $clean (raw $solved) tentativa ${attempt+1}")

            // 4. Extrai campos hidden do form preservando basePostData original
            val doc = Jsoup.parse(curHtml)
            val form = doc.selectFirst("form") 
            val action = form?.attr("action")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: baseAction
            val postData = basePostData.toMutableMap()
            // preserva todos hidden do HTML atual se houver
            doc.select("form input[type=hidden]").forEach { inp ->
                val n = inp.attr("name"); val v = inp.attr("value")
                if (n.isNotBlank()) postData[n] = v
            }
            // campo principal do captcha (varia: captcha_input, captcha, code)
            val captchaField = doc.selectFirst("input[name*=captcha]")?.attr("name")?.takeIf { it.isNotBlank() } ?: "captcha_input"
            postData[captchaField] = clean
            // alguns forms exigem submit
            val submit = doc.selectFirst("input[type=submit]")?.let { it.attr("name") to it.attr("value") }
            if (submit != null && submit.first.isNotBlank()) postData[submit.first] = submit.second

            val postReqHeaders = BROWSER_HEADERS + mapOf("Referer" to curUrl) +
                if (sessionCookies.isNotBlank()) mapOf("Cookie" to sessionCookies) else emptyMap()

            val postResp = app.post(action, data = postData, headers = postReqHeaders)
            val postSetCookie = extractCookiesFromResponse(postResp.headers)
            if (postSetCookie.isNotBlank()) {
                sessionCookies = mergeCookies(sessionCookies, postSetCookie)
            }
            val postHtml = postResp.text

            // 5. Verifica se passou - retry se ainda é pagina Verificacao (captcha incorreto)
            val isStillCaptcha = (postHtml.contains("captcha", ignoreCase = true) && postHtml.contains("Verificação Humana"))
                || postHtml.contains("<title>Verificação")
                || postHtml.contains("captcha_input")
            if (isStillCaptcha) {
                println("NetCine: Captcha tentativa ${attempt+1} falhou (Código incorreto/Verificação), tentando novamente com nova imagem")
                curHtml = postHtml
                curUrl = action
                if (attempt == 2) return false
                else return@repeat
            }

            // 5a. Determina nome da fonte amigável (Dublado / Legendado)
            val sourceTag = Regex("""<source[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(postHtml)?.groupValues?.getOrNull(1)?.let { fixUrl(it.replace("&amp;", "&")) }
            val audioType = getAudioLabel(sourceTag, captchaPageUrl, action, postHtml, referer)
            val linkName = if (audioType.isNotBlank()) "NetCine ($audioType)" else "NetCine (HD)"

            if (sourceTag != null) {
                println("NetCine: Captcha OK -> source tag $sourceTag ($linkName)")
                callback.invoke(
                    newExtractorLink(source = name, name = linkName, url = sourceTag, type = ExtractorLinkType.M3U8) {
                        this.referer = action
                        this.headers = if (sessionCookies.isNotBlank()) mapOf("Cookie" to sessionCookies) else emptyMap()
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
            // tenta extrair m3u8 direto (alguns mirrors retornam direto)
            val m3u8Direct = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(postHtml)?.value
            if (m3u8Direct != null) {
                callback.invoke(
                    newExtractorLink(source = name, name = linkName, url = m3u8Direct, type = ExtractorLinkType.M3U8) {
                        this.referer = action
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
            val videoHref = videoSourceRegex.find(postHtml)?.groupValues?.getOrNull(1)?.let { fixUrl(it) }
            if (videoHref != null) {
                // segue o href para pegar m3u8 final
                val finalResp = app.get(videoHref, headers = BROWSER_HEADERS + mapOf("Referer" to action))
                val finalHtml = finalResp.text
                val m3u8Final = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(finalHtml)?.value ?: videoHref
                val finalAudio = getAudioLabel(videoHref, m3u8Final, audioType)
                val finalName = if (finalAudio.isNotBlank()) "NetCine ($finalAudio)" else linkName
                callback.invoke(
                    newExtractorLink(source = name, name = finalName, url = m3u8Final, type = ExtractorLinkType.M3U8) {
                        this.referer = videoHref
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
            // se POST retornou html sem m3u8 mas com iframe novo
            val newIframe = Regex("""<iframe[^>]+src="([^"]+)"""").find(postHtml)?.groupValues?.getOrNull(1)?.let { fixUrl(it) }
            if (newIframe != null) {
                val iframeResp = app.get(newIframe, headers = BROWSER_HEADERS + mapOf("Referer" to action))
                val iframeHtml2 = iframeResp.text
                val m3u8i = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(iframeHtml2)?.value
                if (m3u8i != null) {
                    callback.invoke(
                        newExtractorLink(source = name, name = linkName, url = m3u8i, type = ExtractorLinkType.M3U8) {
                            this.referer = newIframe
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }
            // fallback também tenta <source> no newIframe e no postHtml novamente
            Regex("""<source[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(postHtml)?.groupValues?.getOrNull(1)?.let { fallbackSource ->
                callback.invoke(newExtractorLink(source = name, name = linkName, url = fixUrl(fallbackSource.replace("&amp;", "&")), type = ExtractorLinkType.M3U8) {
                    this.referer = action; this.quality = Qualities.Unknown.value
                })
                return true
            }
            println("NetCine: POST captcha OK mas nenhum m3u8 encontrado em ${postHtml.take(2000)} url $action curUrl $curUrl")
            // bypass direto: tenta extrair m3u8 do html sem captcha (alguns mirrors liberam com mesmo apr+t)
            Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(postHtml)?.value?.let { fallbackM3u8 ->
                callback.invoke(newExtractorLink(source = name, name = linkName, url = fallbackM3u8, type = ExtractorLinkType.M3U8) {
                    this.referer = curUrl; this.quality = Qualities.Unknown.value
                })
                return true
            }
            return false
        } catch (e: Exception) {
            println("NetCine: handleCaptcha erro ${e.message} tentativa ${attempt+1}")
            e.printStackTrace()
            if (attempt == 2) return false else return@repeat
        }
        } // repeat

        // Fallback final: se HTTP direto falhou/bloqueou (ex: HTTP 403), tenta WebViewResolver nativo do CloudStream
        try {
            println("NetCine: tentando fallback com WebViewResolver para $captchaPageUrl")
            val wvResp = app.get(
                captchaPageUrl,
                headers = BROWSER_HEADERS + mapOf("Referer" to referer),
                interceptor = WebViewResolver(Regex("""https?://.*\.m3u8.*|https?://.*token=.*"""))
            )
            val wvHtml = wvResp.text
            val m3u8Wv = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(wvHtml)?.value
                ?: Regex("""<source[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(wvHtml)?.groupValues?.getOrNull(1)?.let { fixUrl(it.replace("&amp;", "&")) }
                ?: wvResp.url.takeIf { it.contains(".m3u8") || it.contains("token=") }

            if (m3u8Wv != null) {
                val wvAudio = getAudioLabel(m3u8Wv, captchaPageUrl)
                val wvName = if (wvAudio.isNotBlank()) "NetCine ($wvAudio)" else "NetCine (WebView)"
                println("NetCine: WebViewResolver obteve stream $m3u8Wv")
                callback.invoke(
                    newExtractorLink(source = name, name = wvName, url = m3u8Wv, type = ExtractorLinkType.M3U8) {
                        this.referer = captchaPageUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: Exception) {
            println("NetCine: WebViewResolver falhou ${e.message}")
        }

        return false
    }

    private val ocrBlockedMap = mutableMapOf<String, Long>()
    private suspend fun solveCaptchaWithOcr(imageBytes: ByteArray): String? {
        // limpa chaves expiradas
        val now = System.currentTimeMillis()
        ocrBlockedMap.entries.removeIf { it.value <= now }
        if (ocrBlockedMap.size >= 2) {
            val next = ocrBlockedMap.values.minOrNull() ?: now
            val wait = (next - now) / 1000
            println("NetCine OCR todas keys bloqueadas faltam ${wait}s")
            return null
        }
        println("NetCine OCR tentando keys livres ${ocrBlockedMap}")
        val keys = listOf("K81867795788957", "K87899042388957", "helloworld")
        val engines = listOf("2", "1")
        for (apiKey in keys) {
            if (ocrBlockedMap.containsKey(apiKey) && ocrBlockedMap[apiKey]!! > System.currentTimeMillis()) continue
            for (engine in engines) {
            try {
                val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                val base64Image = java.net.URLEncoder.encode("data:image/png;base64,$base64", "UTF-8")
                val data = mapOf(
                    "apikey" to apiKey,
                    "base64Image" to base64Image,
                    "language" to "eng",
                    "isOverlayRequired" to "false",
                    "OCREngine" to engine,
                    "scale" to "true"
                )
                val resp = app.post(
                    "https://api.ocr.space/parse/image",
                    data = data,
                    headers = mapOf("apikey" to apiKey)
                )
                val txt = resp.text
                if (txt.contains("E502")) {
                    println("NetCine OCR $apiKey E502 Corrupted PNG ${txt.take(200)} base64Len ${base64.length}")
                    continue
                }
                if (txt.contains("E553") || txt.contains("Rate limit")) {
                    val retry = Regex("""retryAfter.*?(\d+)""").find(txt)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 300
                    val waitMs = (retry + 10) * 1000
                    ocrBlockedMap[apiKey] = System.currentTimeMillis() + waitMs
                    println("NetCine OCR $apiKey E553 retryAfter ${retry}s -> bloqueado ${waitMs/1000}s")
                    continue
                }
                val parsed = Regex(""""ParsedText"\s*:\s*"([^"]+)"""").find(txt)?.groupValues?.getOrNull(1)
                    ?.replace(Regex("""\\r|\\n"""), "")?.trim()
                if (!parsed.isNullOrBlank()) {
                    val clean = parsed.replace(Regex("[^A-Za-z0-9]"), "").trim().take(6)
                    if (clean.length in 4..6) {
                        println("NetCine OCR engine $engine -> $clean")
                        return clean
                    }
                }
                println("NetCine OCR $apiKey engine $engine raw: ${txt.take(400)}")
            } catch (e: Exception) {
                println("NetCine OCR $apiKey engine $engine erro ${e.message}")
            }
            }
        }
        // fallback: tenta sem scale e sem engine com ambas keys
        for (apiKey in keys) {
            if (ocrBlockedMap.containsKey(apiKey) && ocrBlockedMap[apiKey]!! > System.currentTimeMillis()) continue
            try {
            val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val base64Image = java.net.URLEncoder.encode("data:image/png;base64,$base64", "UTF-8")
            val resp = app.post(
                "https://api.ocr.space/parse/image",
                data = mapOf("apikey" to apiKey, "base64Image" to base64Image, "language" to "eng"),
                headers = mapOf("apikey" to apiKey)
            )
            val txt = resp.text
            val parsed = Regex(""""ParsedText"\s*:\s*"([^"]+)"""").find(txt)?.groupValues?.getOrNull(1)?.replace(Regex("""\\r|\\n"""), "")?.trim()
            val clean = parsed?.replace(Regex("[^A-Za-z0-9]"), "")?.trim()?.takeIf { it.length in 4..6 }
            if (!clean.isNullOrBlank()) return clean
            } catch (e: Exception) {
                println("NetCine OCR fallback $apiKey erro ${e.message}")
            }
        }
        return null
    }

    @Suppress("unused")
    private fun runTesseractOcr(imageBytes: ByteArray): String? {
        return try {
            val temp = createTempFile(suffix = ".png")
            temp.writeBytes(imageBytes)
            val proc = Runtime.getRuntime().exec(arrayOf("tesseract", temp.absolutePath, "stdout", "--psm", "8", "-c", "tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))
            val out = proc.inputStream.bufferedReader().readText().trim().takeIf { it.length in 4..8 }
            temp.delete()
            out
        } catch (_: Exception) {
            null
        }
    }
}
