package com.RedeCanaisAF

import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class RedeCanaisAF : MainAPI() {
    override var mainUrl = "https://redecanais.af"
    override var name = "RedeCanais (AF)"
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

    init {
        Log.i(TAG, "[PLUGIN_VERSION] v=$BUILD_VERSION")
    }

    companion object {
        const val BUILD_VERSION = 147
        private const val TAG = "RedeCanaisAF-Trace"

        // v112: cache maior (30min) ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â a 1Ãƒâ€šÃ‚Âª carga custa (WebView), depois ÃƒÆ’Ã‚Â© instantÃƒÆ’Ã‚Â¢nea
        private const val RESPONSE_CACHE_TTL_MS = 30 * 60 * 1000L
        private val homeCache = ConcurrentHashMap<String, Pair<Long, HomePageResponse>>()
        private val loadCache = ConcurrentHashMap<String, Pair<Long, LoadResponse>>()

        private val PLACEHOLDER_PATTERNS = listOf(
            "echo-lzld",
            "blank.gif",
            "pixel.gif",
            "no-thumbnail",
            "default-thumbnail",
            "lazy.png",
            "1x1",
            "data:image/gif;base64,R0lGOD"
        )

        private val SERIES_URL_KEYWORDS = listOf(
            "lista-de-episodios", "todas-as-temporadas", "temporada", "temporadas",
            "serie", "series", "animes", "anime", "desenhos", "desenho",
            "episodio", "episodios", "completo-dublado", "temp"
        )

        private val SERIES_TITLE_KEYWORDS = listOf(
            "Temporada", "Temp", "Episódio", "Episodio", "Ep.", "Ep ",
            "Completo Dublado", "Lista de Episódios", "Todas as Temporadas",
            "1ª", "2ª", "3ª", "4ª", "5ª", "6ª", "7ª", "8ª", "9ª"
        )
    }

    private val htmlGate = HtmlGate(mainUrl)

    /**
     * Headers customizados do Coil para carregar imagens e capas protegidas pelo Cloudflare.
     */
    private fun posterHeaders(): Map<String, String> {
        val cookies = runCatching {
            CookieManager.getInstance().getCookie(mainUrl)
        }.getOrNull().orEmpty()

        val userAgent = CloudflareSolver.lastUserAgent
            ?: WebViewResolver.webViewUserAgent
            ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

        return buildMap {
            put("Referer", "$mainUrl/")
            put("User-Agent", userAgent)
            put("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            if (cookies.isNotBlank()) put("Cookie", cookies)
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/browse-filmes-lancamentos-videos" to "Filmes Lançamentos",
        "$mainUrl/browse-series-videos" to "Séries Atualizadas",
        "$mainUrl/browse-animes-videos" to "Animes",
        "$mainUrl/browse-desenhos-videos" to "Desenhos & Cartoons"
    )

    private suspend fun requestDoc(url: String, referer: String = "$mainUrl/"): Document {
        return htmlGate.fetch(url, referer)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseSlug = request.data
        val url = "$baseSlug-$page-date.html"
        val now = android.os.SystemClock.elapsedRealtime()
        // v146: cold start — restaura HTML do disco (30min) antes de bater na rede
        runCatching { CloudflareSolver.restoreDiskCacheIfNeeded() }
        homeCache[url]?.takeIf { now - it.first < RESPONSE_CACHE_TTL_MS }?.second?.let {
            Log.i(TAG, "[HOME_CACHE_HIT] Cat=${request.name} url=$url")
            return it
        }

        Log.i(TAG, "[MAINPAGE_ENTER] Cat=${request.name} url=$url")

        // v130: registra as 4 URLs do catálogo — o primeiro REQ a resolver o Turnstile navega o
        // MESMO WebView do diálogo (sessão TLS compartilhada) por todas, capturando cada uma no
        // cache. Os REQs seguintes retornam do cache ~0ms sem reabrir diálogo.
        val catalogSlugs = listOf(
            "$mainUrl/browse-filmes-lancamentos-videos",
            "$mainUrl/browse-series-videos",
            "$mainUrl/browse-animes-videos",
            "$mainUrl/browse-desenhos-videos"
        )
        CloudflareSolver.setCatalogUrls(catalogSlugs.map { "$it-$page-date.html" })

        val doc = try {
            requestDoc(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            if ("IP_BANNED_1006" in msg) {
                Log.e(TAG, "[MAINPAGE_BANNED] Cat=${request.name} url=$url — IP banido (1006)")
                // Mostra mensagem visível ao usuário na home (instead of lista vazia silenciosa)
                // Reutiliza resposta vazia mas loga explicação clara no logcat.
            } else {
                Log.w(TAG, "[MAINPAGE_FAIL] Cat=${request.name} url=$url err=${e.message}")
            }
            return newHomePageResponse(listOf(HomePageList(request.name, emptyList())), hasNext = false)
        }

        val htmlLen = doc.html().length
        val elements = doc.select("div.pm-video-thumb, li.pm-video-thumb, .pm-video-thumb, .pm-li-video, .video-thumb, article, div[class*='video-thumb']")
        val homeList = elements.mapNotNull { parseCard(it) }.distinctBy { it.url }

        Log.i(TAG, "[PARSER_DONE] Cat=${request.name} | htmlLen=$htmlLen | rawCards=${elements.size} | validCards=${homeList.size}")

        // v114: diagnÃƒÂ³stico de capas Ã¢â‚¬â€ quantas vieram embutidas (data-cs-poster) vs URL HTTP
        try {
            val embedded = elements.count { it.selectFirst("img[data-cs-poster]") != null }
            val httpOnly = elements.count {
                val img = it.selectFirst("img")
                img != null && img.attr("data-cs-poster").isBlank() && img.attr("data-echo").isNotBlank()
            }
            val noPoster = elements.size - embedded - httpOnly
            Log.i(TAG, "[CAPAS_DIAG] Cat=${request.name} | cards=${elements.size} | EMBUTIDAS=$embedded | HTTP_SEM_EMBUTIR=$httpOnly | SEM_IMAGEM=$noPoster")
        } catch (e: Throwable) {
            Log.e(TAG, "[CAPAS_DIAG_ERROR] ${e.javaClass.simpleName}: ${e.message}")
        }

        val hasNext = doc.select(".pagination a[rel='next'], .pagination a.next, a:contains(Próximo), a:contains(»)").isNotEmpty() || homeList.size >= 12
        val response = newHomePageResponse(
            listOf(HomePageList(request.name, homeList)),
            hasNext = hasNext
        )

        Log.i(TAG, "[HOME_RETURN] Cat=${request.name} | sections=1 | totalItems=${homeList.size} | hasNext=$hasNext")
        homeCache[url] = android.os.SystemClock.elapsedRealtime() to response
        return response
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/search.php?keywords=$encoded"

        return try {
            val doc = requestDoc(searchUrl)
            val elements = doc.select("div.pm-video-thumb, li.pm-video-thumb, .pm-video-thumb, .pm-li-video, .video-thumb, article, div[class*='video-thumb']")
            val results = elements.mapNotNull { parseCard(it) }.distinctBy { it.url }
            Log.i(TAG, "[SEARCH_SUCCESS] query='$query' | results=${results.size}")
            results
        } catch (e: Throwable) {
            Log.e(TAG, "[SEARCH_ERROR] query='$query' | err=${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val now = android.os.SystemClock.elapsedRealtime()
        loadCache[url]?.takeIf { now - it.first < RESPONSE_CACHE_TTL_MS }?.second?.let {
            Log.i(TAG, "[LOAD_CACHE_HIT] url=$url")
            return it
        }
        val doc = requestDoc(url)

        val rawTitle = doc.selectFirst("h1.entry-title, h1.pm-video-attr-title, h1, .pm-video-title")?.text()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "RedeCanais AF"

        val title = RedeCanaisAFText.cleanMediaTitle(rawTitle)

        // 1. ExtraÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o de Imagens (Poster e Backdrop)
        val (posterUrl, backdropUrl) = extractDetailImages(doc)

        // 2. ExtraÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o de Metadados
        val plot = extractPlot(doc)
        val year = extractYear(rawTitle, doc)
        val duration = extractDuration(doc)

        val tags = doc.select(".pm-video-attr-categories a, .categories a, a[href*='browse-']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val hasEpisodeElements = doc.select(
            ".pm-video-description a[href*='.html'], select option[value*='.html'], .episodios, .pm-video-episodes, a[href*='episodio']"
        ).isNotEmpty()

        val isSeries = isSeriesUrlOrTitle(url, rawTitle) || hasEpisodeElements
        val tvType = determineTvType(url, tags, isSeries)

        Log.i(TAG, "[LOAD_INFO] title='$title' type=$tvType isSeries=$isSeries year=$year dur=$duration poster='$posterUrl'")

        if (isSeries) {
            val episodes = parseEpisodes(doc, rawTitle, url)
            if (episodes.isNotEmpty()) {
                val response = newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backdropUrl ?: posterUrl
                    this.posterHeaders = posterHeaders()
                    this.plot = plot
                    this.tags = tags
                    this.year = year
                    this.duration = duration
                }
                loadCache[url] = android.os.SystemClock.elapsedRealtime() to response
                return response
            }
        }

        val response = newMovieLoadResponse(title, url, tvType, url) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backdropUrl ?: posterUrl
            this.posterHeaders = posterHeaders()
            this.plot = plot
            this.tags = tags
            this.year = year
            this.duration = duration
        }
        loadCache[url] = android.os.SystemClock.elapsedRealtime() to response
        return response
    }

    /**
     * ExtraÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o de imagens da pÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡gina de detalhes com prioridade estrita:
     * og:image -> twitter:image -> link image_src -> img[data-echo] -> img[src] -> .pm-video-thumb img
     */
    private fun extractDetailImages(doc: Document): Pair<String?, String?> {
        val candidates = mutableListOf<String>()

        // v109: capa embutida pelo WebView (data URL) tem prioridade mÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡xima ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â Coil exibe sem rede
        doc.selectFirst("img[data-cs-poster]")?.attr("data-cs-poster")?.let { candidates.add(it) }
        doc.selectFirst("meta[property='og:image'][data-cs-poster]")?.attr("data-cs-poster")?.let { candidates.add(it) }

        // 1. Meta tags OpenGraph / Twitter
        doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { candidates.add(it) }
        doc.selectFirst("meta[name='twitter:image']")?.attr("content")?.let { candidates.add(it) }
        doc.selectFirst("link[rel='image_src']")?.attr("href")?.let { candidates.add(it) }

        // 2. Imagens reais do PHP Melody
        doc.selectFirst("img[data-echo*='/imgs-videos/']")?.attr("data-echo")?.let { candidates.add(it) }
        doc.selectFirst("img[src*='/imgs-videos/']")?.let { img ->
            val src = img.attr("data-echo").ifBlank { img.attr("data-src") }.ifBlank { img.attr("src") }
            candidates.add(src)
        }

        // 3. Fallbacks de imagem no container de vÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­deo
        doc.selectFirst(".pm-video-watch-wrap img, .pm-video-thumb img, article img, .pm-video-img img, .poster img, img[itemprop='thumbnailUrl']")?.let { img ->
            val src = img.attr("data-echo")
                .ifBlank { img.attr("data-src") }
                .ifBlank { img.attr("src") }
            candidates.add(src)
        }

        val validImages = candidates
            .map { optimizePosterUrl(it) }
            // v109: data: URLs (capas embutidas do WebView) sÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o aceitas; placeholders nÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o
            .filter { it.isNotBlank() && !isPlaceholderImage(it) && !it.startsWith("data:image/gif;base64,R0lGOD", true) }
            .distinct()

        val poster = validImages.firstOrNull()
        val backdrop = validImages.getOrNull(1) ?: poster

        return Pair(poster, backdrop)
    }

    private fun isSeriesUrlOrTitle(url: String, title: String): Boolean {
        val urlLower = url.lowercase()
        if (SERIES_URL_KEYWORDS.any { urlLower.contains(it) }) return true
        return SERIES_TITLE_KEYWORDS.any { title.contains(it, ignoreCase = true) }
    }

    private fun determineTvType(url: String, tags: List<String> = emptyList(), isSeries: Boolean): TvType {
        val text = "$url ${tags.joinToString(" ")}".lowercase()
        return when {
            text.contains("anime") -> if (isSeries) TvType.Anime else TvType.AnimeMovie
            text.contains("desenho") || text.contains("cartoon") -> if (isSeries) TvType.Cartoon else TvType.Movie
            text.contains("drama") || text.contains("dorama") -> if (isSeries) TvType.AsianDrama else TvType.Movie
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun extractPlot(doc: Document): String? {
        val metaDesc = doc.selectFirst("meta[property='og:description'], meta[name='description']")?.attr("content")?.trim()
        if (!metaDesc.isNullOrBlank() && metaDesc.length > 30 && !RedeCanaisAFText.isJunkText(metaDesc)) {
            return cleanPlotText(metaDesc)
        }

        val descContainer = doc.selectFirst(".pm-video-description, #pm-video-description, .description, .sinopse")
        if (descContainer != null) {
            val clone = descContainer.clone()
            clone.select("a, script, style, select, button, form, iframe, .episodios, .pm-video-episodes, .pm-ads").remove()

            val paragraphs = clone.select("p").map { it.text().trim() }.filter { it.length > 25 && !RedeCanaisAFText.isJunkText(it) }
            if (paragraphs.isNotEmpty()) {
                return cleanPlotText(paragraphs.joinToString("\n\n"))
            }

            val directText = clone.ownText().trim()
            if (directText.length > 25 && !RedeCanaisAFText.isJunkText(directText)) {
                return cleanPlotText(directText)
            }

            val allText = clone.text().trim()
            if (allText.length > 25 && !RedeCanaisAFText.isJunkText(allText)) {
                return cleanPlotText(allText)
            }
        }

        return null
    }

    private fun isJunkText(text: String): Boolean {
        val lower = text.lowercase()
        val junk = listOf(
            "caso o vÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­deo", "se o vÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­deo nÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o", "problema para assistir",
            "redecanais", "rede canais", "todos os direitos reservados",
            "reportar erro", "clique aqui", "navegador recomendado",
            "baixe o app", "grupo telegram", "compartilhe com seus amigos",
            "lista de episÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³dios", "todas as temporadas"
        )
        return junk.any { lower.contains(it) } && text.length < 150
    }

    private fun cleanPlotText(text: String): String {
        return text
            .replace(Regex("""(?i)^\s*Sinopse\s*:\s*"""), "")
            .replace(Regex("""(?i)\s*Rede\s*Canais.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun extractYear(rawTitle: String, doc: Document): Int? {
        val fromTitle = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)?.value?.toIntOrNull()
        if (fromTitle != null) return fromTitle

        val fromMeta = doc.selectFirst(".pm-video-attr-since, .year, .date, .pm-video-attr-description, time")?.text()?.let {
            Regex("""\b(19\d{2}|20\d{2})\b""").find(it)?.value?.toIntOrNull()
        }
        if (fromMeta != null) return fromMeta

        return doc.selectFirst("meta[property='video:release_date'], meta[property='og:release_date'], meta[itemprop='datePublished']")?.attr("content")?.let {
            Regex("""\b(19\d{2}|20\d{2})\b""").find(it)?.value?.toIntOrNull()
        }
    }

    private fun extractDuration(doc: Document): Int? {
        val durText = doc.selectFirst(".pm-video-attr-duration, .duration, span[itemprop='duration'], meta[itemprop='duration']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }.orEmpty()

        if (durText.isNotBlank()) {
            val isoH = Regex("""(?i)(\d+)H""").find(durText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val isoM = Regex("""(?i)(\d+)M""").find(durText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            if (isoH > 0 || isoM > 0) return isoH * 60 + isoM

            val hours = Regex("""(?i)(\d+)\s*(?:h|hora|horas)""").find(durText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val minutes = Regex("""(?i)(\d+)\s*(?:min|m|minuto|minutos)""").find(durText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            if (hours > 0 || minutes > 0) return hours * 60 + minutes

            val plainMin = Regex("""\b(\d{2,3})\b""").find(durText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (plainMin != null && plainMin in 1..600) return plainMin
        }
        return null
    }

    private fun parseCard(element: Element): SearchResponse? {
        val aTag = element.selectFirst("a[href*='_']:not([href*='#']), a[href*='.html']:not([href*='#']), a:not([href*='#']):not([class*='watch-later'])")
            ?: (if (element.tagName() == "a") element else null)
            ?: return null

        val link = aTag.attr("href").ifBlank { return null }
        if (link.contains("browse-") || link.contains("category") || link == "#") {
            return null
        }
        val fullUrl = fixUrl(link)

        var rawTitle = ""
        val titleEl = element.selectFirst("h3 a, h3, h4, .pm-video-attr-title a, .pm-video-attr-title, .title, a[title]")
        if (titleEl != null) {
            rawTitle = titleEl.text().ifBlank { titleEl.attr("title") }
        }
        if (rawTitle.isBlank()) {
            rawTitle = aTag.attr("title")
        }
        if (rawTitle.isBlank()) {
            rawTitle = element.selectFirst("img")?.attr("alt").orEmpty()
        }

        val cleanTitle = RedeCanaisAFText.cleanMediaTitle(rawTitle)
        if (cleanTitle.isBlank()) return null

        val rawPoster = extractPosterFromCardElement(element)
        val poster = optimizePosterUrl(rawPoster)

        val isSeries = isSeriesUrlOrTitle(fullUrl, rawTitle)
        val tvType = determineTvType(fullUrl, emptyList(), isSeries)

        val qualityText = element.selectFirst(".pm-label-hd, .label-hd, .quality")?.text().orEmpty()
        val quality = if (qualityText.contains("4K", ignoreCase = true)) SearchQuality.UHD else SearchQuality.HD

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanTitle, fullUrl, tvType) {
                this.posterUrl = poster
                this.posterHeaders = posterHeaders()
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(cleanTitle, fullUrl, tvType) {
                this.posterUrl = poster
                this.posterHeaders = posterHeaders()
                this.quality = quality
            }
        }
    }

    private fun extractPosterFromCardElement(element: Element): String {
        val img = element.selectFirst("img") ?: return ""

        // v109: data-cs-poster = capa embutida pelo WebView (data URL) ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â o Coil exibe sem rede.
        // SÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³ usa se NÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢O for placeholder e NÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢O for o src placeholder do lazy-load.
        val csPoster = img.attr("data-cs-poster").trim()
        if (csPoster.isNotBlank() && !isPlaceholderImage(csPoster) && csPoster != img.attr("src")) {
            return csPoster
        }

        val dataEcho = img.attr("data-echo").trim()
        if (dataEcho.isNotBlank() && !isPlaceholderImage(dataEcho)) {
            return dataEcho
        }

        val dataSrc = img.attr("data-src").ifBlank { img.attr("data-original") }.trim()
        if (dataSrc.isNotBlank() && !isPlaceholderImage(dataSrc)) {
            return dataSrc
        }

        val src = img.attr("src").trim()
        if (src.isNotBlank() && !isPlaceholderImage(src) && !src.startsWith("data:", true)) {
            return src
        }

        return ""
    }

    private fun isPlaceholderImage(url: String): Boolean {
        if (url.isBlank()) return true
        // v110: data URLs de imagem (capas embutidas pelo WebView via data-cs-poster) sÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â£o
        // legÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â­timas ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â aplicar padrÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Âµes de texto ao base64 aleatÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â³rio descarta capas reais
        // (ex: "1x1" aparece com frequÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Âªncia em base64 de JPEG). SÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â³ rejeita SVG pequeno (1px).
        if (url.startsWith("data:image/", ignoreCase = true)) {
            return url.startsWith("data:image/svg+xml", ignoreCase = true) && url.length < 200
        }
        if (url.startsWith("data:image/svg+xml", ignoreCase = true) && url.length < 200) return true
        return PLACEHOLDER_PATTERNS.any { url.contains(it, ignoreCase = true) }
    }

    private fun optimizePosterUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank() || isPlaceholderImage(trimmed)) return ""
        if (trimmed.startsWith("data:image/", ignoreCase = true)) return trimmed

        val absoluteUrl = fixUrl(trimmed)
        return absoluteUrl.replace(" ", "%20")
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return "$mainUrl$url"
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return "$mainUrl/$url"
        }
        return url
    }

    private fun cleanMediaTitle(raw: String): String {
        var title = raw
            .replace(Regex("""(?i)\s*[-|ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â/]\s*Rede\s*Canais.*$"""), "")
            .replace(Regex("""(?i)\s*Rede\s*Canais.*$"""), "")
            .replace(Regex("""(?i)^Assistir\s+"""), "")
            .replace(Regex("""(?i)\s*[-|ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â/]?\s*Assistir\s+Online.*$"""), "")
            .replace(Regex("""(?i)\s+Online(\s+Gr[aÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡]tis|\s+em\s+HD|\s+HD)?\b"""), "")
            .replace(Regex("""(?i)\s*[-|ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â/]?\s*Lista\s+de\s+Epis[oÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³]dios.*$"""), "")
            .replace(Regex("""(?i)\s*[-|ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â/]?\s*Todas\s+as\s+Temporadas.*$"""), "")
            .replace(Regex("""(?i)\s*[-|ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â/]?\s*Completo\s+(?:Dublado|Legendado)?.*$"""), "")
            .replace(Regex("""(?i)\s*\([^)]*(?:Dublado|Legendado|Nacional|Dual|Temporada|Epis[oÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³]dio)[^)]*\)"""), "")
            .replace(Regex("""(?i)\s*\[[^\]]*(?:Dublado|Legendado|Nacional|Dual|Temporada|Epis[oÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³]dio)[^\]]*\]"""), "")
            .replace(Regex("""(?i)\s*[-|ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â/]?\s*(?:Dublado|Legendado|Nacional|Dual\s*[AÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â]udio).*$"""), "")
            .replace(Regex("""(?i)\b(?:720p|1080p|4k|uhd|fhd|hd|sd|cam|ts)\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        title = title.replace(Regex("""[\s(\[\-ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â/:]+$"""), "").trim()
        return title
    }

    // =========================================================================
    // EXTRAÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â‚¬Å¾Ã‚Â¢O DE EPISÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œDIOS COM SUPORTE A PHP MELODY
    // =========================================================================

    private fun parseEpisodes(doc: Document, seriesTitle: String, pageUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // 1. Select option (dropdowns de episÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³dios)
        doc.select("select option[value*='.html'], select option[value*='video_']").forEach { opt ->
            val href = opt.attr("value")
            val rawName = opt.text().trim()
            if (isValidEpisodeLink(href)) {
                val fullEpUrl = fixUrl(href)
                val (season, epNum) = extractSeasonAndEpisode(rawName, fullEpUrl, 1)
                episodes.add(
                    newEpisode(fullEpUrl) {
                        this.name = RedeCanaisAFText.cleanEpisodeTitle(rawName, epNum)
                        this.season = season
                        this.episode = epNum
                    }
                )
            }
        }

        // 2. Links dentro de .pm-video-description com rastreamento contextual de temporada
        if (episodes.isEmpty()) {
            val container = doc.selectFirst(".pm-video-description, #pm-video-description, .description, .episodios, .pm-video-episodes")
            if (container != null) {
                var currentContextSeason = RedeCanaisAFText.extractSeasonNumber(seriesTitle) ?: 1

                val nodes = container.select("h2, h3, h4, h5, strong, b, p, div, a")
                for (node in nodes) {
                    if (node.tagName() in listOf("h2", "h3", "h4", "h5", "strong", "b", "p")) {
                        val text = node.ownText().ifBlank { node.text() }.trim()
                        val sNum = RedeCanaisAFText.extractSeasonHeaderNumber(text)
                        if (sNum != null) {
                            currentContextSeason = sNum
                        }
                    }

                    if (node.tagName() == "a") {
                        val href = node.attr("href")
                        val rawName = node.text().trim()
                        if (isValidEpisodeLink(href)) {
                            val fullEpUrl = fixUrl(href)
                            val (season, epNum) = extractSeasonAndEpisode(rawName, fullEpUrl, currentContextSeason)
                            episodes.add(
                                newEpisode(fullEpUrl) {
                                    this.name = RedeCanaisAFText.cleanEpisodeTitle(rawName, epNum)
                                    this.season = season
                                    this.episode = epNum
                                }
                            )
                        }
                    }
                }
            }
        }

        // 3. Fallback: Varredura de links de episÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³dios no documento inteiro
        if (episodes.isEmpty()) {
            doc.select("a[href*='episodio'], a[href*='temporada'], a[href*='_']").forEach { a ->
                val href = a.attr("href")
                val rawName = a.text().trim()
                if (isValidEpisodeLink(href) && (href.contains("episodio", true) || rawName.contains("Epis", true))) {
                    val fullEpUrl = fixUrl(href)
                    val (season, epNum) = extractSeasonAndEpisode(rawName, fullEpUrl, 1)
                    episodes.add(
                        newEpisode(fullEpUrl) {
                            this.name = RedeCanaisAFText.cleanEpisodeTitle(rawName, epNum)
                            this.season = season
                            this.episode = epNum
                        }
                    )
                }
            }
        }

        // 4. Fallback: Link de episÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³dio individual aberto diretamente
        if (episodes.isEmpty()) {
            val (season, epNum) = extractSeasonAndEpisode(seriesTitle, pageUrl, 1)
            episodes.add(
                newEpisode(pageUrl) {
                    this.name = RedeCanaisAFText.cleanEpisodeTitle(seriesTitle, epNum)
                    this.season = season
                    this.episode = epNum
                }
            )
        }

        return episodes.distinctBy { it.data }
    }

    private fun isValidEpisodeLink(href: String): Boolean {
        if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript:")) return false
        val lower = href.lowercase()
        if (lower.contains("browse-") || lower.contains("category") || lower.contains("login") || lower.contains("register") || lower.contains("contact")) return false
        if (lower.contains("facebook.com") || lower.contains("t.me") || lower.contains("twitter.com") || lower.contains("whatsapp")) return false
        return lower.endsWith(".html") || lower.contains("video") || lower.contains("_")
    }

    private fun extractSeasonHeaderNumber(text: String): Int? {
        val match = Regex("""(?i)(?:^|[^\w])(\d+)[ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚ÂªaÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚ÂºoÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â°]?\s*(?:temp|temporada|season)\b""").find(text)
            ?: Regex("""(?i)\b(?:temporada|temp|season)\s*(\d+)\b""").find(text)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractSeasonNumber(text: String): Int? {
        val sFormat = Regex("""(?i)\bS(\d+)\s*E\d+\b""").find(text)
            ?: Regex("""(?i)\b(\d+)x\d+\b""").find(text)
        if (sFormat != null) return sFormat.groupValues[1].toIntOrNull()

        val sWord = Regex("""(?i)(\d+)[ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚ÂªaÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚ÂºoÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â°]?\s*[-_]?\s*(?:temporada|temp|season)\b""").find(text)
            ?: Regex("""(?i)\b(?:temporada|temp|season)[-_]?\s*(\d+)\b""").find(text)
            ?: Regex("""(?i)\bT(\d+)\b""").find(text)
        return sWord?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractEpisodeNumber(text: String): Int? {
        val sFormat = Regex("""(?i)\bS\d+\s*E(\d+)\b""").find(text)
            ?: Regex("""(?i)\b\d+x(\d+)\b""").find(text)
        if (sFormat != null) return sFormat.groupValues[1].toIntOrNull()

        val epWord = Regex("""(?i)\b(?:epis[oÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³]dio|episodio|ep|e)[-_.\s]*(\d+)\b""").find(text)
        if (epWord != null) return epWord.groupValues[1].toIntOrNull()

        val capWord = Regex("""(?i)\b(?:cap[iÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­]tulo|cap)[-_.\s]*(\d+)\b""").find(text)
        if (capWord != null) return capWord.groupValues[1].toIntOrNull()

        return null
    }

    private fun extractSeasonAndEpisode(text: String, url: String, fallbackSeason: Int): Pair<Int, Int> {
        val season = RedeCanaisAFText.extractSeasonNumber(text)
            ?: RedeCanaisAFText.extractSeasonNumber(url)
            ?: fallbackSeason

        val epNum = RedeCanaisAFText.extractEpisodeNumber(text)
            ?: RedeCanaisAFText.extractEpisodeNumber(url)
            ?: 1

        return Pair(season, epNum)
    }

    private fun cleanEpisodeTitle(raw: String, epNum: Int): String {
        val cleaned = raw
            .replace(Regex("""(?i)\s*-\s*Rede\s*Canais.*$"""), "")
            .replace(Regex("""(?i)\s*\|\s*Rede\s*Canais.*$"""), "")
            .replace(Regex("""(?i)^Assistir\s+"""), "")
            .replace(Regex("""(?i)\s+Online(\s+Gr[aÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡]tis|\s+em\s+HD|\s+HD)?\b"""), "")
            .replace(Regex("""(?i)\s*[-|ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â/]?\s*(?:Dublado|Legendado|Nacional|Dual\s*[AÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â]udio).*$"""), "")
            .replace(Regex("""(?i)\s*\((?:Dublado|Legendado|Nacional|Dual\s*[AÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â]udio)\)"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (cleaned.isBlank() || cleaned.equals("assistir", ignoreCase = true) || cleaned.equals("online", ignoreCase = true) || cleaned.length < 3) {
            return "EpisÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³dio $epNum"
        }
        return cleaned
    }

    // =========================================================================
    // EXTRAÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â‚¬Å¾Ã‚Â¢O E RESOLUÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â‚¬Å¾Ã‚Â¢O DE STREAMS / PLAYERS
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return StreamResolver(mainUrl, ::requestDoc).resolve(data, subtitleCallback, callback)
    }

}
