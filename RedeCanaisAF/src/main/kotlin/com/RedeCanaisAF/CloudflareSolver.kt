package com.RedeCanaisAF

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object CloudflareSolver {
    private const val TAG = "RedeCanaisAF-Trace"
    private val nonCatalogHosts = setOf(
        "static.cloudflareinsights.com",
        "cdnjs.cloudflare.com",
        "ajax.googleapis.com",
        "fonts.googleapis.com",
        "fonts.gstatic.com",
        "netdna.bootstrapcdn.com",
        "acscdn.com"
    )
    var lastUserAgent: String? = null

    // v130: cache de HTML capturado por URL dentro da MESMA sessão TLS do diálogo. O Turnstile
    // managed resolve UMA vez no WebView do diálogo; navegar para as outras URLs do catálogo no
    // MESMO WebView carrega SEM re-challenge (~2-5s por página, vs ~70s por diálogo). Os REQs
    // seguintes leem o cache e não reabrem diálogo — fica dentro do deadline de 120s do framework.
    private val capturedHtmlByUrl = ConcurrentHashMap<String, String>()
    @Volatile
    private var catalogUrls: List<String> = emptyList()

    fun setCatalogUrls(urls: List<String>) {
        if (catalogUrls != urls) {
            catalogUrls = urls
            capturedHtmlByUrl.clear()
            Log.i(TAG, "[CF] catalogUrls setadas (${urls.size}) — cache de HTML limpo")
        }
    }

    fun capturedHtml(url: String): String? = capturedHtmlByUrl[url]

    // v128: HTML capturado do WebView do diálogo interativo (a página alvo carrega na MESMA sessão
    // TLS que resolveu o Turnstile). O Turnstile managed de redecanais.af resolve SEM emitir
    // cf_clearance no CookieManager — o token fica na sessão do WebView — então um WebView NOVO
    // (solveAndGetHtml) falha de novo. Reutilizar este HTML evita o segundo WebView.
    @Volatile
    var lastSolvedHtml: String? = null

    private fun shouldBlockResource(request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) return false
        return request.url.host?.lowercase() in nonCatalogHosts
    }

    private fun emptyResource(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    // v128: serializa os diálogos de verificação — o Turnstile managed de redecanais.af SÓ resolve
    // quando o WebView do diálogo tem foco/visibilidade. Com 4 REQs paralelos (uma categoria cada)
    // abrindo 4 diálogos ao mesmo tempo, NENHUM resolve (todos target_page_loaded=false após 60s).
    // Um diálogo por vez + cf_clearance compartilhado no CookieManager resolve o catálogo inteiro.
    private val interactiveMutex = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solveInteractive(url: String, timeoutMs: Long = 45000L, force: Boolean = false): String? {
        // v130: cache da sessão do diálogo — se outro REQ já navegou o WebView do diálogo para esta
        // URL (mesma sessão TLS), devolve o HTML capturado sem reabrir diálogo (rápido, ~0ms).
        capturedHtmlByUrl[url]?.let {
            Log.i(TAG, "[CF] HTML do cache da sessão (outro REQ capturou) url=$url len=${it.length}")
            return it
        }
        return interactiveMutex.withLock {
            // v130: o REQ#1 (primeiro a pegar o lock) navega o MESMO WebView por TODAS as URLs do
            // catálogo antes de soltar o lock. Os REQs 2-4 que esperaram o mutex encontram o HTML da
            // própria URL já no cache — sem abrir outro diálogo (senão: +70s cada, estoura o deadline).
            capturedHtmlByUrl[url]?.let {
                Log.i(TAG, "[CF] HTML do cache dentro do lock (REQ#1 navegou por todas) url=$url len=${it.length}")
                return@withLock it
            }
            // v128: dentro do lock, re-checa cf_clearance — outro REQ pode ter resolvido o Turnstile
            // enquanto este aguardava o mutex; com o cookie válido, não precisa abrir outro diálogo.
            val cookiesNow = CookieManager.getInstance().getCookie(url) ?: ""
            if (cookiesNow.contains("cf_clearance")) {
                Log.i(TAG, "[CF] cf_clearance presente após lock (outro REQ resolveu) — pulando diálogo")
                return@withLock ""
            }
            // v130: o primeiro diálogo a resolver navega o MESMO WebView pelas outras URLs do
            // catálogo (mesma sessão TLS, sem re-challenge). Retorna o HTML desta URL.
            val extras = catalogUrls.filter { it != url && capturedHtmlByUrl[it] == null }
            val result = solveInteractiveLocked(url, timeoutMs, force, extras)
            if (result == null) {
                capturedHtmlByUrl[url]?.let { return@withLock it }
            }
            result
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solveInteractiveLocked(url: String, timeoutMs: Long, force: Boolean, extraUrls: List<String> = emptyList()): String? {
        val initialCookies = CookieManager.getInstance().getCookie(url) ?: ""
        val hasClearanceBefore = initialCookies.contains("cf_clearance")
        Log.d(TAG, "[CF] clearance_present=$hasClearanceBefore (before interactive)")

        // v122: com force=true (chamado após solveAndGetHtml falhar), o cf_clearance existente pode
        // estar INVALIDADO pelo IP dinâmico — não confia nele, abre o diálogo mesmo assim.
        if (hasClearanceBefore && !force) {
            return ""
        }

        val activity: Activity? = CommonActivity.activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "[CF] Activity não disponível para abrir WebView interativo")
            return null
        }

        Log.i(TAG, "[CF] challenge_started url=$url | extras=${extraUrls.size}")
        val isSolved = AtomicBoolean(false)
        val targetLoaded = AtomicBoolean(false)
        // v128: sinaliza que o HTML da página alvo foi capturado (ou falhou) — o poller aguarda
        // antes de destruir o WebView, senão o callback do evaluateJavascript nunca roda.
        val htmlCaptureDone = CompletableDeferred<Boolean>()
        // v130: o primeiro diálogo a resolver navega o MESMO WebView (sessão TLS que resolveu o
        // Turnstile) pelas demais URLs do catálogo — sem re-challenge, ~2-5s por página. A URL da
        // captura atual acompanha o onPageFinished (o challenge serve a URL alvo no finishedUrl).
        var currentUrl = url
        val pendingExtras = extraUrls.toMutableList()
        var dialog: AlertDialog? = null

        withContext(Dispatchers.Main) {
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                val wv = WebView(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        blockNetworkImage = false
                        loadsImagesAutomatically = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        javaScriptCanOpenWindowsAutomatically = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    lastUserAgent = settings.userAgentString
                    Log.i(TAG, "[CF] WebView User-Agent gravado: $lastUserAgent")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            CookieManager.getInstance().flush()
                            val cookies = CookieManager.getInstance().getCookie(finishedUrl ?: url) ?: ""
                            val hasClearance = cookies.contains("cf_clearance")
                            val isTarget = finishedUrl?.contains("redecanais.af") == true && 
                                    !finishedUrl.contains("challenge-platform") && 
                                    finishedUrl != "about:blank"
                            
                            Log.d(TAG, "[CF] onPageFinished url=$finishedUrl | clearance=$hasClearance | target_page_loaded=$isTarget")

                            if (hasClearance || isTarget) {
                                isSolved.set(true)
                                targetLoaded.set(isTarget)
                                Log.i(TAG, "[CF] Resolvido! clearance_present=$hasClearance | target_page_loaded=$isTarget")
                                // v128: captura o HTML da página alvo do MESMO WebView (sessão TLS que
                                // resolveu o Turnstile) — o CF_HTML com WebView novo NÃO reutiliza o token.
                                // v129: o onPageFinished dispara ANTES do JS popular os cards (fetch/lazy).
                                // Faz polling por .pm-video-thumb (até ~12s) antes de capturar o outerHTML.
                                if (isTarget) {
                                    // v129: o Cloudflare serve a página de challenge (title "Um momento…",
                                    // "Executando verificação de segurança") com a URL alvo original no
                                    // onPageFinished — capturar aí devolve o HTML do desafio (rawCards=0).
                                    // O Turnstile managed resolve em 30-90s e SÓ ENTÃO o redirect real para
                                    // o catálogo acontece (cards .pm-video-thumb aparecem). Polling de até
                                    // ~70s, capturando apenas quando o catálogo real estiver no DOM.
                                    // v130: a URL atual da captura acompanha o finishedUrl — ao navegar o
                                    // MESMO WebView pelas URLs extras do catálogo, cada página dispara seu
                                    // próprio onPageFinished/polling e é capturada sob a própria URL.
                                    currentUrl = finishedUrl ?: url
                                    var pollAttempts = 0
                                    fun pollAndCapture(cv: WebView?) {
                                        cv?.evaluateJavascript(
                                            "(function() { var c = document.querySelector('.pm-video-thumb, .pm-li-video'); return JSON.stringify({cards: document.querySelectorAll('.pm-video-thumb, .pm-li-video').length, links: document.querySelectorAll('a[href]').length, title: document.title || '', firstCard: (c ? c.outerHTML.substring(0, 400) : ''), body: (document.body ? document.body.innerText : '').substring(0, 120)}); })();"
                                        ) { diagJson ->
                                            val diag = diagJson?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
                                            pollAttempts++
                                            val cardCount = Regex("\"cards\":(\\d+)").find(diag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                            Log.d(TAG, "[CF] polling cards=$cardCount (tentativa $pollAttempts) | diag=$diag | url=$currentUrl")
                                            if (cardCount > 0) {
                                                cv?.evaluateJavascript(
                                                    "(function() { return document.getElementsByTagName('html')[0].outerHTML; })();"
                                                ) { html ->
                                                    if (!html.isNullOrBlank() && html != "null") {
                                                        val decoded = html.removeSurrounding("\"")
                                                            .replace("\\u003C", "<")
                                                            .replace("\\u003E", ">")
                                                            .replace("\\\"", "\"")
                                                            .replace("\\n", "\n")
                                                            .replace("\\r", "\r")
                                                        lastSolvedHtml = decoded
                                                        capturedHtmlByUrl[currentUrl] = decoded
                                                        Log.i(TAG, "[CF] HTML alvo capturado do diálogo: len=${decoded.length} | cards=$cardCount | url=$currentUrl")
                                                    }
                                                    // v130: a sessão TLS do WebView do diálogo já resolveu o
                                                    // Turnstile — navegar para as outras URLs do catálogo no
                                                    // MESMO WebView carrega SEM re-challenge (~2-5s). Só
                                                    // encerra o diálogo quando todas as URLs foram capturadas.
                                                    val next = pendingExtras.firstOrNull()
                                                    if (next != null) {
                                                        pendingExtras.remove(next)
                                                        Log.i(TAG, "[CF] Navegando no MESMO WebView para próxima URL do catálogo: $next")
                                                        cv?.loadUrl(next)
                                                    } else {
                                                        htmlCaptureDone.complete(true)
                                                        dialog?.dismiss()
                                                    }
                                                }
                                            } else if (pollAttempts >= 140) {
                                                Log.w(TAG, "[CF] polling esgotado (70s) sem cards reais — desistindo do diálogo")
                                                htmlCaptureDone.complete(true)
                                                dialog?.dismiss()
                                            } else {
                                                cv?.postDelayed({ pollAndCapture(cv) }, 500)
                                            }
                                        }
                                    }
                                    pollAndCapture(view)
                                } else {
                                    htmlCaptureDone.complete(true)
                                    dialog?.dismiss()
                                }
                            } else {
                                view?.evaluateJavascript(
                                    """(function() {
                                        try {
                                            const cb = document.querySelector('input[type="checkbox"], .ctp-checkbox-label');
                                            if (cb) cb.click();
                                        } catch (_) {}
                                    })();""".trimIndent(), null
                                )
                            }
                        }
                    }
                }

                dialog = AlertDialog.Builder(activity)
                    .setTitle("RedeCanais (AF) - Verificação")
                    .setMessage("Toque na caixinha de verificação para liberar o catálogo:")
                    .setView(wv)
                    .setNegativeButton("Cancelar") { d, _ ->
                        d.dismiss()
                    }
                    .setOnDismissListener {
                        try {
                            wv.stopLoading()
                            wv.destroy()
                        } catch (_: Throwable) {}
                    }
                    .create()

                dialog?.show()
                wv.loadUrl(url)
            } catch (e: Throwable) {
                Log.e(TAG, "[CF] Erro ao abrir diálogo interativo: ${e.message}")
            }
        }

        withTimeoutOrNull(timeoutMs) {
            while (!isSolved.get()) {
                CookieManager.getInstance().flush()
                val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                if (cookies.contains("cf_clearance")) {
                    isSolved.set(true)
                    Log.i(TAG, "[CF] clearance_present=true (poller)")
                    break
                }
                delay(500)
            }
            // v128: o onPageFinished dispara isSolved=true e agenda o evaluateJavascript na main
            // thread. Sem esta espera, o dismiss+destroy abaixo (também main thread) poderia rodar
            // ANTES do callback do evaluateJavascript — a captura do HTML seria perdida.
            // v129: o Turnstile managed resolve em 30-90s (REQ#7 resolveu em ~35s). O polling de cards
            // vai até ~70s; o await precisa cobrir isso, senão o dismiss+destroy mata o WebView antes
            // do redirect real para o catálogo (título "Um momento…" nunca vira a página real).
            // v130: o diálogo navega o MESMO WebView pelas URLs extras do catálogo (sessão TLS já
            // resolvida) — 70s de polling + ~3s por página extra; await ampliado para cobrir tudo.
            if (!htmlCaptureDone.isCompleted) {
                withTimeoutOrNull(95000) { htmlCaptureDone.await() }
            }
            true
        }

        withContext(Dispatchers.Main) {
            try {
                dialog?.dismiss()
            } catch (_: Throwable) {}
        }

        val finalCookies = CookieManager.getInstance().getCookie(url) ?: ""
        val finalClearance = finalCookies.contains("cf_clearance")
        Log.i(TAG, "[CF] clearance_present=$finalClearance | target_page_loaded=${targetLoaded.get()} (finished)")
        // v130: retorna o HTML da URL PEDIDA (o WebView pode ter navegado para as URLs extras do
        // catálogo depois de capturar esta — lastSolvedHtml seria o da última navegação).
        val captured = capturedHtmlByUrl[url] ?: lastSolvedHtml
        return if (isSolved.get() || finalClearance) (captured ?: "") else null
    }

    suspend fun solveAndGetHtml(url: String, timeoutMs: Long = 15000L): String? {
        val activity: Activity? = CommonActivity.activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "[CF_HTML] Activity não disponível para extração via WebView")
            return null
        }

        Log.i(TAG, "[CF_HTML] Iniciando extração de HTML via WebView para $url")
        var resultHtml: String? = null
        val isDone = AtomicBoolean(false)
        var wvVar: WebView? = null
        val rootLayout = activity.findViewById<ViewGroup>(android.R.id.content)

        withContext(Dispatchers.Main) {
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                val wv = WebView(activity).apply {
                    visibility = android.view.View.INVISIBLE
                    // v121: viewport REAL (720x1280) posicionado FORA da tela — o Turnstile managed de
                    // redecanaistv.af não renderiza o widget em viewport 1x1 (TURNSTILE NODES: 0 confirmado
                    // via harness). Chrome desktop com viewport 1920x1080 emitiu cf_clearance em ~1min.
                    val density = resources.displayMetrics.density
                    val wvW = (720 * density).toInt()
                    val wvH = (1280 * density).toInt()
                    layoutParams = FrameLayout.LayoutParams(wvW, wvH).apply {
                        leftMargin = -(wvW + 100)
                        topMargin = -(wvH + 100)
                    }

                    cookieManager.setAcceptThirdPartyCookies(this, true)
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                // v109: imagens habilitadas para (a) o widget Turnstile completar o
                                // challenge e (b) as capas serem baixadas e embutidas como data-cs-poster
                                blockNetworkImage = false
                                loadsImagesAutomatically = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                javaScriptCanOpenWindowsAutomatically = true
                                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            }
                    addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface
                        fun processHTML(html: String) {
                            val embeddedPosters = Regex("data-cs-poster=", RegexOption.IGNORE_CASE)
                                .findAll(html).count()
                            Log.i(TAG, "[CF_HTML] HTML capturado via JS interface: len=${html.length} | capasIncorporadas=$embeddedPosters")
                            resultHtml = html
                            isDone.set(true)
                        }
                    }, "HTMLOUT")

                    webViewClient = object : WebViewClient() {
                        private var extractionAttempts = 0
                        private var extractionStarted = false
                        private var challengeReloads = 0

                        private fun extractWhenPageIsReady(view: WebView?) {
                            // v121: cap maior (300 x 400ms ~= 120s) — Turnstile managed de redecanaistv.af
                            // com viewport real pode levar 30-90s para completar sem interação visível
                            if (view == null || isDone.get() || extractionAttempts++ >= 300) return

                            view.evaluateJavascript(
                                """(function() {
                                    const title = document.title || '';
                                    const bodyText = document.body ? document.body.innerText : '';
                                    const isChallenge = /Just a moment|Um momento|Aguarde|Verificando|challenge-platform|cf-chl-|Checking your browser|Attention Required|Cloudflare|Error code 520|Error code 522|Error code 524|Web server is returning/i.test(title) ||
                                                        /challenge-platform|cf-turnstile|cf-challenge|cf-error-details/i.test(document.documentElement.outerHTML.substring(0, 3000));
                                    if (isChallenge) return '';

                                    // v119: página de player (server.php/player3) — detecta antes do filtro de links,
                                    // pois o player pode ter poucos <a> mas o <video src> dinâmico é o alvo real
                                    const isPlayerPage = !!document.querySelector('video, .jwplayer, #jwplayer, .video-js, iframe[src*="server.php"]') ||
                                                         /server\.php|player3|player\.php|embed\.php|play\.php/i.test(location.href);
                                    if (isPlayerPage) return 'player';

                                    const linksCount = document.querySelectorAll('a[href]').length;
                                    if (linksCount < 15) return '';

                                    const catalogCount = document.querySelectorAll('.pm-video-thumb, .pm-li-video').length;
                                    const hasDetailElements = !!document.querySelector("h1.entry-title, h1.pm-video-attr-title, .pm-video-title, .pm-video-watch-wrap, .pm-video-description, #pm-video-description");

                                    if (catalogCount > 0) return 'catalog';
                                    if (hasDetailElements) return 'detail';
                                    return '';
                                })();""".trimIndent()
                            ) { pageTypeJson ->
                                if (isDone.get()) return@evaluateJavascript

                                val pageType = pageTypeJson.orEmpty().removeSurrounding("\"")
                                if (pageType.isNotBlank() && pageType != "null" && !extractionStarted) {
                                    extractionStarted = true
                                    Log.i(TAG, "[CF_HTML] página pronta cedo: tipo=$pageType | tentativas=$extractionAttempts")
                                    // v109: SEMPRE tenta incorporar as capas como data-cs-poster
                                    // (catálogo E detalhe) — o Coil não consegue baixar do Cloudflare (JA3)
                                    if (pageType == "detail" || url.contains("server.php") || url.contains("player") || url.contains(".html")) {
                                        val isPlayerPage = url.contains("server.php") || url.contains("player3") || url.contains("player.php") || url.contains("embed.php") || url.contains("play.php")
                                        if (isPlayerPage) {
                                            // v119: página de player — espera o src do vídeo aparecer (injetado pelo video.js após dt.api)
                                            view.evaluateJavascript(
                                                """(function() {
                                                    const waitForVideo = async () => {
                                                        const deadline = Date.now() + 8000; // até 8s para o dt.api responder
                                                        while (Date.now() < deadline) {
                                                            let src = '';
                                                            try {
                                                                const v = document.querySelector('video');
                                                                if (v) src = v.currentSrc || v.src || '';
                                                                if (!src && window.jwplayer) {
                                                                    try {
                                                                        const pl = jwplayer().getPlaylist();
                                                                        if (pl && pl[0] && pl[0].file) src = pl[0].file;
                                                                    } catch (_) {}
                                                                }
                                                            } catch (_) {}
                                                            if (src && /^https?:/.test(src)) {
                                                                document.documentElement.setAttribute('data-cs-video-src', src);
                                                                break;
                                                            }
                                                            // tenta buscar no outerHTML também (__RC__/proxy)
                                                            const html = document.documentElement.outerHTML;
                                                            if (html.includes('__RC__/proxy') || html.includes('p12-common-sign')) {
                                                                break;
                                                            }
                                                            await new Promise(r => setTimeout(r, 400));
                                                        }
                                                        HTMLOUT.processHTML(document.documentElement.outerHTML);
                                                    };
                                                    // inicia async — não bloqueia o WebView
                                                    waitForVideo();
                                                    // fallback timeout de segurança: captura após 8s mesmo sem vídeo
                                                    setTimeout(() => {
                                                        HTMLOUT.processHTML(document.documentElement.outerHTML);
                                                    }, 8500);
                                                })();""".trimIndent(),
                                                null
                                            )
                                        } else {
                                            // v116: detalhe/catálogo — capas com timeout curto
                                            view.evaluateJavascript(
                                                """(function() {
                                                    const toDataUrl = async (el, attempt) => {
                                                        let src = el.tagName === 'META' ? el.getAttribute('content') : (el.getAttribute('data-echo') || el.getAttribute('data-src') || el.getAttribute('src'));
                                                        if (!src || src.startsWith('data:') || /echo-lzld|blank\.gif|pixel\.gif/i.test(src)) return;
                                                        try {
                                                            const absolute = new URL(src, location.href).href;
                                                            const response = await fetch(absolute, { credentials: 'include', cache: 'force-cache' });
                                                            if (!response.ok) return;
                                                            const blob = await response.blob();
                                                            if (!blob.type.startsWith('image/') || blob.size > 5000000) return;
                                                            const dataUrl = await new Promise((resolve, reject) => {
                                                                const reader = new FileReader();
                                                                reader.onload = () => resolve(reader.result);
                                                                reader.onerror = reject;
                                                                reader.readAsDataURL(blob);
                                                            });
                                                            el.setAttribute('data-cs-poster', dataUrl);
                                                        } catch (_) {
                                                            if ((attempt || 0) === 0) toDataUrl(el, 1);
                                                        }
                                                    };
                                                    const targets = Array.from(document.querySelectorAll('.pm-video-thumb img, meta[property="og:image"], .pm-video-watch-wrap img, article img, img[data-echo]')).slice(0, 40);
                                                    const done = Promise.allSettled(targets.map(el => toDataUrl(el)));
                                                    const timeout = new Promise(resolve => setTimeout(resolve, 2000));
                                                    Promise.race([done, timeout]).then(() => HTMLOUT.processHTML(document.documentElement.outerHTML));
                                                })();""".trimIndent(),
                                                null
                                            )
                                        }
                                    } else {
                                        view.evaluateJavascript(
                                            """(function() {
                                                const toDataUrl = async (el, attempt) => {
                                                    let src = el.tagName === 'META' ? el.getAttribute('content') : (el.getAttribute('data-echo') || el.getAttribute('data-src') || el.getAttribute('src'));
                                                    if (!src || src.startsWith('data:') || /echo-lzld|blank\.gif|pixel\.gif/i.test(src)) return;
                                                    try {
                                                        const absolute = new URL(src, location.href).href;
                                                        const response = await fetch(absolute, { credentials: 'include', cache: 'force-cache' });
                                                        if (!response.ok) return;
                                                        const blob = await response.blob();
                                                        if (!blob.type.startsWith('image/') || blob.size > 5000000) return;
                                                        const dataUrl = await new Promise((resolve, reject) => {
                                                            const reader = new FileReader();
                                                            reader.onload = () => resolve(reader.result);
                                                            reader.onerror = reject;
                                                            reader.readAsDataURL(blob);
                                                        });
                                                        el.setAttribute('data-cs-poster', dataUrl);
                                                    } catch (_) {
                                                        // v111: retry 1x se o fetch falhar (rede instável)
                                                        if ((attempt || 0) === 0) toDataUrl(el, 1);
                                                    }
                                                };
                                                const targets = Array.from(document.querySelectorAll('.pm-video-thumb img, meta[property="og:image"], .pm-video-watch-wrap img, article img, img[data-echo]')).slice(0, 40);
                                                const done = Promise.allSettled(targets.map(el => toDataUrl(el)));
                                                // v116: timeout curto — o conteúdo aparece rápido (~5s); capas entram se derem tempo
                                                const timeout = new Promise(resolve => setTimeout(resolve, 2000));
                                                Promise.race([done, timeout]).then(() => HTMLOUT.processHTML(document.documentElement.outerHTML));
                                            })();""".trimIndent(),
                                            null
                                        )
                                    }
                                } else if (!isDone.get()) {
                                    view.postDelayed({ extractWhenPageIsReady(view) }, 400)
                                }
                            }
                        }
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            if (shouldBlockResource(request)) {
                                Log.d(TAG, "[CF_HTML] recurso externo bloqueado: ${request.url.host}")
                                return emptyResource()
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, startedUrl, favicon)
                            // v120: reseta tentativas a cada nova página (Turnstile de redecanaistv.af pode levar >30s)
                            extractionAttempts = 0
                            extractionStarted = false
                            view?.postDelayed({ extractWhenPageIsReady(view) }, 750)
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            CookieManager.getInstance().flush()
                            Log.d(TAG, "[CF_HTML] onPageFinished finishedUrl=$finishedUrl")
                            // Se a captura antecipada já iniciou, aguarda a tentativa curta
                            // de incorporar capas em vez de sobrescrever o DOM imediatamente.
                            if (extractionStarted && !isDone.get()) return
                            // Injeta JS para extrair o HTML desofuscado pelo motor V8 do Chromium
                            view?.evaluateJavascript(
                                "(function() { return document.getElementsByTagName('html')[0].outerHTML; })();"
                            ) { html ->
                                if (!html.isNullOrBlank() && html != "null") {
                                    val unquoted = html.removeSurrounding("\"")
                                        .replace("\\u003C", "<")
                                        .replace("\\u003E", ">")
                                        .replace("\\\"", "\"")
                                        .replace("\\n", "\n")
                                        .replace("\\r", "\r")
                                    
                                    val isChallenge = unquoted.contains("Just a moment...", true) || 
                                                      unquoted.contains("Um momento", true) ||
                                                      unquoted.contains("Error code 520", true) ||
                                                      unquoted.contains("Error code 522", true) ||
                                                      unquoted.contains("Error code 524", true) ||
                                                      unquoted.contains("Web server is returning", true) ||
                                                      unquoted.contains("cf-browser-verification", true) ||
                                                      unquoted.contains("cf-error-details", true)
                                     
                                     val hasValidStructure = unquoted.contains("pm-video", true) || 
                                                             unquoted.contains("pm-video-description", true) ||
                                                             unquoted.contains("pm-video-thumb", true) ||
                                                             unquoted.contains("entry-title", true) ||
                                                             // v119: página de player (server.php) — não tem pm-video/entry-title
                                                             unquoted.contains("jwplayer", true) ||
                                                             unquoted.contains("<video", true) ||
                                                             unquoted.contains("__RC__/proxy", true) ||
                                                             unquoted.contains("server.php", true) ||
                                                             unquoted.contains("player3", true)
                                     
        // v120: o player migrou para redecanaistv.af (Turnstile PRÓPRIO — cf_clearance de redecanais.af não vale).
        // Se o WebView navegou para um domínio de player QUE NÃO É redecanais.af, NÃO recarregar em loop:
        // o reload impede o Turnstile de completar a prova. Deixa o challenge resolver naturalmente (timeout 40s).
        val isForeignPlayerDomain = finishedUrl?.let { url ->
            url.contains("redecanaistv", true) || (
                (url.contains("server.php", true) || url.contains("player", true) || url.contains("dt.api", true)) &&
                !url.contains("redecanais.af", true)
            )
        } == true

        Log.i(TAG, "[CF_HTML] JS OuterHTML extraído: len=${unquoted.length} | isChallenge=$isChallenge | validStruct=$hasValidStructure | foreignPlayer=$isForeignPlayerDomain")
        
        if (!isChallenge && hasValidStructure && unquoted.length > 5000) {
            resultHtml = unquoted
            isDone.set(true)
        } else if (isChallenge && !isForeignPlayerDomain) {
            // v122: reload LIMITADO (máx 2x) — reload em loop infinito impede o Turnstile de completar
            // a prova (cada reload reinicia o challenge). Após 2 tentativas, aguarda resolver sozinho
            // (Turnstile managed de redecanais.af completa em ~30-90s com viewport real) e o requestDoc
            // cai no fallback solveInteractive (diálogo) se ainda falhar.
            if (challengeReloads < 2) {
                challengeReloads++
                view?.postDelayed({
                    if (!isDone.get()) {
                        Log.w(TAG, "[CF_HTML] Erro 520 ou challenge detectado (reload #$challengeReloads) -> recarregando página")
                        view.reload()
                    }
                }, 1500)
            } else {
                Log.i(TAG, "[CF_HTML] Challenge persistente após $challengeReloads reloads -> aguardando Turnstile resolver (sem reload em loop)")
            }
        } else if (isChallenge && isForeignPlayerDomain) {
            // v120: domínio de player externo com Turnstile próprio -> aguarda resolver, sem reload em loop.
            Log.i(TAG, "[CF_HTML] Domínio de player externo ($finishedUrl) com challenge próprio -> aguardando Turnstile resolver (sem reload)")
        }
                                }
                            }
                        }
                    }
                }
                wvVar = wv
                rootLayout.addView(wv)
                wv.loadUrl(url)
            } catch (e: Throwable) {
                Log.e(TAG, "[CF_HTML] Erro na extração via WebView: ${e.message}")
            }
        }

        withTimeoutOrNull(timeoutMs) {
            while (!isDone.get()) {
                delay(300)
            }
            true
        }

        withContext(Dispatchers.Main) {
            try {
                wvVar?.let {
                    it.stopLoading()
                    rootLayout.removeView(it)
                    it.destroy()
                }
            } catch (_: Throwable) {}
        }

        return resultHtml
    }
}
