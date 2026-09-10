package com.RedeCanaisAF

import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.Requests
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withTimeoutOrNull

class RedeCanaisAF : MainAPI() {
    override var mainUrl = "https://redecanais.af"
    override var name = "RedeCanais (AF)"
    override val hasMainPage = true
    // v228: o conteúdo vem com latência variável (cf_clearance 520 + WebView de
    // fallback). Timeouts longos por chamada evitam que o framework aborte a Home
    // ("Timed out waiting for 120000 ms") quando uma categoria cai no WebView.
    // v228: requests sequenciais com delay — o framework dispara as 6 categorias em
    // paralelo; cada requestDoc() fora do cache abre um WebView de 5-8MB e o agregado
    // estoura o LMK do redroid (signal 9, "Timed out waiting for 120000 ms" na UI).
    // Sequencial: 1 WebView por vez, catálogo completo em ~6-10s, app vivo.
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 800L
    override var getMainPageTimeoutMs: Long? = 180_000L
    override var searchTimeoutMs: Long? = 120_000L
    override var loadTimeoutMs: Long? = 120_000L
    override var loadLinksTimeoutMs: Long? = 180_000L
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
        CloudflareSolver.setCatalogUrls(listOf(
            "$mainUrl/browse-filmes-videos-1-date.html",
            "$mainUrl/browse-series-videos-1-date.html",
            "$mainUrl/browse-animes-videos-1-date.html",
            "$mainUrl/browse-desenhos-videos-1-date.html",
            "$mainUrl/browse-filmes-videos-1-views.html",
            "$mainUrl/topvideos.html"
        ))
    }

    companion object {
        const val BUILD_VERSION = 255
        private const val TAG = "RedeCanaisAF-Trace"
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/AP1A.240505.005) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.113 Mobile Safari/537.36"

        private val reqCounter = AtomicInteger(0)
        private const val RESPONSE_CACHE_TTL_MS = 30 * 60 * 1000L
        private val homeCache = ConcurrentHashMap<String, Pair<Long, HomePageResponse>>()
        private val loadCache = ConcurrentHashMap<String, Pair<Long, LoadResponse>>()

        private fun logCookieState(stage: String, url: String, reqId: Int) {
            try {
                val cookies = CookieManager.getInstance().getCookie(url) ?: "none"
                val hasClearance = cookies.contains("cf_clearance")
                val hasCfBm = cookies.contains("__cf_bm")
                Log.d(TAG, "[REQ#$reqId][$stage] Cookies | clearance=$hasClearance | __cf_bm=$hasCfBm | rawLen=${cookies.length}")
            } catch (e: Throwable) {
                Log.w(TAG, "[REQ#$reqId][$stage] Failed to read CookieManager: ${e.message}")
            }
        }
    }

    private val cleanClient by lazy {
        val baseBuilder = app.baseClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // v227-dual: PRODUÇÃO usa NO_PROXY (FAST_GET>80%), ANÁLISE descomente abaixo
            // Para interceptar via mitmproxy/ZAP: comente a linha NO_PROXY e descomente o bloco INTERCEPT
            .proxy(java.net.Proxy.NO_PROXY)
            // INTERCEPT_ANALYSIS: deixe NO_PROXY comentado e use http_proxy=172.17.0.3:8080 (mitm->ZAP)
            .retryOnConnectionFailure(true)
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))

        baseBuilder.interceptors().removeAll {
            it.javaClass.simpleName.contains("Cloudflare", ignoreCase = true)
        }
        // v225-stealth: injeta headers indetectáveis (Sec-CH-UA/Sec-Fetch/Accept) em TODA requisição OkHttp do plugin
        baseBuilder.addInterceptor { chain ->
            val orig = chain.request()
            val stealth = CloudflareSolver.stealthHeaders(orig.header("Referer") ?: "$mainUrl/")
            val b = orig.newBuilder()
            for ((k, v) in stealth) {
                if (orig.header(k) == null) b.header(k, v)
            }
            // preserva Cookie/User-Agent já setados por requestDoc
            chain.proceed(b.build())
        }
        Requests(baseBuilder.build())
    }

    private val streamResolver by lazy { StreamResolver(mainUrl) { url, ref -> requestDoc(url, ref) } }

    /**
     * Headers customizados para carregar imagens e capas protegidas pelo Cloudflare.
     */
    internal fun posterHeaders(): Map<String, String> {
        val cookies = runCatching {
            CookieManager.getInstance().getCookie(mainUrl)
        }.getOrNull().orEmpty()

        val userAgent = CloudflareSolver.lastUserAgent
            ?: WebViewResolver.webViewUserAgent
            ?: DEFAULT_USER_AGENT

        return buildMap {
            put("User-Agent", userAgent)
            put("Referer", "$mainUrl/")
            put("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            if (cookies.isNotBlank()) {
                put("Cookie", cookies)
            }
        }
    }

    /**
     * Requisição HTTP resiliente com fallback automático para CloudflareSolver.
     */
    internal suspend fun requestDoc(url: String, referer: String = "$mainUrl/"): Document {
        val reqId = reqCounter.incrementAndGet()
        val fixedUrl = fixUrl(url)
        Log.i(TAG, "[REQ#$reqId] Fetching url=$fixedUrl referer=$referer")

        // 1. Verificação ultra-rápida de cache RAM e Disco (0ms) — v227: valida stale (found=0 / sem pm-grid)
        val cachedRaw = CloudflareSolver.capturedHtml(fixedUrl) ?: CloudflareSolver.getDiskCachedHtml(fixedUrl)
        if (!cachedRaw.isNullOrBlank() && !CloudflareSolver.isChallengeContent(cachedRaw)) {
            val isStale = cachedRaw.length < 50000 && !cachedRaw.contains("pm-video-thumb") && !cachedRaw.contains("pm-li-video")
            val missingGrid = !cachedRaw.contains("pm-grid") && !cachedRaw.contains("pm-category-browse") && !cachedRaw.contains("entry-title")
            // só barra cache stale para páginas de listagem/browse, não detail
            val isBrowse = fixedUrl.contains("browse-") || fixedUrl.contains("topvideos") || fixedUrl.contains("category")
            if (isStale && missingGrid && isBrowse) {
                Log.w(TAG, "[REQ#$reqId] Cache STALE len=${cachedRaw.length} url=$fixedUrl — forçando refresh via WebView")
            } else {
                Log.i(TAG, "[REQ#$reqId] HTML retornado instantaneamente do cache! len=${cachedRaw.length} url=$fixedUrl")
                return Jsoup.parse(cachedRaw, fixedUrl)
            }
        }

        logCookieState("BEFORE_REQ", fixedUrl, reqId)

        val cookie = runCatching {
            CookieManager.getInstance().getCookie(fixedUrl)
        }.getOrNull().orEmpty()

        val headers = mutableMapOf(
            "User-Agent" to (CloudflareSolver.lastUserAgent ?: WebViewResolver.webViewUserAgent ?: DEFAULT_USER_AGENT),
            "Referer" to referer,
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
        )

        if (cookie.isNotBlank()) {
            headers["Cookie"] = cookie
        }

        val hasClearance = cookie.contains("cf_clearance")
        val initialTimeout = if (hasClearance) 10L else 5L

        val res = try {
            cleanClient.get(
                fixedUrl,
                headers = headers,
                referer = referer,
                timeout = initialTimeout
            )
        } catch (e: Throwable) {
            Log.w(TAG, "[REQ#$reqId] cleanClient.get falhou ($e) — tentando CloudflareSolver")
            null
        }

        val code = res?.code ?: 0
        val body = res?.text.orEmpty()
        val isChallenge = code in 400..599 || CloudflareSolver.isPendingSearchContent(body) ||
            body.contains("Just a moment...", true) ||
            body.contains("Checking your browser", true) ||
            body.contains("cf-browser-verification", true) ||
            body.contains("challenge-platform", true)

        if (res != null && !isChallenge && body.isNotBlank()) {
            Log.d(TAG, "[REQ#$reqId] HTTP $code OK bodyLen=${body.length}")
            return Jsoup.parse(body, fixedUrl)
        }

        Log.w(TAG, "[REQ#$reqId] Cloudflare ativo (code=$code, isChallenge=$isChallenge). Resolvendo via CloudflareSolver...")
        val solverHtml = CloudflareSolver.solve(fixedUrl)
        if (solverHtml.isNotBlank()) {
            Log.i(TAG, "[REQ#$reqId] Cloudflare resolvido via WebView! len=${solverHtml.length}")
            logCookieState("AFTER_SOLVER", fixedUrl, reqId)
            return Jsoup.parse(solverHtml, fixedUrl)
        }
        // v229: solve() retornou "" mas o interactive armazenou o HTML do player na RAM
        // (o validador antigo descartava antes de retornar — corrida já corrigida no
        // solver mas este REQ usou o build anterior). Reaproveita direto da RAM.
        val ramHtml = CloudflareSolver.capturedHtml(fixedUrl)
        if (!ramHtml.isNullOrBlank() && !CloudflareSolver.isChallengeContent(ramHtml)) {
            Log.i(TAG, "[REQ#$reqId] HTML player reaproveitado da RAM! len=${ramHtml.length}")
            return Jsoup.parse(ramHtml, fixedUrl)
        }

        val cookieAfter = runCatching {
            CookieManager.getInstance().getCookie(fixedUrl)
        }.getOrNull().orEmpty()
        if (cookieAfter.isNotBlank()) {
            headers["Cookie"] = cookieAfter
            val retryRes = try {
                cleanClient.get(
                    fixedUrl,
                    headers = headers,
                    referer = referer,
                    timeout = 15L
                )
            } catch (e: Throwable) {
                null
            }
            val retryBody = retryRes?.text.orEmpty()
            val isRetryChallenge = (retryRes?.code ?: 0) in 400..599 || CloudflareSolver.isPendingSearchContent(retryBody) ||
                retryBody.contains("Just a moment...", true) ||
                retryBody.contains("Checking your browser", true) ||
                retryBody.contains("cf-browser-verification", true) ||
                retryBody.contains("challenge-platform", true)

            if (retryRes != null && !isRetryChallenge && retryBody.isNotBlank()) {
                Log.i(TAG, "[REQ#$reqId] Retry com cookies após solver OK! code=${retryRes.code} len=${retryBody.length}")
                return Jsoup.parse(retryBody, fixedUrl)
            }
        }

        Log.e(TAG, "[REQ#$reqId] Falha total ao carregar $fixedUrl")
        return Jsoup.parse(body.ifBlank { "<html><body></body></html>" }, fixedUrl)
    }

    // v228: mainPageOf(url to nome) — confirmado pelo framework: request.data=URL,
    // request.name=nome (o log 10:01 com "Cat=Filmes Lançamentos | url=https://..."
    // estava correto; a inversão nome->URL quebrou tudo: data virou "Filmes...").
    override val mainPage = mainPageOf(
        "$mainUrl/browse-filmes-videos-1-date.html" to "Filmes Lançamentos",
        "$mainUrl/browse-series-videos-1-date.html" to "Séries Lançamentos",
        "$mainUrl/browse-animes-videos-1-date.html" to "Animes Lançamentos",
        "$mainUrl/browse-desenhos-videos-1-date.html" to "Desenhos Lançamentos",
        "$mainUrl/browse-filmes-videos-1-views.html" to "Mais Vistos",
        "$mainUrl/topvideos.html" to "Top Filmes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            if (request.data.contains(Regex("""-\d+-(date|views|rating|title)\.html"""))) {
                request.data.replace(Regex("""-\d+-"""), "-$page-")
            } else if (request.data.endsWith(".html")) {
                request.data.replace(".html", "-page-$page.html")
            } else {
                "${request.data}?page=$page"
            }
        }

        Log.i(TAG, "[HOME_FETCH] Page=$page | Cat=${request.name} | url=$url")

        homeCache[url]?.let { (ts, cached) ->
            if (android.os.SystemClock.elapsedRealtime() - ts < RESPONSE_CACHE_TTL_MS) {
                Log.i(TAG, "[HOME_CACHE_HIT] url=$url")
                return cached
            }
        }

        // v228: sem withTimeoutOrNull aqui — o requestDoc() já resolve via WebView
        // quando precisa (é assim que as 6 categorias carregam em ~1s) e o timeout do
        // framework foi elevado para 180s. Envolver em timeout de 6-45s só produzia
        // "HOME_TIMEOUT_EMPTY" (lista vazia) ou cancelava o WebView no meio da
        // captura — foi isso que gerou o "Timed out waiting for 120000 ms" na UI.
        val doc = requestDoc(url)
        val homeList = mutableListOf<SearchResponse>()
        val seenUrls = HashSet<String>()

        val elements = doc.select(
            "#pm-grid > li, li.col-xs-6, li.col-sm-4, li.col-md-3, li.col-lg-3, " +
            "li.pm-li-video, article.pm-video-item, .pm-video-thumb, .pm-category-browse li, " +
            ".entry-item, li.video-item, div.pm-li-video"
        )
        Log.i(TAG, "[HOME_RAW_CARDS] Cat=${request.name} | found=${elements.size}")

        for (el in elements) {
            val card = parseCard(el) ?: continue
            if (seenUrls.add(card.url)) {
                homeList.add(card)
            }
        }

        val hasNext = doc.select(".pagination a[rel='next'], .pagination a.next, a:contains(Próximo), a:contains(»)")
            .isNotEmpty() || homeList.size >= 12

        val response = newHomePageResponse(
            listOf(HomePageList(request.name, homeList)),
            hasNext = hasNext
        )

        Log.i(TAG, "[HOME_RETURN] Cat=${request.name} | totalItems=${homeList.size} | hasNext=$hasNext")
        homeCache[url] = android.os.SystemClock.elapsedRealtime() to response
        return response
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")

        return try {
            val searchUrl = "$mainUrl/search.php?keywords=$encoded"
            val doc = requestDoc(searchUrl)
            var results = parseSearchResults(doc, query)
            Log.i(TAG, "[SEARCH_STAGE1] query='$query' | url=$searchUrl | raw=${results.size}")
            if (results.isEmpty()) {
                try {
                    // Keep production diagnostics bounded; never dump multi-MB page scripts.
                    Log.w(TAG, "[SEARCH_DIAG] query='$query' ready=${doc.selectFirst("html")?.attr("data-cs-search-ready")} listItems=${doc.select(".listagem > div").size}")
                } catch (e: Throwable) {
                    Log.w(TAG, "[SEARCH_DIAG_ERR] ${e.message}")
                }
            }

            if (results.isEmpty()) {
                try {
                    val searchHeaders = mutableMapOf(
                        "User-Agent" to (CloudflareSolver.lastUserAgent ?: WebViewResolver.webViewUserAgent ?: DEFAULT_USER_AGENT),
                        "Referer" to "$mainUrl/",
                        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
                    )
                    val searchCookie = runCatching {
                        CookieManager.getInstance().getCookie(mainUrl)
                    }.getOrNull().orEmpty()
                    if (searchCookie.isNotBlank()) {
                        searchHeaders["Cookie"] = searchCookie
                    }
                    val ajaxDoc = cleanClient.post(
                        "$mainUrl/ajax_search.php",
                        headers = searchHeaders,
                        data = mapOf("queryString" to URLEncoder.encode(query.trim(), "UTF-8")),
                        timeout = 15L
                    ).document
                    results = parseAjaxSearchResults(ajaxDoc, query)
                    Log.i(TAG, "[SEARCH_AJAX] query='$query' | raw=${results.size}")
                } catch (e: Throwable) {
                    Log.w(TAG, "[SEARCH_AJAX] query='$query' falhou: ${e.message}")
                }
            }

            Log.i(TAG, "[SEARCH_SUCCESS] query='$query' | results=${results.size}")
            results
        } catch (e: Throwable) {
            Log.e(TAG, "[SEARCH_ERROR] query='$query' | err=${e.message}")
            emptyList()
        }
    }

    private fun parseAjaxSearchResults(doc: Document, query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        doc.select("li, .pm-search-suggestion, a[href*='.html']").forEach { el ->
            val a = if (el.tagName() == "a") el else el.selectFirst("a[href*='.html']") ?: return@forEach
            val href = a.attr("href")
            if (href.contains("browse-") || href.contains("category") || href.contains("#") ||
                href.contains("modal") || href.contains("episodio") || href.contains("page/")) return@forEach

            val fullUrl = fixUrl(href)
            val title = el.selectFirst(".pm-search-suggestion-title, .title, h3, h4, strong, span")
                ?.text()?.trim()?.ifBlank { null }
                ?: a.attr("title").ifBlank { a.text().trim() }
                ?: return@forEach

            val clean = RedeCanaisAFText.cleanMediaTitle(title)
            if (clean.isBlank() || clean.equals("Watch Later", true)) return@forEach

            val img = el.selectFirst("img") ?: a.selectFirst("img")
            val poster = img?.let { RedeCanaisAFText.optimizePosterUrl(it.attr("data-echo").ifBlank { it.attr("src") }, ::fixUrl) }

            val isSeries = RedeCanaisAFText.isSeriesUrlOrTitle(fullUrl, title)
            val type = RedeCanaisAFText.determineTvType(fullUrl, emptyList(), isSeries)

            if (seen.add(fullUrl)) {
                val item = if (isSeries) {
                    newTvSeriesSearchResponse(clean, fullUrl, type) {
                        if (!poster.isNullOrBlank() && !RedeCanaisAFText.isPlaceholderImage(poster)) {
                            this.posterUrl = poster
                            this.posterHeaders = posterHeaders()
                        }
                    }
                } else {
                    newMovieSearchResponse(clean, fullUrl, type) {
                        if (!poster.isNullOrBlank() && !RedeCanaisAFText.isPlaceholderImage(poster)) {
                            this.posterUrl = poster
                            this.posterHeaders = posterHeaders()
                        }
                    }
                }
                results.add(item)
            }
        }
        return results
    }

    private fun parseSearchResults(doc: Document, query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        val cards = doc.select(
            "#pm-grid > li, li.col-xs-6, li.col-sm-4, li.col-md-3, li.col-lg-3, " +
            "li.pm-li-video, article.pm-video-item, .pm-video-thumb, " +
            ".pm-category-browse li, .entry-item, li.video-item, div.pm-li-video"
        )

        for (el in cards) {
            val card = parseCard(el) ?: continue
            if (RedeCanaisAFText.isRelevantSearchTitle(card.name, query) && seen.add(card.url)) {
                results.add(card)
            }
        }

        // Suporte a resultados em lista (.listagem div) do RedeCanais
        doc.select(".listagem div, .listagem > div, .lista-filmes div, section div.listagem div").forEach { div ->
            val a = div.selectFirst("a[href*='.html']") ?: return@forEach
            val href = a.attr("href")
            if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript:") ||
                href.contains("category") || href.contains("login") || href.contains("contact")
            ) return@forEach

            val rawTitle = div.selectFirst("span, strong, b")?.text()?.trim()
                ?.ifBlank { null }
                ?: div.ownText().replace("Acessar", "").replace("-", "").trim()
                ?: a.attr("title").ifBlank { a.text().trim() }

            if (rawTitle.isBlank() || rawTitle.equals("Acessar", true)) return@forEach

            if (RedeCanaisAFText.isRelevantSearchTitle(rawTitle, query)) {
                val fullUrl = fixUrl(href)
                val clean = RedeCanaisAFText.cleanMediaTitle(rawTitle)
                val isSeries = RedeCanaisAFText.isSeriesUrlOrTitle(fullUrl, rawTitle)
                val type = RedeCanaisAFText.determineTvType(fullUrl, emptyList(), isSeries)

                val img = div.selectFirst("img") ?: a.selectFirst("img")
                val rawPoster = img?.attr("data-echo")
                    ?.ifBlank { img.attr("data-src") }
                    ?.ifBlank { img.attr("src") }
                    .orEmpty()

                var poster = if (rawPoster.isNotBlank()) {
                    RedeCanaisAFText.optimizePosterUrl(rawPoster, ::fixUrl)
                } else {
                    val folder = when (type) {
                        TvType.Anime, TvType.AnimeMovie -> "Animes"
                        TvType.Cartoon -> "Desenhos"
                        TvType.TvSeries -> "Series"
                        else -> "Filmes"
                    }
                    RedeCanaisAFText.optimizePosterUrl("$mainUrl/imgs-videos/$folder/$clean.jpg", ::fixUrl)
                }

                if (seen.add(fullUrl)) {
                    val item = if (isSeries) {
                        newTvSeriesSearchResponse(clean, fullUrl, type) {
                            if (poster.isNotBlank() && !RedeCanaisAFText.isPlaceholderImage(poster)) {
                                this.posterUrl = poster
                                this.posterHeaders = posterHeaders()
                            }
                        }
                    } else {
                        newMovieSearchResponse(clean, fullUrl, type) {
                            if (poster.isNotBlank() && !RedeCanaisAFText.isPlaceholderImage(poster)) {
                                this.posterUrl = poster
                                this.posterHeaders = posterHeaders()
                            }
                        }
                    }
                    results.add(item)
                }
            }
        }

        if (results.isEmpty()) {
            doc.select("a[href*='.html']").forEach { a ->
                val href = a.attr("href")
                val text = a.text().trim()
                if (RedeCanaisAFText.isValidEpisodeLink(href) &&
                    RedeCanaisAFText.isRelevantSearchTitle(text, query)
                ) {
                    val fullUrl = fixUrl(href)
                    val clean = RedeCanaisAFText.cleanMediaTitle(text)
                    val isSeries = RedeCanaisAFText.isSeriesUrlOrTitle(fullUrl, text)
                    val type = RedeCanaisAFText.determineTvType(fullUrl, emptyList(), isSeries)
                    val folder = when (type) {
                        TvType.Anime, TvType.AnimeMovie -> "Animes"
                        TvType.Cartoon -> "Desenhos"
                        TvType.TvSeries -> "Series"
                        else -> "Filmes"
                    }
                    val poster = RedeCanaisAFText.optimizePosterUrl("$mainUrl/imgs-videos/$folder/$clean.jpg", ::fixUrl)
                    if (seen.add(fullUrl)) {
                        val item = if (isSeries) {
                            newTvSeriesSearchResponse(clean, fullUrl, type) {
                                if (poster.isNotBlank() && !RedeCanaisAFText.isPlaceholderImage(poster)) {
                                    this.posterUrl = poster
                                    this.posterHeaders = posterHeaders()
                                }
                            }
                        } else {
                            newMovieSearchResponse(clean, fullUrl, type) {
                                if (poster.isNotBlank() && !RedeCanaisAFText.isPlaceholderImage(poster)) {
                                    this.posterUrl = poster
                                    this.posterHeaders = posterHeaders()
                                }
                            }
                        }
                        results.add(item)
                    }
                }
            }
        }

        return results
    }

    private fun parseCard(element: Element): SearchResponse? {
        val a = element.select("a").firstOrNull { el ->
            val h = el.attr("href")
            h.isNotBlank() &&
                !h.startsWith("#") &&
                !h.startsWith("javascript:") &&
                !h.contains("browse-") &&
                !h.contains("category") &&
                !h.contains("user/") &&
                !h.contains("login") &&
                !h.contains("register") &&
                !h.contains("contact") &&
                !h.contains("mapa") &&
                (h.endsWith(".html") || h.contains(".html?"))
        } ?: element.selectFirst("h3 a, h2 a, .caption a, .pm-video-title a, a[href*='_']")
            ?: element.selectFirst("a[href*='.html']")
            ?: return null

        val href = a.attr("href")
        if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript:") ||
            href.contains("browse-") || href.contains("category") || href.contains("user/") ||
            href.contains("login") || href.contains("register") || href.contains("contact") ||
            href.contains("mapa")
        ) {
            return null
        }

        val fullUrl = fixUrl(href)
        val rawTitle = a.attr("title").ifBlank {
            element.selectFirst("h3 a, h2 a, .caption a, h3, h2, .pm-video-title, .title, strong")?.let {
                it.attr("title").ifBlank { it.text() }
            }?.trim().orEmpty()
        }.ifBlank {
            a.text().trim()
        }

        if (rawTitle.isBlank()) return null
        val title = RedeCanaisAFText.cleanMediaTitle(rawTitle)
        if (title.isBlank() || title.equals("Watch Later", true) || title.equals("Novo", true)) return null

        val img = element.selectFirst("img")
        val rawPoster = img?.attr("data-cs-poster")
            ?.ifBlank { img.attr("data-echo") }
            ?.ifBlank { img.attr("data-src") }
            ?.ifBlank { img.attr("data-lazy-src") }
            ?.ifBlank { img.attr("src") }
            .orEmpty()

        val isSeries = RedeCanaisAFText.isSeriesUrlOrTitle(fullUrl, rawTitle)
        val tvType = RedeCanaisAFText.determineTvType(fullUrl, emptyList(), isSeries)

        var posterUrl = RedeCanaisAFText.optimizePosterUrl(rawPoster, ::fixUrl)
        if (posterUrl.isBlank() || RedeCanaisAFText.isPlaceholderImage(posterUrl)) {
            val folder = when (tvType) {
                TvType.Anime, TvType.AnimeMovie -> "Animes"
                TvType.Cartoon -> "Desenhos"
                TvType.TvSeries -> "Series"
                else -> "Filmes"
            }
            posterUrl = RedeCanaisAFText.optimizePosterUrl("$mainUrl/imgs-videos/$folder/$title.jpg", ::fixUrl)
        }

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fullUrl, tvType) {
                if (posterUrl.isNotBlank() && !RedeCanaisAFText.isPlaceholderImage(posterUrl)) {
                    this.posterUrl = posterUrl
                    this.posterHeaders = posterHeaders()
                }
            }
        } else {
            newMovieSearchResponse(title, fullUrl, tvType) {
                if (posterUrl.isNotBlank() && !RedeCanaisAFText.isPlaceholderImage(posterUrl)) {
                    this.posterUrl = posterUrl
                    this.posterHeaders = posterHeaders()
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = fixUrl(url)
        Log.i(TAG, "[LOAD_START] url=$cleanUrl")

        loadCache[cleanUrl]?.let { (ts, cached) ->
            if (android.os.SystemClock.elapsedRealtime() - ts < RESPONSE_CACHE_TTL_MS) {
                Log.i(TAG, "[LOAD_CACHE_HIT] url=$cleanUrl")
                return cached
            }
        }

        val doc = requestDoc(cleanUrl)

        // v228: dumps de debug DESABILITADOS em produção — doc.html() de 5-6MB por
        // chamada de load() era o maior alocador de memória do plugin (20MB+ LOS por
        // boot, GC pausando 469ms) e o LMK matava o app logo após a Home (signal 9).
        // Para reativar em análise, descomente o bloco abaixo.
        /*
        try {
            val ctx = com.lagradost.cloudstream3.CommonActivity.activity ?: com.lagradost.cloudstream3.CommonActivity.activity?.applicationContext
            ctx?.let {
                val full = doc.html()
                val f = java.io.File(it.filesDir, "redecanais_af_last_detail.html")
                f.writeText(full)
                val bodyIdx = full.indexOf("<body")
                val slice = if (bodyIdx >= 0) full.substring(bodyIdx, minOf(bodyIdx + 800_000, full.length)) else full.take(800_000)
                java.io.File(it.filesDir, "redecanais_af_last_detail_body.html").writeText(slice)
                Log.i(TAG, "[VERIFY_DETAIL] dumped len=${full.length} bodyAt=$bodyIdx to ${f.absolutePath}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[VERIFY_DETAIL] dump failed: ${e.message}")
        }
        */

        val rawTitle = doc.selectFirst("h1.entry-title, h1.pm-video-title, h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim().orEmpty()

        val title = RedeCanaisAFText.cleanMediaTitle(rawTitle).ifBlank { "Sem Título" }
        val (posterUrl, backdropUrl) = extractDetailImages(doc)

        val plotRaw = doc.selectFirst(
            "meta[property='og:description'], meta[name='description'], " +
            "#pm-video-description, .pm-video-description, .entry-content, .description, p.plot"
        )?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }?.trim()
        val plot = plotRaw?.let { RedeCanaisAFText.cleanPlotText(it) }?.takeIf { it.isNotBlank() } ?: plotRaw

        // v228: trace de 1 linha (14 doc.select() removidos — cada um percorre o DOM
        // de 5MB; o agregado travava o GC e o LMK matava o app).
        Log.i(TAG, "[VERIFY_DETAIL] title='${title.take(60)}' poster=${posterUrl?.take(60)} plot_len=${plot?.length ?: 0}")

        val tags = doc.select("meta[property='article:tag'], .pm-video-tags a, .tags a, .genres a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val year = RedeCanaisAFText.extractYear(rawTitle) ?: doc.selectFirst(".pm-video-attr-date, .release-date, .year")?.text()?.let {
            RedeCanaisAFText.extractYear(it)
        }

        val duration = doc.selectFirst(".pm-video-attr-duration, .duration, span.duration")?.text()?.let {
            RedeCanaisAFText.extractDurationMinutes(it)
        }

        val isSeries = RedeCanaisAFText.isSeriesUrlOrTitle(cleanUrl, rawTitle) ||
            doc.select("select option[value*='.html'], select option[value*='video_'], .pm-video-description a[href*='episodio']").isNotEmpty()

        val tvType = RedeCanaisAFText.determineTvType(cleanUrl, tags, isSeries)

        if (isSeries) {
            val episodes = parseEpisodes(doc, rawTitle, cleanUrl)
            if (episodes.isNotEmpty()) {
                val response = newTvSeriesLoadResponse(title, cleanUrl, tvType, episodes) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backdropUrl ?: posterUrl
                    this.posterHeaders = posterHeaders()
                    this.plot = plot
                    this.tags = tags
                    this.year = year
                    this.duration = duration
                }
                loadCache[cleanUrl] = android.os.SystemClock.elapsedRealtime() to response
                return response
            }
        }

        val response = newMovieLoadResponse(title, cleanUrl, tvType, cleanUrl) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backdropUrl ?: posterUrl
            this.posterHeaders = posterHeaders()
            this.plot = plot
            this.tags = tags
            this.year = year
            this.duration = duration
        }
        loadCache[cleanUrl] = android.os.SystemClock.elapsedRealtime() to response
        return response
    }

    private fun extractDetailImages(doc: Document): Pair<String?, String?> {
        val candidates = mutableListOf<String>()

        doc.selectFirst("img[data-cs-poster]")?.attr("data-cs-poster")?.let { candidates.add(it) }
        doc.selectFirst("meta[property='og:image'][data-cs-poster]")?.attr("data-cs-poster")?.let { candidates.add(it) }

        doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { candidates.add(it) }
        doc.selectFirst("meta[name='twitter:image']")?.attr("content")?.let { candidates.add(it) }
        doc.selectFirst("link[rel='image_src']")?.attr("href")?.let { candidates.add(it) }

        doc.selectFirst(".pm-category-description img")?.let { img ->
            val src = img.attr("data-echo").ifBlank { img.attr("data-src") }.ifBlank { img.attr("src") }
            candidates.add(src)
        }

        doc.selectFirst("img[data-echo*='/imgs-videos/']")?.attr("data-echo")?.let { candidates.add(it) }
        doc.selectFirst("img[src*='/imgs-videos/']")?.let { img ->
            val src = img.attr("data-echo").ifBlank { img.attr("data-src") }.ifBlank { img.attr("src") }
            candidates.add(src)
        }

        doc.selectFirst(".pm-video-watch-wrap img, .pm-video-thumb img, article img, .pm-video-img img, .poster img, img[itemprop='thumbnailUrl'], .entry-content img, .description img")?.let { img ->
            val src = img.attr("data-echo")
                .ifBlank { img.attr("data-src") }
                .ifBlank { img.attr("src") }
            candidates.add(src)
        }

        val validImages = candidates
            .map { RedeCanaisAFText.optimizePosterUrl(it, ::fixUrl) }
            .filter { it.isNotBlank() && !RedeCanaisAFText.isPlaceholderImage(it) && !it.startsWith("data:image/gif;base64,R0lGOD", true) }
            .distinct()

        val poster = validImages.firstOrNull()
        val backdrop = validImages.getOrNull(1) ?: poster

        return Pair(poster, backdrop)
    }

    private fun parseEpisodes(doc: Document, seriesTitle: String, pageUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // 1. Select option (dropdowns de episódios)
        doc.select("select option[value*='.html'], select option[value*='video_']").forEach { opt ->
            val href = opt.attr("value")
            val rawName = opt.text().trim()
            if (RedeCanaisAFText.isValidEpisodeLink(href)) {
                val fullEpUrl = fixUrl(href)
                val (season, epNum) = RedeCanaisAFText.extractSeasonAndEpisode(rawName, fullEpUrl, 1)
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
            val container = doc.selectFirst(".pm-video-description, #pm-video-description, [itemprop='description'], .description, .episodios, .pm-video-episodes")
            if (container != null) {
                var currentContextSeason = RedeCanaisAFText.extractSeasonNumber(seriesTitle) ?: 1
                val lines = container.html().split(Regex("""(?i)<br\s*/?>|</p>|</div>"""))
                for (line in lines) {
                    val plain = line.replace(Regex("""<[^>]*>"""), " ").replace(Regex("""\s+"""), " ").trim()
                    val sNum = RedeCanaisAFText.extractSeasonHeaderNumber(plain)
                    if (sNum != null) {
                        currentContextSeason = sNum
                    }
                    val aMatches = Regex("""(?i)<a[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""").findAll(line)
                    for (m in aMatches) {
                        val href = m.groupValues[1]
                        val linkText = m.groupValues[2].replace(Regex("""<[^>]*>"""), "").trim()
                        if (RedeCanaisAFText.isValidEpisodeLink(href)) {
                            val fullEpUrl = fixUrl(href)
                            val combinedText = if (plain.length > linkText.length + 3) plain else linkText
                            val (season, epNum) = RedeCanaisAFText.extractSeasonAndEpisode(combinedText, fullEpUrl, currentContextSeason)
                            episodes.add(
                                newEpisode(fullEpUrl) {
                                    this.name = RedeCanaisAFText.cleanEpisodeTitle(combinedText, epNum)
                                    this.season = season
                                    this.episode = epNum
                                }
                            )
                        }
                    }
                }
            }
        }

        // 3. Suporte a listagens de categoria / browse (.pm-category-browse / #pm-grid)
        if (episodes.isEmpty()) {
            val gridCards = doc.select("#pm-grid li, article.pm-video-item, .pm-category-browse li, li.pm-li-video, .video-item, .entry-item")
            for (card in gridCards) {
                val a = card.selectFirst("a[href*='video_'], a[href*='.html']") ?: continue
                val href = a.attr("href")
                val rawName = a.attr("title").ifBlank { card.selectFirst(".pm-video-title, .entry-title, h3, h4, h2")?.text() }.orEmpty().ifBlank { a.text().trim() }
                if (RedeCanaisAFText.isValidEpisodeLink(href)) {
                    val fullEpUrl = fixUrl(href)
                    val (season, epNum) = RedeCanaisAFText.extractSeasonAndEpisode(rawName, fullEpUrl, 1)
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

        // 4. Fallback: Varredura de links de episódios no documento inteiro
        if (episodes.isEmpty()) {
            doc.select("a[href*='episodio'], a[href*='temporada'], a[href*='_']").forEach { a ->
                val href = a.attr("href")
                val rawName = a.text().trim()
                if (RedeCanaisAFText.isValidEpisodeLink(href) && (href.contains("episodio", true) || rawName.contains("Epis", true) || rawName.contains("Assistir", true))) {
                    val fullEpUrl = fixUrl(href)
                    val (season, epNum) = RedeCanaisAFText.extractSeasonAndEpisode(rawName, fullEpUrl, 1)
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

        // 5. Fallback: Link de episódio individual aberto diretamente
        if (episodes.isEmpty()) {
            val (season, epNum) = RedeCanaisAFText.extractSeasonAndEpisode(seriesTitle, pageUrl, 1)
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return streamResolver.resolve(data, subtitleCallback, callback)
    }
}
