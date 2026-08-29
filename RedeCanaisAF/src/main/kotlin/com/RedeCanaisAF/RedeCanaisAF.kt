package com.RedeCanaisAF

import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.jsoup.Jsoup
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
        const val BUILD_VERSION = 143
        private const val TAG = "RedeCanaisAF-Trace"

        private val reqCounter = java.util.concurrent.atomic.AtomicInteger(0)
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
            "Temporada", "Temp", "EpisÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³dio", "Episodio", "Ep.", "Ep ", 
            "Completo Dublado", "Lista de EpisÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³dios", "Todas as Temporadas",
            "1ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Âª", "2ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Âª", "3ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Âª", "4ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Âª", "5ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Âª", "6ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Âª", "7ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Âª", "8ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Âª", "9ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Âª"
        )

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

    private val cloudflareKiller = CloudflareKiller()

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
        val reqId = reqCounter.incrementAndGet()
        val startRealtime = android.os.SystemClock.elapsedRealtime()

        Log.i(TAG, "[REQ#$reqId][START] url=$url | referer=$referer")
        logCookieState("BEFORE_REQ", url, reqId)

        return try {
            val freshCookies = CookieManager.getInstance().getCookie(url) ?: ""
            val userAgent = CloudflareSolver.lastUserAgent 
                ?: WebViewResolver.webViewUserAgent 
                ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

            // v109: O site NÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢O emite cf_clearance persistente (liberaÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o amarrada ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â  sessÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o TLS do
            // navegador). O CloudflareKiller/OkHttp NUNCA passa (JA3 diferente). A via confiÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡vel ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â©
            // o WebView: o app.get retorna 403 rÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡pido e o FALLBACK_WV abaixo usa o HTML renderizado
            // pelo CloudflareSolver DIRETO (conteÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Âºdo real em ~7s). Capas via data-cs-poster.

            val reqHeaders = mutableMapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer" to referer,
                "Upgrade-Insecure-Requests" to "1",
                "User-Agent" to userAgent
            )
            if (freshCookies.isNotBlank()) {
                reqHeaders["Cookie"] = freshCookies
            }

            val resp = app.get(
                url,
                headers = reqHeaders,
                timeout = 30L
            )

            val httpRealtime = android.os.SystemClock.elapsedRealtime()
            val snippet = resp.text.replace("\n", " ").take(250)
            Log.i(TAG, "[REQ#$reqId][HTTP_SUCCESS] code=${resp.code} | elapsedMs=${httpRealtime - startRealtime} | bodyLen=${resp.text.length} | snippet=$snippet")
            logCookieState("AFTER_REQ", url, reqId)

            var finalDoc = resp.document
            val initialLinks = finalDoc.select("a[href]").size

            if (resp.code != 200 || initialLinks == 0 || resp.text.contains("Just a moment...", true)) {
                Log.w(TAG, "[REQ#$reqId][FALLBACK_WV] HTTP code=${resp.code} | links=$initialLinks -> Extraindo DOM renderizado via WebView V8 silencioso")
                // v120: domínios de player externos (redecanaistv.af) precisam de timeout maior —
                // o Turnstile managed resolve sozinho em 30-60s sem interação
                val isForeignPlayer = url.contains("redecanaistv", true) || (
                    (url.contains("server.php", true) || url.contains("player", true) || url.contains("redirect.api", true)) &&
                    !url.contains("redecanais.af", true)
                )
                val wvTimeout = if (isForeignPlayer) 120000L else 40000L
                Log.i(TAG, "[REQ#$reqId][FALLBACK_WV] foreignPlayer=$isForeignPlayer | wvTimeout=$wvTimeout")
                // v128: o CF_HTML invisível SÓ funciona quando o cf_clearance já está válido (extrai em
                // ~7s). Sem cf_clearance ele gasta 40s e falha ("Challenge persistente") — e como o
                // diálogo interativo agora é SERIALIZADO (mutex), esses 40s desperdiçados por REQ
                // estouram o deadline de 120s do framework para os REQs seguintes. Sem clearance, vai
                // direto ao diálogo (o único caminho que resolve o Turnstile managed).
                val hasClearanceNow = (CookieManager.getInstance().getCookie(url) ?: "").contains("cf_clearance")
                var html: String?
                if (hasClearanceNow) {
                    html = CloudflareSolver.solveAndGetHtml(url, timeoutMs = wvTimeout)
                } else {
                    Log.w(TAG, "[REQ#$reqId][FALLBACK_WV] sem cf_clearance -> pulando CF_HTML (40s) e indo direto ao diálogo serializado")
                    html = null
                }
                if (html.isNullOrBlank()) {
                    // v122: WebView invisível não resolveu o challenge (cf_clearance expirado por IP dinâmico).
                    // Abre o diálogo interativo para o usuário tocar na caixinha do Turnstile uma vez.
                    Log.w(TAG, "[REQ#$reqId][FALLBACK_WV_INTERACTIVE] HTML vazio/inválido -> abrindo diálogo de verificação")
                    // v128: retorna o HTML capturado do MESMO WebView do diálogo (sessão TLS que resolveu
                    // o Turnstile). null = falhou; "" = resolveu mas sem HTML (ex: cf_clearance de outro
                    // REQ no lock) -> solveAndGetHtml agora funciona com o cookie válido.
                    val dialogHtml = CloudflareSolver.solveInteractive(url, timeoutMs = 115000L, force = true)
                    Log.i(TAG, "[REQ#$reqId][FALLBACK_WV_INTERACTIVE] solveInteractive -> html=${dialogHtml?.length ?: "null"}")
                    if (dialogHtml != null) {
                        if (dialogHtml.isBlank()) {
                            html = CloudflareSolver.solveAndGetHtml(url, timeoutMs = wvTimeout)
                        } else {
                            html = dialogHtml
                            Log.i(TAG, "[REQ#$reqId][FALLBACK_WV_INTERACTIVE] usando HTML do diálogo (len=${dialogHtml.length})")
                        }
                    }
                }
                if (!html.isNullOrBlank()) {
                    val parsed = Jsoup.parse(html, url)
                    val linkCount = parsed.select("a[href]").size
                    Log.i(TAG, "[REQ#$reqId][FALLBACK_WV_SUCCESS] htmlLen=${html.length} | linksEncontrados=$linkCount")
                    if (linkCount > 0) {
                        finalDoc = parsed
                    }
                }
            }

            finalDoc
        } catch (e: kotlinx.coroutines.CancellationException) {
            val errRealtime = android.os.SystemClock.elapsedRealtime()
            Log.w(TAG, "[REQ#$reqId][CANCELLED] elapsedMs=${errRealtime - startRealtime}")
            throw e
        } catch (e: Throwable) {
            val errRealtime = android.os.SystemClock.elapsedRealtime()
            val isActive = currentCoroutineContext().isActive
            Log.e(
                TAG,
                "[REQ#$reqId][REQ_ERROR] elapsedMs=${errRealtime - startRealtime} | isActive=$isActive | exception=${e.javaClass.simpleName} | message=${e.message}"
            )
            logCookieState("AFTER_ERROR", url, reqId)
            throw e
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseSlug = request.data
        val url = "$baseSlug-$page-date.html"
        val now = android.os.SystemClock.elapsedRealtime()
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
            Log.w(TAG, "[MAINPAGE_FAIL] Cat=${request.name} url=$url err=${e.message}")
            return newHomePageResponse(listOf(HomePageList(request.name, emptyList())), hasNext = false)
        }

        val htmlLen = doc.html().length
        val elements = doc.select("div.pm-video-thumb, li.pm-video-thumb, .pm-video-thumb")
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

        val hasNext = doc.select(".pagination a[rel='next'], .pagination a.next, a:contains(PrÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³ximo), a:contains(ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â»)").isNotEmpty() || homeList.size >= 12
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
            val elements = doc.select("div.pm-video-thumb, li.pm-video-thumb, .pm-video-thumb")
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

        val title = cleanMediaTitle(rawTitle)

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
        if (!metaDesc.isNullOrBlank() && metaDesc.length > 30 && !isJunkText(metaDesc)) {
            return cleanPlotText(metaDesc)
        }

        val descContainer = doc.selectFirst(".pm-video-description, #pm-video-description, .description, .sinopse")
        if (descContainer != null) {
            val clone = descContainer.clone()
            clone.select("a, script, style, select, button, form, iframe, .episodios, .pm-video-episodes, .pm-ads").remove()

            val paragraphs = clone.select("p").map { it.text().trim() }.filter { it.length > 25 && !isJunkText(it) }
            if (paragraphs.isNotEmpty()) {
                return cleanPlotText(paragraphs.joinToString("\n\n"))
            }

            val directText = clone.ownText().trim()
            if (directText.length > 25 && !isJunkText(directText)) {
                return cleanPlotText(directText)
            }

            val allText = clone.text().trim()
            if (allText.length > 25 && !isJunkText(allText)) {
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

        val cleanTitle = cleanMediaTitle(rawTitle)
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
        val imgs = element.select("img")
        if (imgs.isEmpty()) return ""

        // Percorre TODAS as imgs do card (nao so a primeira): se a 1a for placeholder,
        // tenta as seguintes antes de desistir.
        for (img in imgs) {
            val found = extractPosterFromSingleImg(img)
            if (found.isNotBlank()) return found
        }
        return ""
    }

    private fun extractPosterFromSingleImg(img: Element): String {
        // v109: data-cs-poster (capa embutida pelo WebView) tem prioridade maxima
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

        // data-srcset (lazy srcset): verificar antes de src
        val dataSrcset = img.attr("data-srcset").trim()
        if (dataSrcset.isNotBlank()) {
            val fromSrcset = parseSrcset(dataSrcset)
            if (fromSrcset.isNotBlank()) return fromSrcset
        }

        val src = img.attr("src").trim()
        if (src.isNotBlank() && !isPlaceholderImage(src) && !src.startsWith("data:", true)) {
            return src
        }

        // srcset direto: so usado se src for placeholder
        val srcset = img.attr("srcset").trim()
        if (srcset.isNotBlank()) {
            val fromSrcset = parseSrcset(srcset)
            if (fromSrcset.isNotBlank()) return fromSrcset
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

    /**
     * Parser de srcset (HTML spec).
     * Formato: "url descriptor, url descriptor, ..."
     *   - descriptor pode ser "300w" (largura), "2x" (densidade) ou ausente (=1x)
     * Retorna a URL do candidato de MAIOR resolucao, normalizada, ou "" se nenhum valido.
     */
    private fun parseSrcset(srcset: String): String {
        if (srcset.isBlank()) return ""
        val candidates = srcset.split(",").mapNotNull { raw ->
            val entry = raw.trim()
            if (entry.isEmpty()) return@mapNotNull null
            // Descritor e o ULTIMO token whitespace-separated se parecer "300w"/"2x"/"1.5x".
            // URLs com espaco NAO podem ser truncadas no primeiro espaco — por isso o split
            // e feito pelo fim: pega-se tudo antes do descritor como URL.
            val descriptorMatch = Regex("""^(.*?)[\s]+(\d+(?:\.\d+)?[wx]|\d+(?:\.\d+)?)$""", RegexOption.IGNORE_CASE)
                .matchEntire(entry)
            val urlRaw: String
            val descriptor: String
            if (descriptorMatch != null) {
                urlRaw = descriptorMatch.groupValues[1].trim()
                descriptor = descriptorMatch.groupValues[2]
            } else {
                urlRaw = entry
                descriptor = ""
            }
            if (urlRaw.isEmpty()) return@mapNotNull null
            // desnormalizar: srcset costuma ter %20 ja, mas garantimos espacos p/ selecao
            val urlFixed = urlRaw.replace("%20", " ")
            // parse do descriptor -> (largura, densidade)
            val w = Regex("""^(\d+)w$""", RegexOption.IGNORE_CASE).matchEntire(descriptor)?.groupValues?.get(1)?.toIntOrNull()
            val d = if (w == null) {
                Regex("""^([\d.]+)x$""", RegexOption.IGNORE_CASE).matchEntire(descriptor)?.groupValues?.get(1)?.toFloatOrNull() ?: 1.0f
            } else 1.0f
            val normalized = optimizePosterUrl(urlFixed)
            if (normalized.isBlank() || isPlaceholderImage(normalized)) return@mapNotNull null
            Triple(normalized, w ?: -1, d)
        }
        // seleciona o de maior largura; em empate, maior densidade; senao o primeiro
        val best = candidates.maxWithOrNull(
            compareBy<Triple<String, Int, Float>> { it.second }
                .thenBy { it.third }
                .thenBy { -it.first.length }
        )
        return best?.first ?: ""
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
                        this.name = cleanEpisodeTitle(rawName, epNum)
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
                var currentContextSeason = extractSeasonNumber(seriesTitle) ?: 1

                val nodes = container.select("h2, h3, h4, h5, strong, b, p, div, a")
                for (node in nodes) {
                    if (node.tagName() in listOf("h2", "h3", "h4", "h5", "strong", "b", "p")) {
                        val text = node.ownText().ifBlank { node.text() }.trim()
                        val sNum = extractSeasonHeaderNumber(text)
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
                                    this.name = cleanEpisodeTitle(rawName, epNum)
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
                            this.name = cleanEpisodeTitle(rawName, epNum)
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
                    this.name = cleanEpisodeTitle(seriesTitle, epNum)
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
        val season = extractSeasonNumber(text)
            ?: extractSeasonNumber(url)
            ?: fallbackSeason

        val epNum = extractEpisodeNumber(text)
            ?: extractEpisodeNumber(url)
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
        var foundAny = false
        val cleanUrl = data.substringBefore("#")
        val visitedUrls = mutableSetOf<String>()

        Log.i(TAG, "[LOADLINKS_START] data=$cleanUrl")

        try {
            val doc = requestDoc(cleanUrl)
            val html = doc.html()

            // 1. Coleta de todos os Iframes e Embeds do DOM
            // 1. Coleta focada de Iframes legÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­timos de vÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­deo (ignora Disqus, Ads, etc.)
            val iframeElements = doc.select(
                ".pm-video-watch-wrap iframe, #player iframe, div.player iframe, " +
                ".player-wrapper iframe, #player-embed iframe, .video-player iframe, " +
                "iframe[src*='player'], iframe[src*='server.php'], iframe[src*='play.php'], iframe[src*='embed']"
            )

            val embedCandidates = mutableListOf<Pair<String, String>>()

            for ((index, el) in iframeElements.withIndex()) {
                val rawSrc = el.attr("data-src")
                    .ifBlank { el.attr("data-lazy-src") }
                    .ifBlank { el.attr("src") }
                    .ifBlank { el.attr("data") }

                if (rawSrc.isNotBlank() && 
                    !rawSrc.contains("about:blank", true) && 
                    !rawSrc.contains("recaptcha", true) &&
                    !rawSrc.contains("disqus", true) &&
                    !rawSrc.contains("facebook", true) &&
                    !rawSrc.contains("google", true) &&
                    !rawSrc.endsWith(".js", true) &&
                    !rawSrc.endsWith(".css", true)
                ) {
                    val label = "Player ${index + 1}"
                    embedCandidates.add(fixUrl(rawSrc) to label)
                }
            }

            // 2. Coleta de botÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Âµes / abas de servidores alternativos ou abas dublado/legendado
            val altButtons = doc.select("a[href*='player'], a[href*='play.php'], a[href*='player3.php'], a[href*='server.php'], button[data-src], select[name*='player'] option")
            for (btn in altButtons) {
                val btnSrc = btn.attr("data-src").ifBlank { btn.attr("href") }.ifBlank { btn.attr("value") }
                if (btnSrc.isNotBlank() && 
                    !btnSrc.startsWith("#") && 
                    !btnSrc.startsWith("javascript") &&
                    !btnSrc.contains("disqus", true) &&
                    !btnSrc.endsWith(".js", true)
                ) {
                    val label = btn.text().trim().ifBlank { "Servidor Alternativo" }
                    embedCandidates.add(fixUrl(btnSrc) to label)
                }
            }

            // 3. ResoluÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o de cada candidato a player/iframe
            for ((embedUrl, label) in embedCandidates.distinctBy { it.first }) {
                // v122: fluxo recap -> rcPreloadPlayer -> __RC__/proxy (TLS-bound) -> proxy local.
                // A URL real do vÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­deo (__RC__/proxy?src=p12-common-sign...) sÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³ funciona no TLS do navegador/WebView que emitiu o cf_clearance
                // (VLC/curl/OkHttp direto = 520, provado via browser-harness). O WebView captura o recap e
                // serve a URL local 127.0.0.1 ao ExoPlayer, que consome via proxy.
                if (embedUrl.contains("server.php", true) && embedUrl.contains("vid=", true)) {
                    var localProxyUrl = WebViewStreamProxy.captureAndServe(embedUrl)
                    // v125/v126/v127: o iframe pode apontar p/ servidor morto (RCServer27 NXDOMAIN)
                    // ou ineficiente p/ WebView (RCServer01/videos nunca montou em 2 ciclos de
                    // 120s no v125 — click ok mas __RC__/proxy nunca veio). O harness provou que
                    // server=RCFServer2/ondemand monta __RC__/proxy p/ qualquer vid (206).
                    // v126: timeout cortado p/ 45s/captura — 2 tentativas cabem em ~90s dentro do
                    // deadline de 120s do CloudStream e o fallback realmente roda.
                    // v127: força subfolder=ondemand no fallback (series usam videos; só ondemand foi
                    // provado via browser-harness — NAOIDNTFCA → 206 ftypisom).
                    if (localProxyUrl == null && !embedUrl.contains("server=RCFServer2", true)) {
                        var fixedUrl = embedUrl.replace(Regex("server=[^&]+", RegexOption.IGNORE_CASE), "server=RCFServer2")
                        fixedUrl = if (fixedUrl.contains("subfolder=", true)) {
                            fixedUrl.replace(Regex("subfolder=[^&]+", RegexOption.IGNORE_CASE), "subfolder=ondemand")
                        } else {
                            if (fixedUrl.contains("?")) "$fixedUrl&subfolder=ondemand" else "$fixedUrl?subfolder=ondemand"
                        }
                        Log.i(TAG, "[PROXY_LINK] servidor original falhou — tentando RCFServer2/ondemand: $fixedUrl")
                        localProxyUrl = WebViewStreamProxy.captureAndServe(fixedUrl)
                    }
                    if (localProxyUrl != null) {
                        Log.i(TAG, "[PROXY_LINK] emitindo proxy local: $localProxyUrl")
                        callback.invoke(
                            newExtractorLink(
                                source = "RedeCanais AF",
                                name = "RedeCanais AF ($label) — WebView Proxy",
                                url = localProxyUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = embedUrl
                                this.quality = Qualities.P1080.value
                                this.headers = mapOf(
                                    "User-Agent" to CloudflareSolver.lastUserAgent.orEmpty(),
                                    "Referer" to embedUrl
                                )
                            }
                        )
                        foundAny = true
                        continue
                    }
                    Log.w(TAG, "[PROXY_LINK] proxy local falhou para $embedUrl — tentando fluxo normal")
                }
                if (resolveStreamOrExtractor(embedUrl, label, cleanUrl, subtitleCallback, callback, visitedUrls, depth = 0)) {
                    foundAny = true
                }
            }

            // 4. ExtraÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o de streams diretos (.m3u8 / .mp4) no prÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â³prio HTML da pÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡gina principal
            if (extractDirectStreamsFromHtml(html, cleanUrl, "Player Direto", callback)) {
                foundAny = true
            }

        } catch (e: Throwable) {
            Log.e(TAG, "[LOADLINKS_ERROR] url=$cleanUrl err=${e.message}", e)
        }

        Log.i(TAG, "[LOADLINKS_DONE] foundAny=$foundAny")
        return foundAny
    }

    /**
     * ResoluÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o recursiva de embeds, iframes intermediÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡rios, extratores e links diretos.
     */
    private suspend fun resolveStreamOrExtractor(
        url: String,
        serverLabel: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visitedUrls: MutableSet<String>,
        depth: Int = 0
    ): Boolean {
        if (depth > 3 || !visitedUrls.add(url)) {
            return false
        }

        Log.d(TAG, "[RESOLVE_STREAM][Depth $depth] url=$url | server=$serverLabel")

        // v120: intercepta redirect.api?p=<base64> — o player migrou para redecanaistv.af
        // (server.php -> bundle.js -> dt.api -> redirect.api?p=<base64 da URL do player real>)
        if (url.contains("redirect.api", true)) {
            val pMatch = Regex("""[?&]p=([^&]+)""", RegexOption.IGNORE_CASE).find(url)
            val pRaw = pMatch?.groupValues?.getOrNull(1)
            if (!pRaw.isNullOrBlank()) {
                val decoded = tryDecodeBase64OrUrl(pRaw)
                if (decoded.isNotBlank() && decoded != pRaw) {
                    Log.i(TAG, "[REDIRECT_API] redirect.api?p= decodificado: $pRaw -> $decoded")
                    if (resolveStreamOrExtractor(decoded, serverLabel, referer, subtitleCallback, callback, visitedUrls, depth + 1)) {
                        return true
                    }
                    return false
                }
            }
        }

        var success = false

        // 1. ResoluÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o Direta de ParÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢metros de Stream do RedeCanais AF (player3/server.php)
        val vid = Regex("""[?&]vid=([^&]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)
        val serverParam = Regex("""[?&]server=([^&]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1) ?: "RCServer"
        val subfolder = Regex("""[?&]subfolder=([^&]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1) ?: "ondemand"

        if (!vid.isNullOrBlank()) {
            val directStreamCandidates = listOf(
                "https://s1.redecanais.af/$subfolder/$vid.mp4",
                "https://${serverParam.lowercase()}.redecanais.af/$subfolder/$vid.mp4",
                "https://s1.redecanais.af/hls/$vid.m3u8"
            )
            for (candidate in directStreamCandidates) {
                // v119: usa o retorno real — sem falso positivo (NXDOMAIN aborta e retorna false)
                if (emitExtractorLink(
                        streamUrl = candidate,
                        name = "RedeCanais AF ($serverParam)",
                        referer = url,
                        isM3u8 = candidate.contains(".m3u8"),
                        callback = callback
                    )
                ) {
                    success = true
                }
            }
        }

        // 2. AnÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡lise e decodificaÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o de parÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢metros diretos de URL (ex: ?v=..., ?url=..., ?file=...)
        val extractedParam = extractParamAndDecode(url)
        if (!extractedParam.isNullOrBlank() && extractedParam != url) {
            if (extractedParam.contains(".m3u8", true) || extractedParam.contains(".mp4", true)) {
                val isM3u8 = extractedParam.contains(".m3u8", true)
                if (emitExtractorLink(
                        streamUrl = extractedParam,
                        name = "RedeCanais AF ($serverLabel)",
                        referer = url,
                        isM3u8 = isM3u8,
                        callback = callback
                    )
                ) {
                    success = true
                }
            } else {
                if (resolveStreamOrExtractor(extractedParam, serverLabel, url, subtitleCallback, callback, visitedUrls, depth + 1)) {
                    success = true
                }
            }
        }

        // 3. DelegaÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o para extratores nativos do CloudStream (Streamtape, DoodStream, MixDrop, etc.)
        if (!url.contains("redecanais", true) && !url.contains("player3", true) && !url.contains("server.php", true)) {
            try {
                if (loadExtractor(url, referer, subtitleCallback, callback)) {
                    Log.i(TAG, "[RESOLVE_STREAM] loadExtractor com sucesso para: $url")
                    return true
                }
            } catch (_: Throwable) {}
        }

        // 3. Se for player interno/intermediÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡rio (PHP Melody / player3.php / play.php / embed.php)
        try {
            val responseText = fetchHtmlSafe(url, referer)
            if (responseText.isNotBlank()) {
                if (extractDirectStreamsFromHtml(responseText, url, serverLabel, callback)) {
                    success = true
                }

                val innerIframes = Jsoup.parse(responseText, url).select("iframe[src], iframe[data-src], iframe[data-lazy-src]")
                for (iframe in innerIframes) {
                    val innerSrc = iframe.attr("data-src")
                        .ifBlank { iframe.attr("data-lazy-src") }
                        .ifBlank { iframe.attr("src") }

                    if (innerSrc.isNotBlank() && !innerSrc.contains("about:blank", true) && !innerSrc.contains("recaptcha", true)) {
                        val nestedUrl = fixUrl(innerSrc)
                        if (resolveStreamOrExtractor(nestedUrl, "$serverLabel -> Aninhado", url, subtitleCallback, callback, visitedUrls, depth + 1)) {
                            success = true
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[RESOLVE_STREAM_FAIL] url=$url err=${e.message}")
        }

        return success
    }

        private fun isValidStreamUrl(url: String): Boolean {
        if (url.isBlank() || url.startsWith("javascript:", true) || url.startsWith("#")) return false
        val clean = url.substringBefore("?").lowercase()
        val fullLower = url.lowercase()
        val decodedLower = try { java.net.URLDecoder.decode(url, "UTF-8").lowercase() } catch (_: Throwable) { fullLower }
        val decodedClean = try { java.net.URLDecoder.decode(clean, "UTF-8").lowercase() } catch (_: Throwable) { clean }

        // 0. Fast-path proxy __RC__/proxy capturado via browser-harness mesma sessao (206 provado)
        if (fullLower.contains("__rc__/proxy") || fullLower.contains("/proxy?src=") || fullLower.contains("p12-common-sign")) {
            if (decodedLower.contains(".mp4") || decodedLower.contains(".m3u8") || decodedLower.contains(".mkv") || decodedLower.contains(".mpd") || decodedLower.contains(".webm")) {
                return true
            }
        }

        // 1. Rejeicao de assets estaticos
        if (clean.endsWith(".js") || clean.endsWith(".css") || clean.endsWith(".html") ||
            clean.endsWith(".htm") || clean.endsWith(".json") || clean.endsWith(".xml") ||
            clean.endsWith(".jpg") || clean.endsWith(".png") || clean.endsWith(".gif") ||
            clean.endsWith(".svg") || clean.endsWith(".webp") || clean.endsWith(".woff") ||
            clean.endsWith(".woff2") || clean.endsWith(".ttf")) {
            if (!(decodedClean.endsWith(".mp4") || decodedClean.endsWith(".m3u8") || decodedClean.endsWith(".mkv") || decodedClean.endsWith(".mpd") || decodedClean.endsWith(".webm"))) {
                return false
            }
        }

        // 2. Rejeicao widgets/trackers/paginas intermediarias
        if (fullLower.contains("disqus") || fullLower.contains("chatango") ||
            fullLower.contains("facebook") || fullLower.contains("twitter") ||
            fullLower.contains("google-analytics") || fullLower.contains("googletagmanager") ||
            fullLower.contains("recaptcha") || fullLower.contains("turnstile") ||
            fullLower.contains("server.php") || fullLower.contains("player.php") ||
            fullLower.contains("embed.php") || fullLower.contains("play.php") ||
            fullLower.contains("browse-")) {
            return false
        }

        // 3. Whitelist streams (raw + decoded)
        val isDirectMediaExt = clean.endsWith(".mp4") || clean.endsWith(".m3u8") ||
                               clean.endsWith(".mpd") || clean.endsWith(".mkv") || clean.endsWith(".webm") ||
                               decodedClean.endsWith(".mp4") || decodedClean.endsWith(".m3u8") ||
                               decodedClean.endsWith(".mpd") || decodedClean.endsWith(".mkv") || decodedClean.endsWith(".webm")
        val isRecognizedStreamUrl = fullLower.contains(".m3u8?") || fullLower.contains(".mp4?") ||
                                    decodedLower.contains(".m3u8?") || decodedLower.contains(".mp4?") ||
                                    decodedLower.contains(".m3u8&") || decodedLower.contains(".mp4&") ||
                                    fullLower.contains("/hls/") || fullLower.contains("/ondemand/") ||
                                    decodedLower.contains("/hls/") || decodedLower.contains("/ondemand/") ||
                                    fullLower.contains("/stream/") || decodedLower.contains("/stream/") ||
                                    fullLower.contains("googlevideo.com") || decodedLower.contains("googlevideo.com") ||
                                    (fullLower.contains("storage.googleapis.com") && !clean.endsWith(".jpg")) ||
                                    (decodedLower.contains("storage.googleapis.com") && !decodedClean.endsWith(".jpg")) ||
                                    decodedLower.contains("googleusercontent.com") ||
                                    decodedLower.contains("neosoro.gq")

        return isDirectMediaExt || isRecognizedStreamUrl
    }

    private suspend fun extractDirectStreamsFromHtml(
        html: String,
        referer: String,
        serverLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        // v105: Regex direto solicitado na missÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o + padrÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Âµes existentes (jwplayer/file/source/src/hls)
        val streamPatterns = listOf(
            Regex("""https?://[^\s"'"'"]+/__RC__/proxy\?src=[^\s"'"'"]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^\s"'\\]+\.(?:m3u8|mp4)(?:\?[^\s"'\\]*)?)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|source|src|stream|hls|video)\s*[:=]\s*["'](https?://[^\s"'\\]+)["']""", RegexOption.IGNORE_CASE),
            Regex("""\{[^}]*file\s*:\s*["'](https?://[^\s"'\\]+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<source[^>]+src=["'](https?://[^\s"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<video[^>]+src=["'](https?://[^\s"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["'](https?://[^\s"']+)["']""", RegexOption.IGNORE_CASE),
            // v119: src do vídeo capturado pelo WebView (data-cs-video-src) nas páginas de player
            Regex("""data-cs-video-src=["'](https?://[^\s"']+)["']""", RegexOption.IGNORE_CASE)
        )

        val candidates = mutableSetOf<String>()
        for (pattern in streamPatterns) {
            pattern.findAll(html).forEach { match ->
                val rawGroup = if (match.groupValues.size > 1) match.groupValues[1] else ""
                val raw = (if (rawGroup.isNotBlank()) rawGroup else match.value)
                    .replace("\\/", "/")
                    // v119: o outerHTML serializa & como &amp; — o ExoPlayer precisa de & real
                    .replace("&amp;", "&")
                    .trim().trimEnd('"', '\'', ',', ';')
                if (isValidStreamUrl(raw)) {
                    candidates.add(raw)
                }
            }
        }

        for (streamUrl in candidates) {
            val isM3u8 = streamUrl.contains(".m3u8", true)
            // v119: found só é true se o link foi REALMENTE emitido (probe passou ou proxy bypass)
            if (emitExtractorLink(
                    streamUrl = streamUrl,
                    name = "RedeCanais AF ($serverLabel)",
                    referer = referer,
                    isM3u8 = isM3u8,
                    callback = callback
                )
            ) {
                found = true
            }
        }

        return found
    }

    private fun extractParamAndDecode(url: String): String? {
        val paramRegex = Regex("""[?&](?:v|url|file|src|stream)=([^&]+)""", RegexOption.IGNORE_CASE)
        val rawParam = paramRegex.find(url)?.groupValues?.getOrNull(1) ?: return null

        return tryDecodeBase64OrUrl(rawParam)
    }

    private fun tryDecodeBase64OrUrl(input: String): String {
        var text = input
        try {
            text = java.net.URLDecoder.decode(text, "UTF-8")
        } catch (_: Throwable) {}

        if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
            return text
        }

        try {
            val decodedBytes = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
            val decoded = String(decodedBytes, Charsets.UTF_8).trim()
            if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true) || 
                decoded.contains(".m3u8", true) || decoded.contains(".mp4", true)) {
                return decoded
            }
        } catch (_: Throwable) {}

        try {
            val decodedBytes = android.util.Base64.decode(text, android.util.Base64.URL_SAFE)
            val decoded = String(decodedBytes, Charsets.UTF_8).trim()
            if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true) || 
                decoded.contains(".m3u8", true) || decoded.contains(".mp4", true)) {
                return decoded
            }
        } catch (_: Throwable) {}

        return text
    }

    private suspend fun fetchHtmlSafe(url: String, referer: String): String {
        return try {
            val doc = requestDoc(url, referer)
            doc.html()
        } catch (e: Throwable) {
            Log.w(TAG, "[FETCH_HTML_SAFE_ERR] url=$url err=${e.message}")
            ""
        }
    }

    private suspend fun probeMediaStream(url: String, headers: Map<String, String>): Boolean {
        // v118: proxy __RC__/proxy validado via browser-harness/WebView 206 (mesma sessao cf_clearance+RCSESS)
        // OkHttp/CloudflareKiller nunca passa (JA3), bypass probe para emitir direto ao ExoPlayer
        if (url.contains("__RC__/proxy", true) || url.contains("p12-common-sign", true) || url.contains("/proxy?src=", true)) {
            Log.i(TAG, "[MEDIA_PROBE_BYPASS] proxy __RC__ -> bypass probe, emitindo direto: $url")
            return true
        }
        return try {
            val probeHeaders = headers.toMutableMap()
            probeHeaders["Range"] = "bytes=0-1024"
            val refererPresent = probeHeaders.containsKey("Referer")
            val uaPresent = probeHeaders.containsKey("User-Agent")
            val cookiePresent = probeHeaders.containsKey("Cookie")

            val res = app.get(url, headers = probeHeaders, timeout = 6)
            val status = res.code
            val contentType = res.headers["Content-Type"] ?: res.headers["content-type"] ?: ""
            val contentLength = res.headers["Content-Length"] ?: res.headers["content-length"] ?: ""
            val acceptRanges = res.headers["Accept-Ranges"] ?: res.headers["accept-ranges"] ?: ""
            val contentRange = res.headers["Content-Range"] ?: res.headers["content-range"] ?: ""
            val bodySnippet = try { res.body.string().take(100) } catch (_: Throwable) { "" }
            val isHtml = bodySnippet.contains("<!DOCTYPE", true) || bodySnippet.contains("<html", true)

            Log.i(
                TAG,
                "[MEDIA_PROBE] url=$url | status=$status | contentType=$contentType | len=$contentLength | " +
                "acceptRanges=$acceptRanges | contentRange=$contentRange | isHtml=$isHtml | " +
                "referer_present=$refererPresent | user_agent_present=$uaPresent | cookie_present=$cookiePresent | range_requested=true"
            )

            if (isHtml || status == 403 || status == 520 || status == 404) {
                Log.w(TAG, "[MEDIA_PROBE_REJECTED] Resposta nÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â© mÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­dia real: status=$status isHtml=$isHtml")
                false
            } else {
                true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[MEDIA_PROBE_WARN] Erro ao testar stream: url=$url err=${e.message}")
            false
        }
    }

    private suspend fun emitExtractorLink(
        streamUrl: String,
        name: String,
        referer: String,
        isM3u8: Boolean,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!isValidStreamUrl(streamUrl)) {
            Log.w(TAG, "[EMIT_LINK_IGNORED] URL nÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â© um stream de vÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­deo vÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¡lido: $streamUrl")
            return false
        }

        val userAgent = CloudflareSolver.lastUserAgent 
            ?: WebViewResolver.webViewUserAgent 
            ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

        val cookies = try {
            CookieManager.getInstance().getCookie(referer)
                ?: CookieManager.getInstance().getCookie(mainUrl)
                ?: ""
        } catch (_: Throwable) { "" }

        val headers = mutableMapOf(
            "Referer" to referer,
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        if (cookies.isNotBlank()) {
            headers["Cookie"] = cookies
        }

        try {
            val originHost = java.net.URI(referer).let { "${it.scheme}://${it.host}" }
            headers["Origin"] = originHost
        } catch (_: Throwable) {}

        // ValidaÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o ativa da mÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­dia antes de entregar ao ExoPlayer
        // v118: bypass probe para proxy (206 provado via browser-harness mesma sessao)
        val isProxy = streamUrl.contains("__RC__/proxy", true) || streamUrl.contains("p12-common-sign", true) || streamUrl.contains("/proxy?src=", true)
        if (isProxy) {
            Log.i(TAG, "[EMIT_LINK_BYPASS_PROBE] proxy __RC__ validado via WebView 206, emitindo direto sem probe OkHttp: $streamUrl")
        } else {
            val probePassed = probeMediaStream(streamUrl, headers)
            if (!probePassed) {
            Log.e(TAG, "[EMIT_LINK_ABORTED] O servidor de mÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â­dia rejeitou a requisiÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â§ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â£o do stream: $streamUrl")
                return false
            }
        }

        val refererSet = headers.containsKey("Referer")
        val uaSet = headers.containsKey("User-Agent")
        val cookieSet = headers.containsKey("Cookie")
        val originSet = headers.containsKey("Origin")

        Log.i(
            TAG,
            "[EXTRACTOR_LINK] type=${if (isM3u8) "M3U8" else "VIDEO"} | name=$name | " +
            "referer_present=$refererSet | user_agent_present=$uaSet | cookie_present=$cookieSet | " +
            "origin_present=$originSet | headers_count=${headers.size}"
        )

        callback.invoke(
            newExtractorLink(
                source = "RedeCanais AF",
                name = name,
                url = streamUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.headers = headers
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }
}
