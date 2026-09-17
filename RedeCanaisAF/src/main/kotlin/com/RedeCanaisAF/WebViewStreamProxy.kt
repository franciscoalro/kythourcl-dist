package com.RedeCanaisAF

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * v122 — Proxy local de stream via WebView.
 *
 * Por que existe: a URL real do vídeo (__RC__/proxy?src=<p12-common-sign...>) é TLS-bound
 * (JA3 fingerprint). O cf_clearance do Cloudflare só vale para o TLS do navegador que o
 * emitiu — curl/OkHttp/VLC/ExoPlayer direto recebem 520. Provado via browser-harness:
 * o elemento <video> no contexto da página obtém 206 (1.9MB streamed), fetch externo = 520.
 *
 * Solução: o WebView do app carrega server.php (mesma sessão cf_clearance+RCSESS), clica
 * no recap (.captcha_button), captura a URL __RC__/proxy que o player usa, e um ServerSocket
 * local (127.0.0.1) serve essa URL ao ExoPlayer fazendo fetch chunk-a-chunk DENTRO do
 * contexto JS do WebView (credentials: include + Range) — mesmo TLS, mesmo fingerprint.
 */
object WebViewStreamProxy {
    private const val TAG = "RedeCanaisAF-Trace"
    // v123: 512KB por fetch (era 256KB) — decode via FileReader.readAsDataURL é nativo e rápido,
    // então chunks maiores reduzem round-trips sem custo de CPU no JS
    private const val CHUNK_SIZE = 512 * 1024
    private const val CAPTURE_TIMEOUT_MS = 45000L
    private const val CHUNK_FETCH_TIMEOUT_S = 20L
    private const val POLL_INTERVAL_MS = 100L
    private const val CLICK_RETRY_MS = 250L
    private const val DIRECT_FALLBACK_MS = 1500L
    private const val RELOAD_FIRST_MS = 8000L
    private const val RELOAD_INTERVAL_MS = 4000L
    private const val MAX_RELOADS = 3
    private const val DUD_CLICK_MS = 9000L
    private val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    @Volatile private var webView: WebView? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var streamUrl: String? = null
    @Volatile private var isServing = false
    @Volatile private var swInstalled = false
    // v277: proxy local SEMPRE servido na porta preferida PREFERRED_PORT (fallback
    // efêmero se ocupada) por um ServerSocket de SESSÃO — aberto 1x, vivo até o
    // fim do app. Cada loadLinks só troca o targetUrl servido. Isso corrige o
    // erro 2001/ERR_CONNECTION_REFUSED do log do celular: o app cacheava o
    // LoadResponse (LOAD_CACHE_HIT) e o RepoLinkGenerator reentregava a URL
    // 127.0.0.1:<porta-antiga>/stream.mp4 quando o ServerSocket já tinha sido
    // fechado no shutdown() da sessão anterior. Com socket persistente + mesma
    // porta, a URL cacheada continua respondendo (novo target). Sem shutdown em
    // startLocalServer/capture — só no teardown do app.
    private const val PREFERRED_PORT = 17532
    @Volatile private var sessionTargetUrl: String? = null
    @Volatile private var localPort = -1

    /**
     * v278: abre o ServerSocket de sessão ANTECIPADO no boot (sem target ainda).
     * Conexões antes do primeiro loadLinks recebem 503 + Retry-After (player mostra
     * "carregando" em vez de CONNECTION_REFUSED). Quando o loadLinks captura o
     * __RC__/proxy, startLocalServer() só troca o target — mesma porta, sem gap.
     * Seguro chamar 1x no boot; se a porta estiver ocupada tenta de novo no 1º play.
     */
    fun prewarmLocalServer() {
        synchronized(this) {
            val live = serverSocket?.let { !it.isClosed && it.isBound } == true
            if (live) return
        }
        try {
            val server = try {
                ServerSocket(PREFERRED_PORT, 16, java.net.InetAddress.getByName("127.0.0.1"))
            } catch (_: Throwable) {
                return // ocupada — startLocalServer tenta efêmera no 1º play
            }
            serverSocket = server
            isServing = true
            localPort = server.localPort
            thread(isDaemon = true, name = "RCProxy-Accept") {
                while (isServing && !server.isClosed) {
                    try {
                        val client = server.accept()
                        val target = sessionTargetUrl
                        thread(isDaemon = true, name = "RCProxy-Conn") {
                            if (target.isNullOrBlank()) serveWaiting(client)
                            else handleConnection(client, target)
                        }
                    } catch (e: Exception) {
                        if (isServing) Log.w(TAG, "[PROXY] accept err: ${e.message}")
                        break
                    }
                }
            }
            Log.i(TAG, "[PROXY] Socket de sessão pré-aberto no boot: http://127.0.0.1:$localPort/stream.mp4 (aguardando 1º target)")
        } catch (e: Throwable) {
            Log.w(TAG, "[PROXY] prewarm falhou: ${e.message}")
        }
    }

    /** v278: resposta de espera — player operante mas sem vídeo ainda (503). */
    private fun serveWaiting(socket: Socket) {
        try {
            socket.use { sock ->
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
                reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                }
                val body = "waiting for stream"
                val head =
                    "HTTP/1.1 503 Service Unavailable\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: ${body.length}\r\n" +
                        "Retry-After: 2\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n$body"
                sock.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
                sock.getOutputStream().flush()
            }
        } catch (_: Exception) {}
    }
    /**
     * v251: registra ServiceWorker globalmente — o site usa /sw.js para
     * interceptar fetch de serverforms.api/__RC__/proxy e enriquecer a sessão
     * (extra credenciais/cookies que o app não monta). No WebView de
     * produção (browser-harness/CDP) o SW estava ativo foi o único ambiente
     * onde o __RC__/proxy retornou MP4 real e video.readyState=4; no WebView
     * tradicional o SW vinha desativado (sw.js falhava silencioso) e o bundle
     * caía em no-video. Instalado 1x por processo; tolera API<24 (N+).
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun ensureServiceWorker() {
        if (swInstalled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            // v253: habilita debug para browser-harness CDP (adb forward 9223) e registra SW
            try { WebView.setWebContentsDebuggingEnabled(true) } catch (_: Throwable) {}
            val sw = ServiceWorkerController.getInstance()
            sw.setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    try {
                        val u = request.url.toString()
                        if (u.contains("serverforms", true) || u.contains("__RC__", true)
                            || u.contains("tos-alisg", true) || u.contains("proxy", true)
                            || u.contains("sw.js", true)
                        ) Log.i(TAG, "[SW] shouldInterceptRequest ${request.method} " + u.take(260))
                    } catch (_: Throwable) {}
                    return null // delegar ao SW do site
                }
            })
            swInstalled = true
            Log.i(TAG, "[SW] registrado (ServiceWorkerClient global + WebContentsDebuggingEnabled)")
        } catch (e: Throwable) {
            Log.w(TAG, "[SW] não registrado: ${e.message?.take(140)}")
        }
    }

    /**
     * Captura o stream real (__RC__/proxy) abrindo server.php no WebView e clicando no recap.
     * Retorna a URL local http://127.0.0.1:<porta>/stream.mp4 que o ExoPlayer deve reproduzir.
     */
    @SuppressLint("SetJavaScriptEnabled")
    // v241 (Null_Pointer): embed.php legado usa player HTML5 direto (sem recap/
    // serverforms/RC4). O loop de clique/recap + hook serverforms NÃO se aplica:
    // captura via <video>/performance-resource como o browser-harness da Fase 51
    // (elemento <video> no contexto da página obteve 206). GONE 1x1 + SOFTWARE
    // (leve, sem pipeline de mídia) + sem hook pesado — 1 WebView mínima.
    /**
     * v246: reaproveita o WebView canônico (que JÁ tem challenge válido) para o
     * embed legado. Chamado pelo StreamResolver logo após captureAndServe da
     * canônica — NÃO dá shutdown (que destruiria o jar válido); apenas troca o
     * WebViewClient para o modo legado (LEGACY_* logs) e navega o MESMO WebView
     * para embedUrl. Retorna null se não houver WebView vivo (fallback: caller
     * usa captureLegacyEmbed, que cria um novo).
     */
    /**
     * v250: garante o WebView ÚNICO da sessão de captura (cria 1x, reaproveita
     * nas variantes). Chamado pelo StreamResolver antes da matriz. O challenge
     * resolvido na 1ª navegação vale para as seguintes — sem shutdown no meio.
     * Retorna true se há WebView vivo (criado ou reaproveitado).
     */
    suspend fun ensureSingleWebView(): Boolean {
        synchronized(this) { webView }?.let {
            try {
                if (it.url?.isNotBlank() == true) {
                    Log.i(TAG, "[WV_SINGLE] reaproveitando WebView vivo url=${it.url?.take(120)}")
                    return true
                }
            } catch (_: Throwable) {}
        }
        // procura na hierarchy (sobreviveu a shutdown parcial)
        try {
            val act = CommonActivity.activity
            val root = act?.findViewById<ViewGroup>(android.R.id.content)
            if (root != null) {
                fun findWv(g: ViewGroup): WebView? {
                    for (i in 0 until g.childCount) {
                        val v = g.getChildAt(i)
                        if (v is WebView) return v
                        if (v is ViewGroup) { val f = findWv(v); if (f != null) return f }
                    }
                    return null
                }
                val found = findWv(root)
                if (found != null) {
                    synchronized(this) { webView = found }
                    Log.i(TAG, "[WV_SINGLE] WebView resgatado da hierarchy url=${found.url?.take(120)}")
                    return true
                }
            }
        } catch (_: Throwable) {}
        // cria o único
        val activity: Activity? = CommonActivity.activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "[WV_SINGLE] Activity indisponível")
            return false
        }
        LocalImageProxy.shutdownHelper()
        val userAgent = CloudflareSolver.lastUserAgent
            ?: WebViewResolver.webViewUserAgent ?: MOBILE_UA
        withContext(Dispatchers.Main) {
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                val view = WebView(activity).apply {
                    visibility = android.view.View.VISIBLE
                    alpha = 0.01f
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    isLongClickable = true
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    try { ensureServiceWorker() } catch (_: Throwable) {}
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        @Suppress("DEPRECATION") try { databaseEnabled = true } catch (_: Throwable) {}
                        blockNetworkImage = false
                        loadsImagesAutomatically = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        javaScriptCanOpenWindowsAutomatically = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = userAgent
                    }
                }
                val rootLayout = activity.findViewById<ViewGroup>(android.R.id.content)
                rootLayout.addView(view, 0)
                synchronized(this@WebViewStreamProxy) { webView = view }
                Log.i(TAG, "[WV_SINGLE] WebView único criado")
            } catch (e: Throwable) {
                Log.e(TAG, "[WV_SINGLE] err=${e.message?.take(120)}")
            }
        }
        return synchronized(this) { webView } != null
    }

    /**
     * v250: navega o WebView ÚNICO para o embed legado e espera até o <video>
     * TOCAR (playing), capturando a URL raiz que o player usa no play
     * (__rcPlaySrc / shouldIntercept / performance). Sem shutdown, sem WebView
     * novo — o challenge da canônica continua válido.
     */
    suspend fun captureLegacyOnSingleWebView(embedUrl: String, budgetMs: Long = 20000L): String? {
        val wvOk = synchronized(this) { webView }
        if (wvOk == null) {
            Log.w(TAG, "[WV_SINGLE] sem WebView único — abortando variante $embedUrl")
            return null
        }
        return captureLegacyOnSameWebView(embedUrl, budgetMs)
    }

    suspend fun captureLegacyOnSameWebView(embedUrl: String, budgetMs: Long = 20000L): String? {
        // v246b: retrofit — captura o WebView pela VIEW HIERARCHY (o singleton
        // `webView` foi nulado no shutdown pós-canônica, mas a VIEW pode ainda
        // estar atachada). Se nem a view existir, retorna null (fallback).
        var wv: WebView? = synchronized(this) { webView }
        if (wv == null) {
            try {
                val act = CommonActivity.activity
                val root = act?.findViewById<ViewGroup>(android.R.id.content)
                if (root != null) {
                    for (i in 0 until root.childCount) {
                        val v1 = root.getChildAt(i)
                        if (v1 is WebView) { wv = v1; break }
                        if (v1 is ViewGroup) {
                            for (j in 0 until v1.childCount) {
                                val v2 = v1.getChildAt(j)
                                if (v2 is WebView) { wv = v2; break }
                            }
                        }
                        if (wv != null) break
                    }
                }
            } catch (_: Throwable) {}
        }
        val wvOk = wv
        if (wvOk == null) {
            Log.w(TAG, "[PROXY_REUSE] sem WebView (singleton+view) — fallback para captureLegacyEmbed")
            return null
        }
        try {
            Log.i(TAG, "[PROXY_REUSE] WebView encontrado na hierarchy url=${wvOk.url?.take(150)} — navegando para $embedUrl")
        } catch (_: Throwable) {}
        synchronized(this) { webView = wvOk }
        Log.i(TAG, "[PROXY_REUSE] navegando WebView válido para $embedUrl")
        val captured = AtomicBoolean(false)
        streamUrl = null
        withContext(Dispatchers.Main) {
            try {
                wvOk.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        if (newProgress == 100 || newProgress % 25 == 0) {
                            Log.i(TAG, "[LEGACY_PROG] progress=$newProgress url=${view?.url?.take(200)} title=${view?.title?.take(80)}")
                        }
                    }
                }
                wvOk.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        Log.i(TAG, "[LEGACY_PAGE] started url=${url?.take(250)} (reuse)")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.i(TAG, "[LEGACY_PAGE] finished url=${url?.take(250)} title=${view?.title?.take(80)} (reuse)")
                        view?.evaluateJavascript(
                            """(function(){return JSON.stringify({href:location.href,title:document.title,htmlLen:document.documentElement?document.documentElement.outerHTML.length:0,videos:document.querySelectorAll('video').length,iframes:document.querySelectorAll('iframe').length,body0:(document.body?document.body.innerHTML:'').slice(0,200)});})();"""
                        ) { res -> Log.i(TAG, "[LEGACY_DOM] $res (reuse)") }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val u = request.url.toString()
                        val isMedia = (u.contains(".mp4", true) || u.contains(".m3u8", true) ||
                            u.contains("__RC__/proxy", true) || u.contains("/proxy?src=", true) ||
                            u.contains("tos-alisg", true) || u.contains("container=videos", true)) &&
                            !u.contains("disqus", true) && !u.contains("google", true)
                        if (isMedia && !captured.get()) {
                            streamUrl = u
                            captured.set(true)
                            Log.i(TAG, "[PROXY_REUSE] Stream capturado: ${u.take(180)}")
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                wvOk.loadUrl(embedUrl)
            } catch (e: Throwable) {
                Log.e(TAG, "[PROXY_REUSE] err=${e.message?.take(120)}")
                return@withContext
            }
        }
        val budget = budgetMs.coerceIn(10000L, 45000L)
        withTimeoutOrNull(budget) {
            while (!captured.get()) {
                delay(POLL_INTERVAL_MS)
                withContext(Dispatchers.Main) {
                    try {
                        wvOk.evaluateJavascript(
                            """(function(){
                                const entries = performance.getEntriesByType('resource');
                                for (let i = entries.length - 1; i >= 0; i--) {
                                    const n = entries[i].name;
                                    if ((n.indexOf('.mp4')>=0 || n.indexOf('.m3u8')>=0 || n.indexOf('__RC__')>=0 || n.indexOf('/proxy?')>=0) && n.indexOf('disqus')<0) return n;
                                }
                                const v = document.querySelector('video');
                                if (v) { const s = v.currentSrc || v.src || ''; if (s && s.indexOf('blob:')<0) return s; }
                                return '';
                            })();""".trimIndent()
                        ) { res ->
                            val found = res?.removeSurrounding("\"").orEmpty()
                            if (found.startsWith("http") && !captured.get()) {
                                streamUrl = found
                                captured.set(true)
                                Log.i(TAG, "[PROXY_REUSE] Stream via JS poll: ${found.take(180)}")
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
            true
        }
        val finalUrl = streamUrl
        if (finalUrl.isNullOrBlank()) {
            Log.w(TAG, "[PROXY_REUSE] Falha: nenhum stream em ${budget}ms para $embedUrl (WebView reaproveitado)")
            return null
        }
        Log.i(TAG, "[PROXY_REUSE] Captura OK: $finalUrl")
        return startLocalServer(finalUrl)
    }

    suspend fun captureLegacyEmbed(embedUrl: String, budgetMs: Long = 30000L): String? {
        shutdown()
        LocalImageProxy.shutdownHelper()
        val activity: Activity? = CommonActivity.activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "[PROXY] Activity indisponível (legacy)")
            return null
        }
        Log.i(TAG, "[PROXY_LEGACY] Captura embed legado: $embedUrl")
        val captured = AtomicBoolean(false)
        var wv: WebView? = null
        val rootLayout = activity.findViewById<ViewGroup>(android.R.id.content)
        withContext(Dispatchers.Main) {
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                // v245: o WebView legado é NOVO (jar próprio vazio) — sem challenge
                // ele morre em "Attention Required" (E2E v244: LEGACY_DOM prova).
                // Copia TODOS os cookies do domínio (cf_clearance + RCIP/RCSESS)
                // do CookieManager global antes do loadUrl. Sem flush, sem navegação.
                try {
                    val srcUrls = listOf(
                        "https://redecanais.af/",
                        "https://redecanais.af/player3/server.php",
                        embedUrl.substringBefore("?").ifBlank { "https://redecanais.af/" }
                    )
                    var copied = 0
                    for (su in srcUrls.distinct()) {
                        val raw = cookieManager.getCookie(su) ?: continue
                        val host = android.net.Uri.parse(embedUrl).host ?: "redecanais.af"
                        for (piece in raw.split(";")) {
                            val c = piece.trim()
                            if (c.isBlank() || c.startsWith("expires", true) ||
                                c.startsWith("path", true) || c.startsWith("domain", true) ||
                                c.startsWith("max-age", true) || c.startsWith("samesite", true)
                            ) continue
                            try {
                                cookieManager.setCookie("https://$host/", c)
                                cookieManager.setCookie("https://$host${
                                    android.net.Uri.parse(embedUrl).path ?: "/"
                                }", c)
                                copied++
                            } catch (_: Throwable) {}
                        }
                    }
                    try { cookieManager.flush() } catch (_: Throwable) {}
                    Log.i(TAG, "[LEGACY_COOKIES] copiados=$copied para ${android.net.Uri.parse(embedUrl).host}")
                } catch (e: Throwable) {
                    Log.w(TAG, "[LEGACY_COOKIES] err=${e.message?.take(100)}")
                }
                val userAgent = CloudflareSolver.lastUserAgent
                    ?: WebViewResolver.webViewUserAgent ?: MOBILE_UA
                // v243: legado usa o MESMO modo do canônico (VISIBLE MATCH_PARENT
                // alpha 0.01 HARDWARE) — GONE 1x1 SOFTWARE congelava a navegação
                // (E2E v242: 4 embeds x 20s sem 1 LEGACY_NAV; mesmo bug v229).
                val view = WebView(activity).apply {
                    visibility = android.view.View.VISIBLE
                    alpha = 0.01f
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    isLongClickable = true
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    try { ensureServiceWorker() } catch (_: Throwable) {}
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        @Suppress("DEPRECATION") try { databaseEnabled = true } catch (_: Throwable) {}
                        blockNetworkImage = true
                        loadsImagesAutomatically = false
                        useWideViewPort = false
                        loadWithOverviewMode = false
                        userAgentString = userAgent
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            if (newProgress == 100 || newProgress % 25 == 0) {
                                Log.i(TAG, "[LEGACY_PROG] progress=$newProgress url=${view?.url?.take(200)} title=${view?.title?.take(80)}")
                            }
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            Log.i(TAG, "[LEGACY_PAGE] started url=${url?.take(250)}")
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.i(TAG, "[LEGACY_PAGE] finished url=${url?.take(250)} title=${view?.title?.take(80)}")
                            view?.evaluateJavascript(
                                """(function(){return JSON.stringify({href:location.href,title:document.title,htmlLen:document.documentElement?document.documentElement.outerHTML.length:0,videos:document.querySelectorAll('video').length,iframes:document.querySelectorAll('iframe').length,body0:(document.body?document.body.innerHTML:'').slice(0,200)});})();"""
                            ) { res -> Log.i(TAG, "[LEGACY_DOM] $res") }
                        }

                        @Suppress("DEPRECATION")
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            super.onReceivedError(view, errorCode, description, failingUrl)
                            Log.w(TAG, "[LEGACY_ERR] code=$errorCode desc=${description?.take(100)} ${failingUrl?.take(200)}")
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val u = request.url.toString()
                            val isMedia = (u.contains(".mp4", true) || u.contains(".m3u8", true) ||
                                u.contains("__RC__/proxy", true) || u.contains("/proxy?src=", true) ||
                                u.contains("tos-alisg", true) || u.contains("container=videos", true)) &&
                                !u.contains("disqus", true) && !u.contains("google", true)
                            if (isMedia && !captured.get()) {
                                streamUrl = u
                                captured.set(true)
                                Log.i(TAG, "[PROXY_LEGACY] Stream capturado: ${u.take(180)}")
                                try {
                                    val ctx = CommonActivity.activity ?: CommonActivity.activity?.applicationContext
                                    ctx?.let { c -> java.io.File(c.filesDir, "redecanais_af_last_stream_url.txt").writeText(u) }
                                } catch (_: Throwable) {}
                            } else if (u.contains("player", true) || u.contains(".mp4", true) || u.contains(".m3u8", true)) {
                                Log.i(TAG, "[PROXY_LEGACY_REQ] ${request.method} ${u.take(250)}")
                            }
                            // v242 (Null_Pointer): diag do embed legado — loga TODA
                            // navegação (MAIN + status via onReceivedHttpError) p/
                            // distinguir 404/403/JS-vazio no embed.php?vid=<curto>.
                            val uLower = u.lowercase()
                            if (request.isForMainFrame || uLower.contains("embed.php") || uLower.contains("play.php")) {
                                Log.i(TAG, "[LEGACY_NAV] ${request.method} MAIN=${request.isForMainFrame} ${u.take(250)}")
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            try {
                                val u = request?.url.toString()
                                if (request?.isForMainFrame == true || u.contains("embed.php", true) || u.contains("play.php", true)) {
                                    Log.w(TAG, "[LEGACY_HTTPERR] code=${errorResponse?.statusCode} ${u.take(250)}")
                                }
                            } catch (_: Throwable) {}
                            super.onReceivedHttpError(view, request, errorResponse)
                        }
                    }
                }
                wv = view
                webView = view
                rootLayout.addView(view, 0)
                view.loadUrl(embedUrl)
            } catch (e: Throwable) {
                Log.e(TAG, "[PROXY_LEGACY] Erro ao criar WebView: ${e.message}")
            }
        }
        val budget = budgetMs.coerceIn(10000L, 45000L)
        withTimeoutOrNull(budget) {
            while (!captured.get()) {
                delay(POLL_INTERVAL_MS)
                val wvNow = wv ?: continue
                withContext(Dispatchers.Main) {
                    try {
                        wvNow.evaluateJavascript(
                            """(function(){
                                const entries = performance.getEntriesByType('resource');
                                for (let i = entries.length - 1; i >= 0; i--) {
                                    const n = entries[i].name;
                                    if ((n.indexOf('.mp4')>=0 || n.indexOf('.m3u8')>=0 || n.indexOf('__RC__')>=0 || n.indexOf('/proxy?')>=0) && n.indexOf('disqus')<0) return n;
                                }
                                const v = document.querySelector('video');
                                if (v) { const s = v.currentSrc || v.src || ''; if (s && s.indexOf('blob:')<0) return s; }
                                return '';
                            })();""".trimIndent()
                        ) { res ->
                            val found = res?.removeSurrounding("\"").orEmpty()
                            if (found.startsWith("http") && !captured.get()) {
                                streamUrl = found
                                captured.set(true)
                                Log.i(TAG, "[PROXY_LEGACY] Stream via JS poll: ${found.take(180)}")
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
            true
        }
        val finalUrl = streamUrl
        if (finalUrl.isNullOrBlank()) {
            Log.w(TAG, "[PROXY_LEGACY] Falha: nenhum stream em ${budget}ms para $embedUrl")
            shutdown()
            return null
        }
        Log.i(TAG, "[PROXY_LEGACY] Captura OK: $finalUrl")
        return startLocalServer(finalUrl)
    }

    /**
     * v250: canônica no WebView ÚNICO (criado por ensureSingleWebView) — NÃO dá
     * shutdown nem cria WebView novo. Reconfigura clients no jar existente,
     * navega para serverPhpUrl, clica recap, aperta PLAY e espera até o
     * <video> tocar (playing), capturando a URL raiz. Sem destroy no meio.
     */
    suspend fun captureAndServeSingle(serverPhpUrl: String, budgetMs: Long = CAPTURE_TIMEOUT_MS, detailUrl: String = ""): String? {
        val single = synchronized(this) { webView }
        if (single == null) {
            Log.w(TAG, "[WV_SINGLE] sem WebView único — fallback para captureAndServe (cria novo)")
            return captureAndServe(serverPhpUrl, budgetMs, detailUrl)
        }
        Log.i(TAG, "[WV_SINGLE] canônica no WebView único: $serverPhpUrl")
        // marca o singleton como em-uso e delega ao fluxo canônico, que será
        // adaptado para reusar: em vez de duplicar 800 linhas, o captureAndServe
        // ganha um parâmetro reuse. Por ora: navega o único e roda o loop.
        return captureAndServeReuse(single, serverPhpUrl, budgetMs, detailUrl)
    }

    /**
     * v253: aquece o storage/scope do domínio principal antes de atacar o
     * server.php — o harness quente provou que controller==activated só existe
     * quando o WebView já navegou em https://redecanais.af/ com cookies. Sem
     * isso o server.php cai em Just a moment... (sem hasBtn/rcFn) ou em 204.
     * Usa o MESMO WebView único; se já houver wv vivo reaproveita, senão cria.
     * Fallback silencioso em exceção.
     */
    private suspend fun warmMainDomainIfNeeded(existing: WebView? = null): WebView? {
        val activity: Activity? = CommonActivity.activity ?: return existing
        try {
            val wv = existing ?: synchronized(this) { webView } ?: return existing
            val needWarm = withContext(Dispatchers.Main) {
                try {
                    val cur = wv.url.orEmpty()
                    if (cur.contains("redecanais.af", true)) return@withContext false
                    val fut = CompletableFuture<String>()
                    wv.evaluateJavascript("(function(){try{return (navigator.serviceWorker.controller?'ctrl:'+navigator.serviceWorker.controller.state:'no-ctrl')}catch(e){return 'err'}})();") { v -> fut.complete(v ?: "") }
                    val state = try { fut.get(1200, TimeUnit.MILLISECONDS).removeSurrounding("\"").orEmpty() } catch (_: Throwable) { "" }
                    state != "ctrl:activated"
                } catch (_: Throwable) { true }
            }
            if (!needWarm) return wv
            Log.i(TAG, "[WARM] navegando WebView único em https://redecanais.af/ para ativar ServiceWorker controller")
            withContext(Dispatchers.Main) {
                wv.loadUrl("https://redecanais.af/")
            }
            // aguarda até 4s o controller ficar activated (poll rápido)
            for (i in 0 until 20) {
                delay(200)
                val ready = withContext(Dispatchers.Main) {
                    try {
                        val f = CompletableFuture<String>()
                        wv.evaluateJavascript("(function(){try{var c=navigator.serviceWorker.controller;return (c && c.state)||'no-ctrl'}catch(e){return 'err'}})();") { v -> f.complete(v ?: "") }
                        f.get(500, TimeUnit.MILLISECONDS).contains("activated")
                    } catch (_: Throwable) { false }
                }
                if (ready) { Log.i(TAG, "[WARM] controller activated em ${i * 200}ms"); break }
            }
            // pequena pausa para o site gravar storage/cookie no scope correto
            delay(600)
            return wv
        } catch (e: Throwable) {
            Log.w(TAG, "[WARM] falhou warm-up: ${e.message?.take(140)}")
            return existing
        }
    }

    suspend fun captureAndServe(serverPhpUrl: String, budgetMs: Long = CAPTURE_TIMEOUT_MS, detailUrl: String = ""): String? {
        shutdown() // limpa estado anterior
        // Poster fetches have already completed before playback. Release their helper
        // renderer so the full-size hardware player WebView does not overlap it.
        LocalImageProxy.shutdownHelper()

        val activity: Activity? = CommonActivity.activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "[PROXY] Activity indisponível")
            return null
        }

        Log.i(TAG, "[PROXY] Iniciando captura WebView para $serverPhpUrl")

        val captured = AtomicBoolean(false)
        val pageReady = AtomicBoolean(false)
        val clickDone = AtomicBoolean(false)
        val captureHolder = AtomicBoolean(false)
        var wv: WebView? = null
        val rootLayout = activity.findViewById<ViewGroup>(android.R.id.content)

        withContext(Dispatchers.Main) {
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                val userAgent = CloudflareSolver.lastUserAgent
                    ?: WebViewResolver.webViewUserAgent
                    ?: MOBILE_UA

                // v230: MATCH_PARENT invisível (alpha 0.01, atrás do conteúdo) +
                // HARDWARE durante a captura — dois motivos provados no diag:
                // (a) o bundle (videojs/ima3) pode exigir pipeline de mídia real
                // para montar o __RC__/proxy; GONE+SOFTWARE congelava o player
                // (v229: btn+rcFn presentes 45s sem nenhuma chamada de API);
                // (b) com layout 1x1 o getBoundingClientRect retorna coords fora
                // dos limites da view (ex: 60.0,0.5) e o dispatchTouchEvent é
                // descartado — com viewport real as coords caem dentro da view.
                // Vida curta (destruído no shutdown ao capturar/timeout) — 1 WebView
                // transiente não pressiona o LMK como os permanentes da v228.
                val view = WebView(activity).apply {
                    visibility = android.view.View.VISIBLE
                    alpha = 0.01f
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    isLongClickable = true
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    // v251p: corrige SyntaxError que impedia o hook inteiro.
                    // A linha 228 tinha faltando `})()` antes de `)()}` — pegava
                    // todo o hookJs em catch(e) = nunca registrava fetch/hook.
                    // SW dependia do mesmo WebView, então também não interceptava
                    // serverforms (v250 ficava no-video; v251 corrigido).
                    try { ensureServiceWorker() } catch (_: Throwable) {}
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            @Suppress("DEPRECATION") setAllowFileAccess(true)
                            @Suppress("DEPRECATION") allowContentAccess = true
                            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = true
                            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = true
                        }
                        databaseEnabled = true
                        blockNetworkImage = false
                        loadsImagesAutomatically = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        javaScriptCanOpenWindowsAutomatically = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = userAgent
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            view?.evaluateJavascript(CloudflareSolver.ANTI_DETECTION_JS, null)
                        }

                        // v232: captura console.log do hook de fetch/XHR/submit do body
                        override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                            try {
                                val m = msg?.message().orEmpty()
                                if (m.contains("[HOOK]", true) || m.contains("Uncaught", true) ||
                                    m.contains("Failed to load", true) || m.contains("Error", true)) {
                                    Log.i(TAG, "[HOOK_JS] $m @ ${msg?.sourceId()?.take(80)}:${msg?.lineNumber()}")
                                }
                            } catch (_: Throwable) {}
                            return super.onConsoleMessage(msg)
                        }
                    }

                    // v232e: hook total — intercepta TODO fluxo cliente->servidor->meio
                    // (fetch/XHR/submit/click/video) e expõe ao Kotlin via window.__rc*
                    // para diagnóstico do túnel Redemovel/RCIP. Anteriormente só logava
                    // bodies com __RC__/proxy (perdia serverforms vazio len=52 que indica
                    // túnel caído). Agora registra 100% dos bodies em __rcBodies.
                    val hookJs = """(function(){
                        if(window.__rcHook) return;
                        window.__rcHook=true;
                        try{
                          window.__rcCaptured='';
                          window.__rcCapturedRawLen=0;
                          window.__rcLastFetchUrl='';
                          window.__rcLastFetchBody='';
                          window.__rcLastFetchCt='';
                          window.__rcLastFetchStatus=0;
                          window.__rcBodies=[];
                          window.__rcFetchCount=0;
                          window.__rcLastApi=''; window.__rcApiHdrs=''; window.__rcApiLog=[];
                          const ofetch=window.fetch;
                          if(ofetch) window.fetch=function(u,o){
                            const urlStr=String(u).slice(0,400);
                            window.__rcLastFetchUrl=urlStr;
                            // v242 (Null_Pointer, teste d): registra URL + headers de
                            // TODA chamada .api (step1/step2) p/ replay OkHttp in-app.
                            try{
                              if(urlStr.indexOf('.api')>=0 && window.__rcApiLog.length<30){
                                const hdrs=(o&&o.headers)?JSON.stringify(o.headers).slice(0,400):'{}';
                                window.__rcLastApi=urlStr; window.__rcApiHdrs=hdrs+' method='+(o&&o.method||'GET');
                                window.__rcApiLog.push({url:urlStr, hdrs:hdrs, m:(o&&o.method||'GET'), t:Date.now()});
                              }
                            }catch(_){}
                            try{ console.log('[HOOK] fetch '+urlStr+' opts='+JSON.stringify(o||{}).slice(0,250)+' cookies='+document.cookie.slice(0,200)); }catch(_){}
                            const p=ofetch.apply(this, arguments);
                            try{
                              p.then(function(r){
                                try{
                                  const status=r.status, loc=r.headers.get('location')||r.headers.get('Location')||'';
                                  const ct=r.headers.get('content-type')||'';
                                  window.__rcLastFetchStatus=status;
                                  window.__rcLastFetchCt=ct;
                                  console.log('[HOOK] fetch-resp '+status+' ct='+ct.slice(0,60)+' loc='+String(loc).slice(0,250)+' url='+urlStr.slice(0,200));
                                  // v234: only inspect small metadata responses. Cloning every
                                  // fetch used to materialize media/chunks as text and amplify
                                  // WebView native memory during playback.
                                  const contentLength=parseInt(r.headers.get('content-length')||'0',10)||0;
                                  const isMetadata=urlStr.indexOf('serverforms.api')>=0||urlStr.indexOf('dt.api')>=0;
                                  // Stream capture itself is done by shouldInterceptRequest;
                                  // cloning arbitrary text documents is unnecessary here.
                                  if(!isMetadata||contentLength>1048576) return;
                                  const cl=r.clone();
                                  cl.text().then(function(t){
                                    if(t.length>1048576){ console.log('[HOOK] fetch-body skipped oversized metadata len='+t.length); return; }
                                    window.__rcFetchCount++;
                                    window.__rcLastFetchBody=t;
                                    try{ window.__rcBodies.push({url:urlStr, ct:ct, status:status, body:t.slice(0,3000)}); if(window.__rcBodies.length>20) window.__rcBodies.shift(); }catch(_){}
                                    const hasProxy=t.indexOf('__RC__/proxy')>=0||t.indexOf('/proxy?src=')>=0||t.indexOf('tos-alisg')>=0||t.indexOf('container=videos')>=0||t.indexOf('.m3u8')>=0||t.indexOf('.mp4')>=0||t.indexOf('https://')>=0;
                                    const hasLoc=t.indexOf('location')>=0||t.indexOf('src=')>=0;
                                    // v232e: SEMPRE loga body (antes só proxy||<3k) — crítico p/ serverforms vazio 204 []
                                    const preview=t.slice(0,900).replace(/\n/g,' ').replace(/\r/g,'');
                                    console.log('[HOOK] fetch-body #'+window.__rcFetchCount+' len='+t.length+' ct='+ct.slice(0,40)+' proxy='+hasProxy+' url='+urlStr.slice(0,120)+' preview='+preview);
                                    // diagnóstico túnel: serverforms com [] = backend sem stream
                                    if(urlStr.indexOf('serverforms')>=0 || urlStr.indexOf('dt.api')>=0){
                                      console.log('[HOOK] serverforms payload len='+t.length+' body='+t.slice(0,1200).replace(/\n/g,' '));
                                      try{
                                        const j=JSON.parse(t);
                                        console.log('[HOOK] serverforms JSON keys='+Object.keys(j).join(',')+' vals='+JSON.stringify(j).slice(0,900));
                                        // detecta túnel caído: array vazio + code 204
                                        if(j.e18b73c9 && Array.isArray(j.e18b73c9) && j.e18b73c9.length===0){
                                          console.log('[HOOK] TUNEL_VAZIO serverforms retornou e18b73c9=[] (código interno 204) — origem não determinada; pode ser sessão, disponibilidade ou política do servidor');
                                        }
                                        if(j.c4a0f6) console.log('[HOOK] c4a0f6 len='+(String(j.c4a0f6).length)+' preview='+String(j.c4a0f6).slice(0,150));
                                      }catch(e){ console.log('[HOOK] serverforms JSON parse err '+e.message); }
                                    }
                                    try{
                                      let found='';
                                      const m=t.match(/https?:\/\/[^\s"'\\]+\.(?:m3u8|mp4)[^\s"'\\]*/i);
                                      if(m) found=m[0];
                                      if(!found){
                                        const m2=t.match(/https?:\/\/[^\s"'\\]*tos-alisg[^\s"'\\]*/i);
                                        if(m2) found=m2[0];
                                      }
                                      if(!found){
                                        const m3=t.match(/https?:\/\/[^\s"'\\]*\/proxy\?container=[^\s"'\\]*/i);
                                        if(m3) found=m3[0];
                                      }
                                      if(!found){
                                        const m4=t.match(/https?:\/\/[^\s"'\\]*__RC__[^\s"'\\]*/i);
                                        if(m4) found=m4[0];
                                      }
                                      if(!found){
                                        try{
                                          const j=JSON.parse(t);
                                          const vals=JSON.stringify(j);
                                          const m5=vals.match(/https?:\/\/[^\s"'\\]+\.(?:m3u8|mp4)[^\s"'\\]*/i);
                                          if(m5) found=m5[0];
                                          if(!found){
                                            const m6=vals.match(/https?:\\\/\\\/[^\s"'\\]{20,}/);
                                            if(m6) found=m6[0].replace(/\\\//g,'/');
                                          }
                                          // fallback: qualquer https longo em JSON (proxy sem extensão)
                                          if(!found && vals.indexOf('https')>=0){
                                            const m7=vals.match(/https?:[^\s"'\\]{15,}/);
                                            if(m7) found=m7[0].replace(/\\\//g,'/');
                                          }
                                          // tenta decodificar base64 se houver
                                          if(!found){
                                            for(const k of Object.keys(j)){
                                              const v=j[k];
                                              if(typeof v==='string' && v.length>20){
                                                try{ const dec=atob(v); if(dec.indexOf('http')>=0) { found=dec.match(/https?:\/\/[^\s"'\\]+/)?.[0]||''; if(found) break; } }catch(_){}
                                              }
                                            }
                                          }
                                        }catch(_){}
                                      }
                                      if(found){
                                        window.__rcCaptured=found;
                                        window.__rcCapturedRawLen=t.length;
                                        console.log('[HOOK] captured stream '+found.slice(0,320));
                                      } else if(hasProxy){
                                        window.__rcCaptured=t.slice(0,800);
                                        console.log('[HOOK] captured raw proxy body len='+t.length);
                                      }
                                      // sempre guarda último body p/ Kotlin poll (mesmo sem proxy)
                                      window.__rcLastFetchBody=t.slice(0,3000);
                                    }catch(e){ console.log('[HOOK] capture-err '+e.message); }
                                  }).catch(function(e){ console.log('[HOOK] fetch-body-err '+e.message); });
                                }catch(e){ console.log('[HOOK] fetch-resp-err '+e.message); }
                                return r;
                              }).catch(function(e){ console.log('[HOOK] fetch-reject '+e.message+' url='+urlStr.slice(0,200)); });
                            }catch(_){}
                            return p;
                          };
                          const oOpen=XMLHttpRequest.prototype.open;
                          XMLHttpRequest.prototype.open=function(m,u){
                            try{ console.log('[HOOK] XHR open '+m+' '+String(u).slice(0,260)); }catch(_){}
                            return oOpen.apply(this, arguments);
                          };
                          const oSend=XMLHttpRequest.prototype.send;
                          XMLHttpRequest.prototype.send=function(b){
                            try{ console.log('[HOOK] XHR send '+(b?String(b).slice(0,300):'-')); }catch(_){}
                            // espia resposta XHR
                            try{
                              const xhr=this;
                              const prev=xhr.onload;
                              const onLoad=function(){
                                try{
                                  const t=xhr.responseText||'';
                                  const hasProxy=t.indexOf('__RC__/proxy')>=0||t.indexOf('/proxy?src=')>=0||t.indexOf('.m3u8')>=0;
                                  console.log('[HOOK] XHR-resp status='+xhr.status+' len='+t.length+' proxy='+hasProxy+' preview='+t.slice(0,400).replace(/\n/g,' '));
                                }catch(e){ console.log('[HOOK] XHR-resp-err '+e.message); }
                                if(prev) return prev.apply(this, arguments);
                              };
                              if(xhr.addEventListener) xhr.addEventListener('load', onLoad, false); else xhr.onload=onLoad;
                            }catch(_){}
                            return oSend.apply(this, arguments);
                          };
                          document.addEventListener('submit', function(e){
                            try{ console.log('[HOOK] submit action='+(e.target&&e.target.action||'')+' method='+(e.target&&e.target.method||'')); }catch(_){}
                          }, true);
                          window.addEventListener('error', function(e){ try{ console.log('[HOOK] window-error '+e.message+' @'+(e.filename||'').slice(0,80)+':'+e.lineno); }catch(_){} }, true);
                          window.addEventListener('unhandledrejection', function(e){ try{ console.log('[HOOK] unhandledrejection '+(e.reason&&e.reason.message||String(e.reason)).slice(0,300)); }catch(_){} }, true);
                          // v232: intercepta navegação (o form action="?" sem preventDefault
                          // causa GET server.php? e o reload perde o handler do botão).
                          window.addEventListener('beforeunload', function(){ try{ console.log('[HOOK] beforeunload href='+location.href.slice(0,120)); }catch(_){} }, true);
                          // v249: o __RC__/proxy só nasce quando o <video> TOCA.
                          // Espiona play/playing/pause/error + src de TODO <video>
                          // (existente ou criado depois) e registra a URL que o
                          // player usa no momento do play em window.__rcPlaySrc.
                          window.__rcPlaySrc='';
                          function rcWatchVideo(v){
                            if(!v||v.__rcWatched) return;
                            v.__rcWatched=true;
                            ['play','playing','pause','error','stalled','waiting','loadstart'].forEach(function(ev){
                              v.addEventListener(ev, function(){
                                try{
                                  const s=v.currentSrc||v.src||'';
                                  if((ev==='play'||ev==='playing'||ev==='loadstart')&&s) window.__rcPlaySrc=s;
                                  console.log('[HOOK] video-'+ev+' ready='+v.readyState+' net='+v.networkState+' paused='+v.paused+' src='+String(s).slice(0,250));
                                }catch(_){}
                              });
                            });
                          }
                          try{ Array.from(document.querySelectorAll('video')).forEach(rcWatchVideo); }catch(_){}
                          document.addEventListener('click', function(e){
                            try{
                              const t=e.target;
                              console.log('[HOOK] click tag='+(t&&t.tagName||'')+' id='+(t&&t.id||'')+' class='+(t&&t.className||'').toString().slice(0,60)+' prevented='+e.defaultPrevented+' trusted='+e.isTrusted);
                            }catch(_){}
                          }, true);
                          // v232c+v249: observa <video>/<source> criados depois + src
                          // setado via propriedade (o player monta o elemento após
                          // o recap; rcWatchVideo cobre play/playing/pause/error).
                          try{
                            const obs=new MutationObserver(function(muts){
                              muts.forEach(function(m){
                                m.addedNodes.forEach(function(n){
                                  try{
                                    const tag=n.tagName||'';
                                    if(tag==='VIDEO'){ rcWatchVideo(n); }
                                    if(n.querySelectorAll){ Array.from(n.querySelectorAll('video')).forEach(rcWatchVideo); }
                                    const src=n.src||n.currentSrc||'';
                                    console.log('[HOOK] dom-add tag='+tag+' src='+String(src).slice(0,200)+' html='+String(n.outerHTML||'').slice(0,300).replace(/\n/g,' '));
                                  }catch(_){}
                                });
                                if(m.type==='attributes' && m.target.tagName==='VIDEO'){
                                  try{ console.log('[HOOK] video-attr '+m.attributeName+'='+String(m.target.getAttribute(m.attributeName)).slice(0,200)); }catch(_){}
                                }
                              });
                            });
                            obs.observe(document.documentElement||document.body, {childList:true, subtree:true, attributes:true, attributeFilter:['src','currentSrc']});
                            console.log('[HOOK] mutation-observer installed');
                          }catch(e){ console.log('[HOOK] observer-err '+e.message); }
                          try{
                            const origCreate=document.createElement.bind(document);
                            document.createElement=function(tag){
                              const el=origCreate(tag);
                              if(String(tag).toLowerCase()==='video' || String(tag).toLowerCase()==='source'){
                                console.log('[HOOK] createElement '+tag);
                                try{
                                  const desc=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src')||Object.getOwnPropertyDescriptor(HTMLVideoElement.prototype,'src');
                                  if(desc&&desc.set){
                                    let origSet=desc.set;
                                    Object.defineProperty(el,'src',{set:function(v){ console.log('[HOOK] video.src set '+String(v).slice(0,300)); return origSet.call(this,v); }, get:desc.get, configurable:true});
                                  }
                                }catch(_){}
                              }
                              return el;
                            };
                          }catch(e){ console.log('[HOOK] createElement-hook-err '+e.message); }
                          console.log('[HOOK] installed href='+location.href.slice(0,120));
                        }catch(e){ console.log('[HOOK] install-err '+e.message); }
                    })();""".trimIndent()
                    webViewClient = object : WebViewClient() {
                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            Log.w(TAG, "[CF_WV] onRenderProcessGone seguro acionado (didCrash=${detail?.didCrash()})")
                            try {
                                (view?.parent as? ViewGroup)?.removeView(view)
                                view?.destroy()
                            } catch (_: Throwable) {}
                            return true
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            try { view?.evaluateJavascript(CloudflareSolver.ANTI_DETECTION_JS, null) } catch(_: Throwable) {}
                            try { view?.evaluateJavascript(hookJs, null) } catch(_: Throwable) {}
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            CookieManager.getInstance().flush()
                            try { view?.evaluateJavascript(CloudflareSolver.ANTI_DETECTION_JS, null) } catch(_: Throwable) {}
                            try { view?.evaluateJavascript(hookJs, null) } catch(_: Throwable) {}
                            // v253: garante que o Service Worker do scope principal existe
                            // mesmo após redirect do server.php — re-regista silencioso
                            try { view?.evaluateJavascript("try{if('serviceWorker' in navigator && !navigator.serviceWorker.controller) navigator.serviceWorker.register('/sw.js').catch(function(){});}catch(e){}", null) } catch (_: Throwable) {}
                            pageReady.set(true)
                            Log.d(TAG, "[PROXY] onPageFinished url=$finishedUrl")
                        }

                        // v229e: deixa a navegação pós-click ACONTECER (não contém) mas
                        // marca para reemitir a URL original se a query se perder —
                        // provado 12:55: click -> server.php? (contido = player congelado,
                        // só bundle.js+jquery.js, nenhuma API, 45s em vão). Sem contenção,
                        // o fluxo segue (server.php? -> sessão -> player real); o reload
                        // com query params (v229) recupera se cair em 520.
                        // (Contain removido; onPageFinished com query vazia só loga.)
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString().orEmpty()
                            if (u.contains("server.php?", true) && captured.get().not()) {
                                Log.i(TAG, "[PROXY] navegação pós-click liberada: $u")
                            }
                            return super.shouldOverrideUrlLoading(view, request)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val u = request.url.toString()
                            // v232: loga TODA navegação/requisição do server.php (não só .api)
                            // — o body script pode fazer GET a server.php?action=... sem sufixo .api.
                            if (!u.contains("google", true) && !u.contains("disqus", true) && !u.contains("facebook", true) && !u.contains("gstatic", true)) {
                                // evita spam de fonte/css mas mantém XHR/fetch visíveis
                                val isApi = u.contains(".api", true) || u.contains("query", true) ||
                                    u.contains("bundle", true) || u.contains("serverforms", true) ||
                                    u.contains("dt.", true) || u.contains("getvid", true) ||
                                    u.contains("getlink", true) || u.contains("redirect", true) ||
                                    u.contains("server.php", true) || u.contains("__RC__", true) ||
                                    u.contains("proxy", true) || u.contains(".m3u8", true) || u.contains(".mp4", true)
                                if (isApi || request.method != "GET" || u.contains("player3", true)) {
                                    Log.i(TAG, "[PROXY_REQ] ${request.method} ${if (request.isForMainFrame) "MAIN " else ""}${u.take(320)}")
                                }
                                // v242 (Null_Pointer, teste c): snapshot de Set-Cookie +
                                // redirect no ponto de interceptação. WebResourceRequest
                                // não expõe response headers (só request headers via
                                // requestHeaders) — loga o que é visível (método, URL,
                                // headers de request) e o veredito do replay OkHttp
                                // (teste d) cobre o lado response/set-cookie.
                                if (isApi) {
                                    try {
                                        val rh = request.requestHeaders?.entries?.joinToString(";") { "${it.key}=${it.value.take(60)}" }?.take(300).orEmpty()
                                        Log.i(TAG, "[REQH] ${request.method} ${u.take(200)} || reqHeaders=$rh")
                                    } catch (_: Throwable) {}
                                }
                            }
                                   val isMediaStream = (u.contains("__RC__/proxy", true) || u.contains("/proxy?src=", true) ||
                                u.contains("p12-common-sign", true) || u.contains("xn--l", true) ||
                                u.contains("neosoro.gq", true) || u.contains("tos-alisg", true) ||
                                u.contains("/proxy?container=", true) || u.contains("container=videos", true) ||
                                (u.contains(".mp4", true) && !u.contains(".jpg") && !u.contains(".png")) ||
                                u.contains(".m3u8", true) || u.contains("/ondemand/", true) || u.contains("/videos/", true)) &&
                                !u.contains("disqus", true) && !u.contains("chatango", true) && !u.contains("google", true)

                            if (isMediaStream && !captured.get()) {
                                streamUrl = u
                                captured.set(true)
                                captureHolder.set(true)
                                Log.i(TAG, "[PROXY] Stream capturado via shouldInterceptRequest: ${u.take(180)}")
                                try {
                                    val ctx2 = com.lagradost.cloudstream3.CommonActivity.activity ?: CommonActivity.activity?.applicationContext
                                    ctx2?.let { c -> java.io.File(c.filesDir, "redecanais_af_last_stream_url.txt").writeText(u) }
                                } catch (_: Throwable) {}
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                    }
                }
                wv = view
                webView = view
                rootLayout.addView(view, 0)
                // v253: aquece domínio principal antes do server.php — reproduz harness quente
                // O valor de retorno é descartado (warm é best-effort); runCaptureLoop usa wv local.
                try { view.loadUrl("https://redecanais.af/") } catch (_: Throwable) { view.loadUrl(serverPhpUrl) }
                // breve janela para controller ficar activated antes de trocar para server.php
                // o loop esperará SW de modo explícito, mas este pre-warm já evita Just a moment...
            } catch (e: Throwable) {
                Log.e(TAG, "[PROXY] Erro ao criar WebView: ${e.message}")
            }
        }
        // v253 adaptação ao plugin: mesmo WebView aquece main domain, depois navega ao server.php
        withContext(Dispatchers.Main) {
            try {
                val cur = wv?.url.orEmpty()
                if (!cur.contains("server.php", true)) {
                    // entra no loop esperando SW (2s) e depois troca URL; log para diagnóstico harness
                    Log.i(TAG, "[WARM] pre-load main domain antes de server.php url=$cur")
                    delay(1200)
                    wv?.loadUrl(serverPhpUrl)
                }
            } catch (_: Throwable) {}
        }
        return runCaptureLoop(
            wvRef = { wv },
            serverPhpUrl = serverPhpUrl,
            detailUrl = detailUrl,
            budgetMs = budgetMs,
            captured = captured,
            pageReady = pageReady,
            clickDone = clickDone,
            captureHolder = captureHolder,
            tagSuffix = ""
        )
    }

    /**
     * v250: o MESMO loop de captura canônica (recap -> play -> playing ->
     * intercept), mas operando sobre um WebView JÁ EXISTENTE (o único).
     * Reconfigura clients, navega para serverPhpUrl e roda o loop — sem criar
     * nem destruir WebView. Retorna URL local ou null (sem shutdown: o
     * StreamResolver decide o ciclo de vida do único).
     */
    /**
     * v251: registra SW de forma covarde — se já estiver instalado sai;
     * no nível do WebView (API 24+) o ServiceWorkerController global
     * cobre todos os WebViews do processo, então 1 instalação vale o
     * jar inteiro.
     */
    private fun ensureSWFast() { try { ensureServiceWorker() } catch (_: Throwable) {} }

    /**
     * v253: adaptação ao plugin via browser-harness — expõe o WebView único ao harness
     * (WebView.setWebContentsDebuggingEnabled). O harness raspa o frame em CDP
     * (adb forward 9223) e a URL raiz do vídeo é extraída via currentSrc/shouldIntercept.
     * Usado quando se quer debugar o MESMO WebView do plugin pelo browser-harness.
     */
    fun harnessWebView(): WebView? = synchronized(this) { webView }

    private fun buildCaptureHookJs(): String = """(function(){
                        if(window.__rcHook) return;
                        window.__rcHook=true;
                        try{
                          window.__rcCaptured='';
                          window.__rcCapturedRawLen=0;
                          window.__rcLastFetchUrl='';
                          window.__rcLastFetchBody='';
                          window.__rcLastFetchCt='';
                          window.__rcLastFetchStatus=0;
                          window.__rcBodies=[];
                          window.__rcFetchCount=0;
                          window.__rcLastApi=''; window.__rcApiHdrs=''; window.__rcApiLog=[];
                          const ofetch=window.fetch;
                          if(ofetch) window.fetch=function(u,o){
                            const urlStr=String(u).slice(0,400);
                            window.__rcLastFetchUrl=urlStr;
                            try{
                              if(urlStr.indexOf('.api')>=0 && window.__rcApiLog.length<30){
                                const hdrs=(o&&o.headers)?JSON.stringify(o.headers).slice(0,400):'{}';
                                window.__rcLastApi=urlStr; window.__rcApiHdrs=hdrs+' method='+(o&&o.method||'GET');
                                window.__rcApiLog.push({url:urlStr, hdrs:hdrs, m:(o&&o.method||'GET'), t:Date.now()});
                              }
                            }catch(_){}
                            try{ console.log('[HOOK] fetch '+urlStr+' opts='+JSON.stringify(o||{}).slice(0,250)+' cookies='+document.cookie.slice(0,200)); }catch(_){}
                            const p=ofetch.apply(this, arguments);
                            try{
                              p.then(function(r){
                                try{
                                  const status=r.status, loc=r.headers.get('location')||r.headers.get('Location')||'';
                                  const ct=r.headers.get('content-type')||'';
                                  window.__rcLastFetchStatus=status;
                                  window.__rcLastFetchCt=ct;
                                  console.log('[HOOK] fetch-resp '+status+' ct='+ct.slice(0,60)+' loc='+String(loc).slice(0,250)+' url='+urlStr.slice(0,200));
                                  const contentLength=parseInt(r.headers.get('content-length')||'0',10)||0;
                                  const isMetadata=urlStr.indexOf('serverforms.api')>=0||urlStr.indexOf('dt.api')>=0;
                                  if(!isMetadata||contentLength>1048576) return;
                                  const cl=r.clone();
                                  cl.text().then(function(t){
                                    if(t.length>1048576){ console.log('[HOOK] fetch-body skipped oversized metadata len='+t.length); return; }
                                    window.__rcFetchCount++;
                                    window.__rcLastFetchBody=t;
                                    try{ window.__rcBodies.push({url:urlStr, ct:ct, status:status, body:t.slice(0,3000)}); if(window.__rcBodies.length>20) window.__rcBodies.shift(); }catch(_){}
                                    const hasProxy=t.indexOf('__RC__/proxy')>=0||t.indexOf('/proxy?src=')>=0||t.indexOf('tos-alisg')>=0||t.indexOf('container=videos')>=0||t.indexOf('.m3u8')>=0||t.indexOf('.mp4')>=0||t.indexOf('https://')>=0;
                                    const preview=t.slice(0,900).replace(/\n/g,' ').replace(/\r/g,'');
                                    console.log('[HOOK] fetch-body #'+window.__rcFetchCount+' len='+t.length+' ct='+ct.slice(0,40)+' proxy='+hasProxy+' url='+urlStr.slice(0,120)+' preview='+preview);
                                    if(urlStr.indexOf('serverforms')>=0 || urlStr.indexOf('dt.api')>=0){
                                      console.log('[HOOK] serverforms payload len='+t.length+' body='+t.slice(0,1200).replace(/\n/g,' '));
                                      try{
                                        const j=JSON.parse(t);
                                        console.log('[HOOK] serverforms JSON keys='+Object.keys(j).join(',')+' vals='+JSON.stringify(j).slice(0,900));
                                        if(j.e18b73c9 && Array.isArray(j.e18b73c9) && j.e18b73c9.length===0){
                                          console.log('[HOOK] TUNEL_VAZIO serverforms retornou e18b73c9=[] (código interno 204) — origem não determinada; pode ser sessão, disponibilidade ou política do servidor');
                                        }
                                        if(j.c4a0f6) console.log('[HOOK] c4a0f6 len='+(String(j.c4a0f6).length)+' preview='+String(j.c4a0f6).slice(0,150));
                                      }catch(e){ console.log('[HOOK] serverforms JSON parse err '+e.message); }
                                    }
                                    try{
                                      let found='';
                                      const m=t.match(/https?:\/\/[^\s"'\\]+\.(?:m3u8|mp4)[^\s"'\\]*/i);
                                      if(m) found=m[0];
                                      if(!found){ const m2=t.match(/https?:\/\/[^\s"'\\]*tos-alisg[^\s"'\\]*/i); if(m2) found=m2[0]; }
                                      if(!found){ const m3=t.match(/https?:\/\/[^\s"'\\]*\/proxy\?container=[^\s"'\\]*/i); if(m3) found=m3[0]; }
                                      if(!found){ const m4=t.match(/https?:\/\/[^\s"'\\]*__RC__[^\s"'\\]*/i); if(m4) found=m4[0]; }
                                      if(!found){
                                        try{
                                          const j=JSON.parse(t);
                                          const vals=JSON.stringify(j);
                                          const m5=vals.match(/https?:\/\/[^\s"'\\]+\.(?:m3u8|mp4)[^\s"'\\]*/i);
                                          if(m5) found=m5[0];
                                          if(!found){ const m6=vals.match(/https?:\\\/\\\/[^\s"'\\]{20,}/); if(m6) found=m6[0].replace(/\\\//g,'/'); }
                                          if(!found && vals.indexOf('https')>=0){ const m7=vals.match(/https?:[^\s"'\\]{15,}/); if(m7) found=m7[0].replace(/\\\//g,'/'); }
                                          if(!found){ for(const k of Object.keys(j)){ const v=j[k]; if(typeof v==='string' && v.length>20){ try{ const dec=atob(v); if(dec.indexOf('http')>=0) { found=dec.match(/https?:\/\/[^\s"'\\]+/)?.[0]||''; if(found) break; } }catch(_){} } } }
                                        }catch(_){}
                                      }
                                      if(found){ window.__rcCaptured=found; window.__rcCapturedRawLen=t.length; console.log('[HOOK] captured stream '+found.slice(0,320)); }
                                      else if(hasProxy){ window.__rcCaptured=t.slice(0,800); console.log('[HOOK] captured raw proxy body len='+t.length); }
                                      window.__rcLastFetchBody=t.slice(0,3000);
                                    }catch(e){ console.log('[HOOK] capture-err '+e.message); }
                                  }).catch(function(e){ console.log('[HOOK] fetch-body-err '+e.message); });
                                }catch(e){ console.log('[HOOK] fetch-resp-err '+e.message); }
                                return r;
                              }).catch(function(e){ console.log('[HOOK] fetch-reject '+e.message+' url='+urlStr.slice(0,200)); });
                            }catch(_){}
                            return p;
                          };
                          const oOpen=XMLHttpRequest.prototype.open;
                          XMLHttpRequest.prototype.open=function(m,u){ try{ console.log('[HOOK] XHR open '+m+' '+String(u).slice(0,260)); }catch(_){} return oOpen.apply(this, arguments); };
                          const oSend=XMLHttpRequest.prototype.send;
                          XMLHttpRequest.prototype.send=function(b){
                            try{ console.log('[HOOK] XHR send '+(b?String(b).slice(0,300):'-')); }catch(_){}
                            try{
                              const xhr=this;
                              const prev=xhr.onload;
                              const onLoad=function(){ try{ const t=xhr.responseText||''; const hasProxy=t.indexOf('__RC__/proxy')>=0||t.indexOf('/proxy?src=')>=0||t.indexOf('.m3u8')>=0; console.log('[HOOK] XHR-resp status='+xhr.status+' len='+t.length+' proxy='+hasProxy+' preview='+t.slice(0,400).replace(/\n/g,' ')); }catch(e){ console.log('[HOOK] XHR-resp-err '+e.message); } if(prev) return prev.apply(this, arguments); };
                              if(xhr.addEventListener) xhr.addEventListener('load', onLoad, false); else xhr.onload=onLoad;
                            }catch(_){}
                            return oSend.apply(this, arguments);
                          };
                          document.addEventListener('submit', function(e){ try{ console.log('[HOOK] submit action='+(e.target&&e.target.action||'')+' method='+(e.target&&e.target.method||'')); }catch(_){} }, true);
                          window.addEventListener('error', function(e){ try{ console.log('[HOOK] window-error '+e.message+' @'+(e.filename||'').slice(0,80)+':'+e.lineno); }catch(_){} }, true);
                          window.addEventListener('unhandledrejection', function(e){ try{ console.log('[HOOK] unhandledrejection '+(e.reason&&e.reason.message||String(e.reason)).slice(0,300)); }catch(_){} }, true);
                          window.addEventListener('beforeunload', function(){ try{ console.log('[HOOK] beforeunload href='+location.href.slice(0,120)); }catch(_){} }, true);
                          window.__rcPlaySrc='';
                          function rcWatchVideo(v){
                            if(!v||v.__rcWatched) return;
                            v.__rcWatched=true;
                            ['play','playing','pause','error','stalled','waiting','loadstart'].forEach(function(ev){
                              v.addEventListener(ev, function(){
                                try{
                                  const s=v.currentSrc||v.src||'';
                                  if((ev==='play'||ev==='playing'||ev==='loadstart')&&s) window.__rcPlaySrc=s;
                                  console.log('[HOOK] video-'+ev+' ready='+v.readyState+' net='+v.networkState+' paused='+v.paused+' src='+String(s).slice(0,250));
                                }catch(_){}
                              });
                            });
                          }
                          try{ Array.from(document.querySelectorAll('video')).forEach(rcWatchVideo); }catch(_){}
                          document.addEventListener('click', function(e){
                            try{
                              const t=e.target;
                              console.log('[HOOK] click tag='+(t&&t.tagName||'')+' id='+(t&&t.id||'')+' class='+(t&&t.className||'').toString().slice(0,60)+' prevented='+e.defaultPrevented+' trusted='+e.isTrusted);
                            }catch(_){}
                          }, true);
                          try{
                            const obs=new MutationObserver(function(muts){
                              muts.forEach(function(m){
                                m.addedNodes.forEach(function(n){
                                  try{
                                    const tag=n.tagName||'';
                                    if(tag==='VIDEO'){ rcWatchVideo(n); }
                                    if(n.querySelectorAll){ Array.from(n.querySelectorAll('video')).forEach(rcWatchVideo); }
                                    const src=n.src||n.currentSrc||'';
                                    console.log('[HOOK] dom-add tag='+tag+' src='+String(src).slice(0,200)+' html='+String(n.outerHTML||'').slice(0,300).replace(/\n/g,' '));
                                  }catch(_){}
                                });
                                if(m.type==='attributes' && m.target.tagName==='VIDEO'){
                                  try{ console.log('[HOOK] video-attr '+m.attributeName+'='+String(m.target.getAttribute(m.attributeName)).slice(0,200)); }catch(_){}
                                }
                              });
                            });
                            obs.observe(document.documentElement||document.body, {childList:true, subtree:true, attributes:true, attributeFilter:['src','currentSrc']});
                            console.log('[HOOK] mutation-observer installed');
                          }catch(e){ console.log('[HOOK] observer-err '+e.message); }
                          try{
                            const origCreate=document.createElement.bind(document);
                            document.createElement=function(tag){
                              const el=origCreate(tag);
                              if(String(tag).toLowerCase()==='video' || String(tag).toLowerCase()==='source'){
                                console.log('[HOOK] createElement '+tag);
                                try{
                                  const desc=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src')||Object.getOwnPropertyDescriptor(HTMLVideoElement.prototype,'src');
                                  if(desc&&desc.set){
                                    let origSet=desc.set;
                                    Object.defineProperty(el,'src',{set:function(v){ console.log('[HOOK] video.src set '+String(v).slice(0,300)); return origSet.call(this,v); }, get:desc.get, configurable:true});
                                  }
                                }catch(_){}
                              }
                              return el;
                            };
                          }catch(e){ console.log('[HOOK] createElement-hook-err '+e.message); }
                          console.log('[HOOK] installed href='+location.href.slice(0,120));
                        }catch(e){ console.log('[HOOK] install-err '+e.message); }
                    })();"""

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun captureAndServeReuse(
        existing: WebView,
        serverPhpUrl: String,
        budgetMs: Long = CAPTURE_TIMEOUT_MS,
        detailUrl: String = ""
    ): String? {
        val captured = AtomicBoolean(false)
        val pageReady = AtomicBoolean(false)
        val clickDone = AtomicBoolean(false)
        val captureHolder = AtomicBoolean(false)
        val hookJs = buildCaptureHookJs()
        withContext(Dispatchers.Main) {
            try {
                ensureSWFast()
                try { existing.settings.domStorageEnabled = true } catch (_: Throwable) {}
                try { @Suppress("DEPRECATION") existing.settings.databaseEnabled = true } catch (_: Throwable) {}
                try { existing.settings.javaScriptEnabled = true } catch (_: Throwable) {}
                existing.stopLoading()
                pageReady.set(false)
                // v254: WebView único — reinstala clients/hook completos (mesmo de captureAndServe)
                // O stub anterior só fazia loadUrl sem WebViewClient/hook, por isso watch.php
                // reaproveitado ficava btn=false/rcFn=false eternamente.
                existing.webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        view?.evaluateJavascript(CloudflareSolver.ANTI_DETECTION_JS, null)
                    }
                    override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                        try {
                            val m = msg?.message().orEmpty()
                            if (m.contains("[HOOK]", true) || m.contains("Uncaught", true) || m.contains("Failed to load", true) || m.contains("Error", true)) {
                                Log.i(TAG, "[HOOK_JS] $m @ ${msg?.sourceId()?.take(80)}:${msg?.lineNumber()}")
                            }
                        } catch (_: Throwable) {}
                        return super.onConsoleMessage(msg)
                    }
                }
                existing.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                        Log.w(TAG, "[CF_WV] onRenderProcessGone seguro acionado (didCrash=${detail?.didCrash()})")
                        try { (view?.parent as? ViewGroup)?.removeView(view); view?.destroy() } catch (_: Throwable) {}
                        return true
                    }
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        try { view?.evaluateJavascript(CloudflareSolver.ANTI_DETECTION_JS, null) } catch(_: Throwable) {}
                        try { view?.evaluateJavascript(hookJs, null) } catch(_: Throwable) {}
                    }
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        super.onPageFinished(view, finishedUrl)
                        CookieManager.getInstance().flush()
                        try { view?.evaluateJavascript(CloudflareSolver.ANTI_DETECTION_JS, null) } catch(_: Throwable) {}
                        try { view?.evaluateJavascript(hookJs, null) } catch(_: Throwable) {}
                        try { view?.evaluateJavascript("try{if('serviceWorker' in navigator && !navigator.serviceWorker.controller) navigator.serviceWorker.register('/sw.js').catch(function(){});}catch(e){}", null) } catch (_: Throwable) {}
                        pageReady.set(true)
                        Log.d(TAG, "[PROXY] onPageFinished url=$finishedUrl [REUSE]")
                    }
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val u = request?.url?.toString().orEmpty()
                        if (u.contains("server.php?", true) && captured.get().not()) Log.i(TAG, "[PROXY] navegação pós-click liberada: $u [REUSE]")
                        return super.shouldOverrideUrlLoading(view, request)
                    }
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                        val u = request.url.toString()
                        if (!u.contains("google", true) && !u.contains("disqus", true) && !u.contains("facebook", true) && !u.contains("gstatic", true)) {
                            val isApi = u.contains(".api", true) || u.contains("query", true) || u.contains("bundle", true) || u.contains("serverforms", true) || u.contains("dt.", true) || u.contains("getvid", true) || u.contains("getlink", true) || u.contains("redirect", true) || u.contains("server.php", true) || u.contains("__RC__", true) || u.contains("proxy", true) || u.contains(".m3u8", true) || u.contains(".mp4", true)
                            if (isApi || request.method != "GET" || u.contains("player3", true)) Log.i(TAG, "[PROXY_REQ] ${request.method} ${if (request.isForMainFrame) "MAIN " else ""}${u.take(320)} [REUSE]")
                            if (isApi) { try { val rh = request.requestHeaders?.entries?.joinToString(";") { "${it.key}=${it.value.take(60)}" }?.take(300).orEmpty(); Log.i(TAG, "[REQH] ${request.method} ${u.take(200)} || reqHeaders=$rh [REUSE]") } catch (_: Throwable) {} }
                        }
                        val isMediaStream = (u.contains("__RC__/proxy", true) || u.contains("/proxy?src=", true) || u.contains("p12-common-sign", true) || u.contains("xn--l", true) || u.contains("neosoro.gq", true) || u.contains("tos-alisg", true) || u.contains("/proxy?container=", true) || u.contains("container=videos", true) || (u.contains(".mp4", true) && !u.contains(".jpg") && !u.contains(".png")) || u.contains(".m3u8", true) || u.contains("/ondemand/", true) || u.contains("/videos/", true)) && !u.contains("disqus", true) && !u.contains("chatango", true) && !u.contains("google", true)
                        if (isMediaStream && !captured.get()) {
                            streamUrl = u; captured.set(true); captureHolder.set(true)
                            Log.i(TAG, "[PROXY] Stream capturado via shouldInterceptRequest [REUSE]: ${u.take(180)}")
                            try { val ctx2 = com.lagradost.cloudstream3.CommonActivity.activity ?: CommonActivity.activity?.applicationContext; ctx2?.let { c -> java.io.File(c.filesDir, "redecanais_af_last_stream_url.txt").writeText(u) } } catch (_: Throwable) {}
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                Log.i(TAG, "[WV_SINGLE] reuse canônica: ${serverPhpUrl.take(150)} [REUSE]")
                existing.loadUrl(serverPhpUrl)
            } catch (e: Throwable) {
                Log.e(TAG, "[WV_SINGLE] reuse err=${e.message?.take(120)}")
                return@withContext
            }
        }
        synchronized(this) { webView = existing }
        return runCaptureLoop(
            wvRef = { synchronized(this) { webView } },
            serverPhpUrl = serverPhpUrl,
            detailUrl = detailUrl,
            budgetMs = budgetMs,
            captured = captured,
            pageReady = pageReady,
            clickDone = clickDone,
            captureHolder = captureHolder,
            tagSuffix = "[REUSE]"
        )
    }

    /**
     * v250: loop de captura compartilhado (extraído do captureAndServe).
     * Clica recap -> força video.play() -> espera playing -> intercepta a URL
     * raiz durante a reprodução. NÃO cria/destrói WebView (wvRef fornece o jar).
     * NÃO dá shutdown (ciclo de vida é do StreamResolver).
     */
    private suspend fun runCaptureLoop(
        wvRef: () -> WebView?,
        serverPhpUrl: String,
        detailUrl: String,
        budgetMs: Long,
        captured: AtomicBoolean,
        pageReady: AtomicBoolean,
        clickDone: AtomicBoolean,
        captureHolder: AtomicBoolean,
        tagSuffix: String
    ): String? {

        // ===== Loop de captura v123: clica no recap assim que o DOM estiver pronto =====
        // v123: não espera mais onPageFinished inteiro — o botão .captcha_button existe antes.
        // Poll 200ms, fallbacks por tempo real (3s/6s/12s) em vez de System.currentTimeMillis() % N.
        var reloadCount = 0
        var lastClickMs = 0L
        var lastReloadCheckMs = 0L
        var lastDirectFallbackMs = 0L
        var clickDoneAtMs = 0L
        // v229c: player montado (btn+rcPreloadPlayer visíveis no diag) — enquanto true,
        // nenhum reload: o bundle precisa de >10s após o click para montar o __RC__/proxy.
        val playerMounted = AtomicBoolean(false)
        // v125: o fallback direto (rcPreloadPlayer manual) roda NO MÁXIMO uma vez por ciclo —
        // chamadas repetidas a cada 3s podem reiniciar a montagem do player (serverforms.api)
        var directFallbackDone = false
        val startMs = System.currentTimeMillis()
        // v252: Service Worker. O site usa /sw.js para enriquecer serverforms.api
        // (__RC__/proxy). No browser-harness com cache o controller já estava
        // activated e o mesmo frame tocou ready=4; no harness virgem (controller=false)
        // e no plugin (click em 250ms) o bundle buscou serverforms ANTES do SW e
        // caiu em 204/e18b73c9=[]. Espera o SW ficar activated antes do tap.
        val swReady = AtomicBoolean(false)
        var lastSwCheckMs = 0L
        var swWaitLogged = false
        val swWaitMaxMs = 9000L
        // v126: orçamento por tentativa cortado p/ 45s — o framework cancela loadLinks em
        // ~120s (TimeoutCancellationException), então 2 tentativas (RCServer01 + RCFServer2)
        // precisam caber em 90s. Antigo 120s por tentativa abortava antes do fallback.
        // v237: budget por tentativa (25s nas 3 primeiras variantes, 45s na última) +
        // early-exit: 3x serverforms 204+e18b73c9=[] seguidos => túnel vazio, aborta
        // sem esperar o timeout (economiza ~20s por variante morta).
        // v252b: capta o tempo real preso na barreira do SW para subtrair do
        // budget de captura — sem isso a primeira variante (12s de capture)
        // gasta 2s parada no SW e só sobra 10s; com retries do impostado
        // não dá. Subtrai o elapsed já preso.
        val budget = budgetMs.coerceIn(10000L, CAPTURE_TIMEOUT_MS)
        val empty204Count = java.util.concurrent.atomic.AtomicInteger(0)
        var lastEmpty204Sig = ""
        withTimeoutOrNull(budget) {
            while (!captured.get() && empty204Count.get() < 3) {
                delay(POLL_INTERVAL_MS) // v123: 200ms (era 500ms)
                val now = System.currentTimeMillis()
                val wvNow = wvRef() ?: continue

                // v252: espera o Service Worker ficar activated ANTES do recap.
                // Sem isso o bundle faz serverforms antes do SW enriquecer a request.
                if (!swReady.get() && !captured.get() && now - lastSwCheckMs >= 400L) {
                    lastSwCheckMs = now
                    withContext(Dispatchers.Main) {
                        try {
                            wvNow.evaluateJavascript(
                                """(function(){
                                    try{
                                      if (!('serviceWorker' in navigator)) return 'sw-unsupported';
                                      var c = navigator.serviceWorker.controller;
                                      var n = (c && c.state) || '';
                                      // força registro se o site não disparou (WebView navegado sem /sw.js anterior)
                                      if (!c && 'serviceWorker' in navigator && !window.__swRegTry) {
                                        window.__swRegTry = true;
                                        try { navigator.serviceWorker.register('/sw.js').catch(function(){}); } catch(_){}
                                      }
                                      if (c && n === 'activated') return 'sw-ready:' + n;
                                      return 'sw-wait:' + (n || 'no-ctrl');
                                    }catch(e){ return 'sw-err:'+e.message; }
                                })();""".trimIndent()
                            ) { res ->
                                val sw = res?.removeSurrounding("\"").orEmpty()
                                if (sw.startsWith("sw-ready")) {
                                    swReady.set(true)
                                    Log.i(TAG, "[PROXY_SW] $sw — liberando recap")
                                } else if (System.currentTimeMillis() - startMs >= swWaitMaxMs) {
                                    // não bloqueia indefinidamente — após 9s libera mesmo sem SW (túnel pode funcionar sem; melhor tentar que travar)
                                    if (!swReady.get()) { swReady.set(true); Log.w(TAG, "[PROXY_SW] timeout 9s ($sw) — prosseguindo sem SW (pode cair em 204)") }
                                } else if (!swWaitLogged && System.currentTimeMillis() - startMs > 2000L) {
                                    swWaitLogged = true; Log.i(TAG, "[PROXY_SW] aguardando SW ($sw)...")
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // 1) Clica no recap assim que o DOM estiver pronto (não espera onPageFinished).
                // v123: retry a cada CLICK_RETRY_MS até o click ser efetivo ('click') —
                // se rodar antes do DOM existir, retorna 'wait' e tenta de novo.
                // v125: espera window.rcPreloadPlayer existir ANTES de clicar (como o
                // browser-harness da Fase 51 fazia) — se o script do player ainda não
                // carregou, o handler do botão não existe e o clique não dispara nada.
                // v229d: NUNCA chama window.rcPreloadPlayer() diretamente — o diag provou
                // que a chamada direta rejeita com "Error: 7a2f" (~250ms depois) e envenena
                // o estado interno do player (nunca mais monta o __RC__/proxy). Só o
                // handler do próprio botão sabe os args/contexto certos (token recap).
                // v252: só clica depois que o SW ficou ready (ou timeout de 9s).
                if (!swReady.get()) {
                    // entra no próximo tick quando o SW liberar
                } else if (!clickDone.get() && captured.get().not() && now - lastClickMs >= CLICK_RETRY_MS) {
                    lastClickMs = now
                    withContext(Dispatchers.Main) {
                        try {
                            wvNow.evaluateJavascript(
                                """(function() {
                                    const cfIframe = document.querySelector("iframe[src*='challenges.cloudflare.com']");
                                    if (cfIframe) {
                                        const rect = cfIframe.getBoundingClientRect();
                                        if (rect.width > 0 && rect.height > 0) {
                                            return 'cf:' + (rect.left + 35) + ':' + (rect.top + rect.height/2);
                                        }
                                    }
                                    const b = document.getElementById('submit') || document.querySelector('.captcha_button');
                                    const hasFn = (typeof window.rcPreloadPlayer === 'function');
                                    if (!b || !hasFn) return 'wait';
                                    // v230: retorna coordenadas do botão para toque REAL via
                                    // dispatchTouchEvent — b.click() sintético não aciona o
                                    // handler do bundle (v229: 45s sem API após click).
                                    // v231: validação de visibilidade — offsetParent!==null,
                                    // rect dentro do viewport, w/h > 0 e scrollIntoView para
                                    // garantir layout resolvido. WebView MATCH_PARENT dá
                                    // viewport real (1x1 retornava coords fora da view).
                                    if (b.offsetParent === null) return 'hidden';
                                    b.scrollIntoView({block:'center'});
                                    const r = b.getBoundingClientRect();
                                    const cx = r.left + r.width/2, cy = r.top + r.height/2;
                                    if (r.width <= 0 || r.height <= 0) return 'zerosize';
                                    if (cx < 0 || cy < 0 || cx > window.innerWidth || cy > window.innerHeight) return 'offscreen:' + Math.round(cx) + ':' + Math.round(cy);
                                    return 'tap:' + cx + ':' + cy;
                                })();""".trimIndent()
                            ) { res ->
                                val r = res?.removeSurrounding("\"")
                                Log.i(TAG, "[PROXY] click recap -> $r")
                                // v230: toque REAL (DOWN+UP) nas coordenadas — serve tanto
                                // para o Turnstile (cf:x:y) quanto para o botão recap
                                // (tap:x:y). b.click() sintético não aciona o handler.
                                if (r?.startsWith("cf:") == true || r?.startsWith("tap:") == true) {
                                    val parts = r.split(":")
                                    val x = parts.getOrNull(1)?.toFloatOrNull() ?: 50f
                                    val y = parts.getOrNull(2)?.toFloatOrNull() ?: 50f
                                    val downTime = SystemClock.uptimeMillis()
                                    // v231: validação — coords CSS precisam cair dentro da
                                    // view nativa (coords fora = layout 1x1/scaling; o
                                    // dispatchTouchEvent seria descartado em silêncio).
                                    val vw = wvNow.width.toFloat()
                                    val vh = wvNow.height.toFloat()
                                    val scale = wvNow.scale
                                    val vx = x * scale
                                    val vy = y * scale
                                    if (vx < 0 || vy < 0 || vx > vw || vy > vh) {
                                        Log.w(TAG, "[PROXY] toque fora da view: css=($x,$y) scale=$scale view=(${vw}x$vh) — aguardando layout")
                                        lastClickMs = now // retry: não marca clickDone
                                    } else {
                                        Log.i(TAG, "[PROXY] toque válido: css=($x,$y) -> view=($vx,$vy) view=(${vw}x$vh)")
                                        // Sequência DOWN -> MOVE -> UP (gesto de toque real,
                                        // não tap instantâneo — o bundle pode validar movimento)
                                        val props = arrayOf(
                                            MotionEvent.PointerProperties().apply {
                                                id = 0
                                                toolType = MotionEvent.TOOL_TYPE_FINGER
                                            }
                                        )
                                        val coordsDown = arrayOf(
                                            MotionEvent.PointerCoords().apply {
                                                this.x = vx
                                                this.y = vy
                                                pressure = 1f
                                                size = 1f
                                            }
                                        )
                                        val eventDown = MotionEvent.obtain(
                                            downTime, downTime,
                                            MotionEvent.ACTION_DOWN, 1,
                                            props, coordsDown,
                                            0, 0, 1f, 1f, 0, 0, 0, 0
                                        ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                                        wvNow.dispatchTouchEvent(eventDown)
                                        eventDown.recycle()

                                        val coordsMove = arrayOf(
                                            MotionEvent.PointerCoords().apply {
                                                this.x = vx + 1f
                                                this.y = vy + 1f
                                                pressure = 1f
                                                size = 1f
                                            }
                                        )
                                        val eventMove = MotionEvent.obtain(
                                            downTime, downTime + 40,
                                            MotionEvent.ACTION_MOVE, 1,
                                            props, coordsMove,
                                            0, 0, 1f, 1f, 0, 0, 0, 0
                                        ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                                        wvNow.dispatchTouchEvent(eventMove)
                                        eventMove.recycle()

                                        val eventUp = MotionEvent.obtain(
                                            downTime, downTime + 80,
                                            MotionEvent.ACTION_UP,
                                            vx + 0.5f, vy + 0.5f,
                                            0f, 0f, 0, 1.0f, 1.0f, 0, 0
                                        ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                                        wvNow.dispatchTouchEvent(eventUp)
                                        eventUp.recycle()
                                    }
                                    if (r.startsWith("cf:")) {
                                        Log.i(TAG, "[PROXY] Turnstile checkbox clicado no server.php em ($x, $y)")
                                    } else {
                                        Log.i(TAG, "[PROXY] toque real no recap em ($x, $y)")
                                        clickDone.set(true)
                                        clickDoneAtMs = now
                                        // v231: prova do pós-click — 2s após o toque, espelha
                                        // o innerHTML do <body> na RAM (o outerHTML espelhado
                                        // no PROXY_STATE traz só <head>; os scripts recriam
                                        // o body — player, video, __RC__/proxy — via JS).
                                        wvNow.postDelayed({
                                            try {
                                                wvNow.evaluateJavascript(
                                                    """(function() {
                                                        try {
                                                            const b = document.body ? document.body.innerHTML : '';
                                                            const v = document.querySelector('video');
                                                            const vsrc = v ? (v.currentSrc || v.src || '') : '';
                                                            const inl = Array.from(document.querySelectorAll('script:not([src])')).map(s => s.textContent || '').join('\n');
                                                            // v232: fatiado em 3 partes de ~60KB (limite do
                                                            // evaluateJavascript/JNI estoura em ~120KB e
                                                            // trunca o retorno — o inline full tem ~290KB).
                                                            const p1 = inl.substring(0,60000), p2 = inl.substring(60000,120000), p3 = inl.substring(120000,180000);
                                                            return 'BODY len=' + b.length + ' video=' + (vsrc.substring(0,100) || 'none') + ' INLINE len=' + inl.length + ' ||BODYHTML||' + b.substring(0,20000) + '||P1||' + p1 + '||P2||' + p2 + '||P3||' + p3;
                                                        } catch(e) { return 'BODY ERR ' + e.message; }
                                                    })();""".trimIndent()
                                                ) { res2 ->
                                                    try {
                                                        val c2 = res2?.removeSurrounding("\"").orEmpty()
                                                        Log.i(TAG, "[PROXY_POSTCLICK] " + c2.substringBefore("||BODYHTML||").take(200))
                                                        fun unesc(s: String) = s.replace("\\u003C", "<").replace("\\u003E", ">")
                                                            .replace("\\\"", "\"").replace("\\n", "\n")
                                                        val bodyHtml = unesc(c2.substringAfter("||BODYHTML||", "").substringBefore("||INLINE||", ""))
                                                        if (bodyHtml.length > 200) {
                                                            CloudflareSolver.mirrorServerPhpHtml(serverPhpUrl + "#body", bodyHtml)
                                                        }
                                                        // v232: remonta o inline fatiado (P1+P2+P3) e grava
                                                        // em partes (mirror tem threshold mínimo).
                                                        val p1 = unesc(c2.substringAfter("||P1||", "").substringBefore("||P2||", ""))
                                                        val p2 = unesc(c2.substringAfter("||P2||", "").substringBefore("||P3||", ""))
                                                        val p3 = unesc(c2.substringAfter("||P3||", ""))
                                                        Log.i(TAG, "[PROXY_POSTCLICK] fatias inline: p1=${p1.length} p2=${p2.length} p3=${p3.length}")
                                                        if (p1.length > 5000) {
                                                            CloudflareSolver.mirrorServerPhpHtml(serverPhpUrl + "#inline", p1 + p2 + p3)
                                                        }
                                                    } catch (_: Throwable) {}
                                                }
                                            } catch (_: Throwable) {}
                                        }, 2000)
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // 2) Fallback: re-deriva as coordenadas do botão e re-toca 3s após
                // o toque inicial, se nada capturou. v229d: sem chamada direta
                // rcPreloadPlayer (Error: 7a2f); v230: sem b.click() sintético.
                // v231: validação de viewport + scrollIntoView, mesma do toque inicial.
                if (clickDone.get() && !captured.get() && !directFallbackDone && now - lastClickMs >= DIRECT_FALLBACK_MS && now - lastDirectFallbackMs >= DIRECT_FALLBACK_MS) {
                    lastDirectFallbackMs = now
                    directFallbackDone = true
                    withContext(Dispatchers.Main) {
                        try {
                            wvNow.evaluateJavascript(
                                """(function() {
                                    const b = document.getElementById('submit') || document.querySelector('.captcha_button');
                                    if (!b || b.offsetParent === null) return 'no-btn';
                                    b.scrollIntoView({block:'center'});
                                    const r = b.getBoundingClientRect();
                                    const cx = r.left + r.width/2, cy = r.top + r.height/2;
                                    if (r.width <= 0 || r.height <= 0) return 'zerosize';
                                    if (cx < 0 || cy < 0 || cx > window.innerWidth || cy > window.innerHeight) return 'offscreen';
                                    return 'retap:' + cx + ':' + cy;
                                })();""".trimIndent()
                            ) { res ->
                                val rr = res?.removeSurrounding("\"").orEmpty()
                                if (rr.startsWith("retap:")) {
                                    val parts = rr.split(":")
                                    val x = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                                    val y = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
                                    val downTime = SystemClock.uptimeMillis()
                                    val vw = wvNow.width.toFloat()
                                    val vh = wvNow.height.toFloat()
                                    val scale = wvNow.scale
                                    val vx = x * scale
                                    val vy = y * scale
                                    if (vx < 0 || vy < 0 || vx > vw || vy > vh) {
                                        Log.w(TAG, "[PROXY] fallback fora da view: css=($x,$y) view=(${vw}x$vh)")
                                    } else {
                                        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, vx, vy, 0.85f, 0.85f, 0, 1.0f, 1.0f, 0, 0)
                                            .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                                        wvNow.dispatchTouchEvent(down)
                                        down.recycle()
                                        val up = MotionEvent.obtain(downTime, downTime + 80, MotionEvent.ACTION_UP, vx + 0.5f, vy + 0.5f, 0f, 0f, 0, 1.0f, 1.0f, 0, 0)
                                            .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                                        wvNow.dispatchTouchEvent(up)
                                        up.recycle()
                                        Log.i(TAG, "[PROXY] recap re-tocado (fallback) css=($x,$y) view=($vx,$vy)")
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // 3) Poll via performance entries & DOM vídeo (fallback não-bloqueante)
                // v229: sonda de estado do player a cada ~5s (alimenta playerMounted).
                // v232e: sonda 100% dos fetch bodies (inclui serverforms vazio len=52)
                // para diagnóstico de túnel Redemovel caído — usa 2 polls simples com unescape.
                if (!captured.get()) {
                    val elapsed = now - startMs
                    val isDiagTick = elapsed > 0 && (elapsed / 5000L) != ((elapsed - POLL_INTERVAL_MS) / 5000L)
                    // fast-path: hook capturou https via fetch body
                    // v238: 204 contado UMA vez por poll (evaluate é async — o callback
                    // antigo incrementava a cada re-poll do mesmo body, esgotando 3/3
                    // com 1 único 204 real e abortando variante saudável em ~3s).
                    withContext(Dispatchers.Main) {
                        try {
                            wvNow.evaluateJavascript("""(function(){try{return ((window.__rcLastFetchUrl||'').slice(-60)+'[SEP]'+(window.__rcLastFetchBody||'').slice(0,300));}catch(e){return ''}})();""".trimIndent()) { r ->
                                val f = r?.removeSurrounding("\"").orEmpty()
                                    .replace("\\u003C", "<").replace("\\u003E", ">").replace("\\\"", "\"")
                                    .replace("\\n", " ")
                                try {
                                    if (f.contains("2fa806d3") && f.contains("204") && f.contains("e18b73c9")) {
                                        if (f != lastEmpty204Sig) {
                                            lastEmpty204Sig = f
                                            val n = empty204Count.incrementAndGet()
                                            Log.i(TAG, "[PROXY] túnel vazio 204 #$n/3 nesta variante — early-exit se persistir")
                                        }
                                    }
                                } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {}
                    }
                    // v249: lê também __rcPlaySrc (URL que o player usou no play)
                    // além de __rcCaptured — o stream nasce no play, não no recap.
                    withContext(Dispatchers.Main) {
                        try {
                            wvNow.evaluateJavascript("""(function(){try{return (window.__rcPlaySrc||'')+'[SEP]'+(window.__rcCaptured||'');}catch(e){return '';}})();""".trimIndent()) { r ->
                                val f = r?.removeSurrounding("\"").orEmpty()
                                    .replace("\\u003C", "<").replace("\\u003E", ">").replace("\\\"", "\"")
                                val playSrc = f.substringBefore("[SEP]").trim()
                                val hooked = f.substringAfter("[SEP]", "").trim()
                                val pick = when {
                                    playSrc.startsWith("http") -> playSrc
                                    hooked.contains("http", true) || hooked.contains("__RC__", true) ||
                                        hooked.contains("tos-alisg", true) || hooked.contains("/proxy", true) -> hooked
                                    else -> ""
                                }
                                if (pick.isNotBlank() && pick.length > 10 && !captured.get()) {
                                    streamUrl = pick
                                    captured.set(true)
                                    captureHolder.set(true)
                                    Log.i(TAG, "[PROXY] Stream capturado via play/hook: ${pick.take(250)}")
                                    try {
                                        val ctx = com.lagradost.cloudstream3.CommonActivity.activity ?: CommonActivity.activity?.applicationContext
                                        ctx?.let { c -> java.io.File(c.filesDir, "redecanais_af_last_stream_url.txt").writeText(pick) }
                                    } catch (_: Throwable) {}
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    withContext(Dispatchers.Main) {
                        try {
                            wvNow.evaluateJavascript("""(function(){try{return window.__rcCaptured||'';}catch(e){return '';}})();""".trimIndent()) { r ->
                                val f = r?.removeSurrounding("\"").orEmpty()
                                    .replace("\\u003C", "<").replace("\\u003E", ">").replace("\\\"", "\"")
                                if (f.isNotBlank() && f != "null" && f.length > 10 && !captured.get()) {
                                    val isUrl = f.contains("http", true) || f.contains("__RC__", true) || f.contains("tos-alisg", true) || f.contains("/proxy", true)
                                    if (isUrl || f.startsWith("http")) {
                                        streamUrl = f
                                        captured.set(true)
                                        captureHolder.set(true)
                                        Log.i(TAG, "[PROXY] Stream capturado via __rcCaptured: ${f.take(250)}")
                                        try {
                                            val ctx = com.lagradost.cloudstream3.CommonActivity.activity ?: CommonActivity.activity?.applicationContext
                                            ctx?.let { c -> java.io.File(c.filesDir, "redecanais_af_last_stream_url.txt").writeText(f) }
                                        } catch (_: Throwable) {}
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    // v232e: diagnóstico de túnel — loga último fetch body mesmo sem proxy
                    // (serverforms len=52 [] indica backend sem stream / RCIP expirado)
                    if (isDiagTick) {
                        withContext(Dispatchers.Main) {
                            try {
                                wvNow.evaluateJavascript("""(function(){
                                    try{
                                      const u=window.__rcLastFetchUrl||'';
                                      const b=(window.__rcLastFetchBody||'').slice(0,1200);
                                      const s=window.__rcLastFetchStatus||0;
                                      const ct=window.__rcLastFetchCt||'';
                                      const ck=document.cookie.slice(0,300);
                                      return 'url='+u.slice(0,180)+' | st='+s+' ct='+ct.slice(0,30)+' | ck='+ck+' | body='+b.replace(/\n/g,' ');
                                    }catch(e){return 'err '+e.message;}
                                })();""".trimIndent()) { r ->
                                    val s = r?.removeSurrounding("\"").orEmpty()
                                        .replace("\\u003C","<").replace("\\u003E",">").replace("\\\"","\"").replace("\\n"," ")
                                    Log.i(TAG, "[PROXY_DIAG] t=${elapsed}ms $s")
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                    // v249: o usuário está certo — o __RC__/proxy só nasce quando o
                    // <video> TOCA de verdade (autoplay/JS play() após o recap). O
                    // loop clicava no recap mas nunca apertava PLAY: o player monta
                    // thumb/pausado e nenhuma conexão de mídia é aberta (daí os
                    // 204 vazios — o backend só aloca origem quando o play começa).
                    // A cada diag tick (~5s) após o click: se houver <video> pausado,
                    // chama .play() + clique no botão play/iframe, e loga
                    // [VIDEO_PLAY] readyState/networkState/paused/currentSrc.
                    // Interceptação acontece em shouldInterceptRequest + hook fetch.
                    if (clickDone.get() && !captured.get() && isDiagTick) {
                        withContext(Dispatchers.Main) {
                            try {
                                wvNow.evaluateJavascript(
                                    """(function(){
                                        try{
                                            const v=document.querySelector('video');
                                            if(!v) return 'no-video title='+document.title.slice(0,40);
                                            const st='paused='+v.paused+' ready='+v.readyState+' net='+v.networkState+' src='+(v.currentSrc||v.src||'').slice(0,120);
                                            if(v.paused){
                                                try{v.muted=true;}catch(_){}
                                                const p=v.play();
                                                if(p&&p.catch)p.catch(function(e){return 'play-rejected '+e.name;});
                                                const big=document.querySelector('.vjs-big-play-button, .jw-display, .play-button, [class*=big-play], [class*=play-btn]');
                                                if(big){try{big.click();}catch(_){}}
                                                return 'PLAYING '+st;
                                            }
                                            return 'already-playing '+st;
                                        }catch(e){return 'play-err '+e.message;}
                                    })();""".trimIndent()
                                ) { r ->
                                    Log.i(TAG, "[VIDEO_PLAY] t=${elapsed}ms ${r?.removeSurrounding("\"")?.take(220)}")
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                    withContext(Dispatchers.Main) {
                        try {
                            wvNow.evaluateJavascript(
                                """(function() {
                                    try {
                                        const entries = performance.getEntriesByType('resource');
                                        for (let i = entries.length - 1; i >= 0; i--) {
                                            const n = entries[i].name;
                                            if (n.indexOf('__RC__/proxy') >= 0 || n.indexOf('/proxy?src=') >= 0 ||
                                                n.indexOf('tos-alisg') >= 0 || n.indexOf('xn--l') >= 0 ||
                                                n.indexOf('neosoro.gq') >= 0 || n.indexOf('container=videos') >= 0 ||
                                                n.indexOf('.mp4') >= 0 || n.indexOf('.m3u8') >= 0 ||
                                                n.indexOf('/ondemand/') >= 0 || n.indexOf('/videos/') >= 0) {
                                                if (n.indexOf('disqus') < 0 && n.indexOf('chatango') < 0 && n.indexOf('google') < 0) {
                                                    return n;
                                                }
                                            }
                                        }
                                    } catch (_) {}
                                    const v = document.querySelector('video');
                                    if (v) {
                                        const s = v.currentSrc || v.src || '';
                                        if (s && s.indexOf('blob:') < 0 && s.indexOf('disqus') < 0) return s;
                                    }
                                    return '';
                                })();""".trimIndent()
                            ) { res ->
                                val found = res?.removeSurrounding("\"").orEmpty()
                                if (found.isNotBlank() && found != "null" && !captured.get()) {
                                    streamUrl = found
                                    captured.set(true)
                                    captureHolder.set(true)
                                    Log.i(TAG, "[PROXY] Stream capturado via evaluateJavascript: ${found.take(180)}")
                                    try {
                                        val ctx3 = com.lagradost.cloudstream3.CommonActivity.activity ?: CommonActivity.activity?.applicationContext
                                        ctx3?.let { c -> java.io.File(c.filesDir, "redecanais_af_last_stream_url.txt").writeText(found) }
                                    } catch (_: Throwable) {}
                                }
                            }
                            // v229: sonda de estado do player a cada ~5s — alimenta
                            // playerMounted (bloqueia reload enquanto o bundle monta).
                            // v231: também espelha o DOM do server.php na RAM do solver
                            // (o solver nunca captura server.php — só o detalhe — então
                            // o SERVERPHP_HTML tinha dumped=null; com o espelho o
                            // StreamResolver inspeciona forms/scripts do player real).
                            if (isDiagTick) {
                                wvNow.evaluateJavascript(
                                    """(function() {
                                        try {
                                            const btn = document.getElementById('submit') || document.querySelector('.captcha_button');
                                            const btnVisible = btn ? (btn.offsetParent !== null) : false;
                                            const rcFn = (typeof window.rcPreloadPlayer === 'function');
                                            const v = document.querySelector('video');
                                            const vSrc = v ? ((v.currentSrc || v.src || '').substring(0,80)) : '';
                                            const html = document.documentElement ? document.documentElement.outerHTML : '';
                                            return 'player btn=' + btnVisible + ' | rcFn=' + rcFn + ' | video=' + (vSrc || 'none') + ' | title=' + document.title.substring(0,50) + ' ||HTML||' + html.substring(0,60000);
                                        } catch(e) { return 'player ERR ' + e.message; }
                                    })();""".trimIndent()
                                ) { res ->
                                    val clean = res?.removeSurrounding("\"").orEmpty()
                                    val state = clean.substringBefore("||HTML||")
                                    Log.i(TAG, "[PROXY_STATE] t=${elapsed}ms $state")
                                    // player montado = btn visível + rcPreloadPlayer function —
                                    // enquanto montado, o retry NÃO recarrega (mata a montagem).
                                    playerMounted.set(state.contains("btn=true") && state.contains("rcFn=true"))
                                    // espelho do DOM na RAM (1x por ciclo basta)
                                    try {
                                        val htmlPart = clean.substringAfter("||HTML||", "")
                                            .replace("\\u003C", "<").replace("\\u003E", ">")
                                            .replace("\\\"", "\"").replace("\\n", "\n")
                                        if (htmlPart.length > 5000 && !captured.get()) {
                                            CloudflareSolver.mirrorServerPhpHtml(serverPhpUrl, htmlPart)
                                        }
                                    } catch (_: Throwable) {}
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // 4) Retry com reload se a página não montou o player.
                // v229: recarrega a URL ORIGINAL com query params (vid/server/subfolder) —
                // wv.reload() após submit do recap navegava para "server.php?" vazio,
                // que retorna 520 e destrói a sessão válida (diag v229 provou).
                // v229c: NÃO recarrega enquanto o player está montado (btn+rcPreloadPlayer
                // presentes) — o bundle leva >10s para montar o __RC__/proxy após o click
                // e o reload cego matava a montagem em andamento (diag 12:08 provou:
                // player montado aos 15s, morto pelo reload #2).
                val clickAge = if (clickDoneAtMs == 0L) Long.MAX_VALUE else now - clickDoneAtMs
                val neverClicked = clickDoneAtMs == 0L
                // v229c: stale só vale se o click foi há pouco E o player sumiu;
                // player montado (btn+rcFn) invalida o stale — dá tempo ao bundle.
                val staleClick = clickDone.get() && clickAge in DUD_CLICK_MS..15000L && playerMounted.get().not()
                if (!captured.get() &&
                    (neverClicked || staleClick) &&
                    now - startMs >= RELOAD_FIRST_MS &&
                    now - lastReloadCheckMs >= RELOAD_INTERVAL_MS &&
                    reloadCount < MAX_RELOADS
                ) {
                    lastReloadCheckMs = now
                    withContext(Dispatchers.Main) {
                        try {
                            wvNow.evaluateJavascript(
                                """(function() {
                                    return document.querySelectorAll('.captcha_button, #submit').length > 0
                                        || typeof window.rcPreloadPlayer === 'function';
                                })();""".trimIndent()
                            ) { res ->
                                val hasCaptcha = res?.contains("true") == true
                                if (staleClick || !hasCaptcha) {
                                    reloadCount++
                                    if (staleClick) {
                                        Log.i(TAG, "[PROXY] click sem efeito -> reload #$reloadCount p/ sessão fresca")
                                    } else {
                                        Log.i(TAG, "[PROXY] captcha ausente -> reload #$reloadCount para renovar RCIP/RCSESS")
                                    }
                                    clickDone.set(false)
                                    clickDoneAtMs = 0L
                                    // v229: preserva query params — reload() nu perdia vid/server
                                    try { wvNow.loadUrl(serverPhpUrl) } catch (_: Throwable) {}
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
            true
        }

        val finalUrl = streamUrl
        if (finalUrl.isNullOrBlank()) {
            if (empty204Count.get() >= 3) {
                Log.w(TAG, "[PROXY$tagSuffix] Túnel vazio 204+e18b73c9=[] x${empty204Count.get()} — servidor sem origem p/ este vid, abortando variante em ${System.currentTimeMillis() - startMs}ms (budget ${budget}ms)")
            } else {
                Log.w(TAG, "[PROXY$tagSuffix] Falha: nenhuma URL __RC__/proxy capturada em ${budget} ms")
            }
            // v242 (Null_Pointer, teste d): replay OkHttp in-app do 2-step
            // serverforms com cookies+UA do WebView. Falha esperada: 402/403
            // (JA3-bound — clearance só vale no TLS do WebView). Se o replay
            // DEVOLVER o mesmo 204+e18b73c9=[] do WebView => túnel vazio é
            // decisão do BACKEND (origem morta), não da sessão/TLS/ad.
            // Se devolver challenge/HTML diferente => diverge, pista nova.
            try {
                replayServerformsOnce(wvRef(), serverPhpUrl, detailUrl)
            } catch (e: Throwable) {
                Log.w(TAG, "[REPLAY] err=${e.message?.take(120)}")
            }
            // v248/v250: NÃO dá shutdown aqui — o StreamResolver reaproveita o
            // MESMO WebView (challenge válido) na próxima variante. O shutdown
            // acontece no finally da matriz (falha total) ou no fim do app.
            return null
        }

        // v250: NÃO pausa o vídeo — o usuário pediu WebView único ATÉ a
        // reprodução: o <video> continua tocando no jar único enquanto o
        // ExoPlayer consome via proxy local (mesmo TLS/sessão). Pausar aqui
        // mataria as conexões que acabamos de interceptar.
        Log.i(TAG, "[PROXY$tagSuffix] Captura OK (video segue tocando no WebView único): $finalUrl")
        try {
            val ctx4 = com.lagradost.cloudstream3.CommonActivity.activity ?: CommonActivity.activity?.applicationContext
            ctx4?.let { c -> java.io.File(c.filesDir, "redecanais_af_last_stream_url.txt").writeText(finalUrl) }
            java.io.File("/sdcard/redecanais_af_last_stream_url.txt").writeText(finalUrl)
        } catch (_: Throwable) {}
        return startLocalServer(finalUrl)
    }

    /**
     * v242 (Null_Pointer, teste d): replay OkHttp in-app do 2-step serverforms.
     * Lê __rcApiLog (URLs .api + headers que o bundle usou no WebView), repete
     * step1/step2 via app.baseClient com cookies do CookieManager + UA do
     * WebView + Referer server.php + x-requested-with. Loga [REPLAY] com
     * code/len/preview de cada passo + classificação do 204:
     *  - REPLAY_204_IDENTICO: mesmo {"2fa806d3":204,"e18b73c9":[]} => backend
     *    decidiu túnel vazio (origem morta), NÃO é sessão/TLS/ad.
     *  - REPLAY_DIVERGE: challenge/HTML/erro TLS => sessão diverge fora do WV.
     * Roda em Dispatchers.Main (evaluateJavascript) + IO (OkHttp) — chamado
     * ANTES do shutdown() para o WebView ainda estar vivo.
     */
    private suspend fun replayServerformsOnce(wv: WebView?, serverPhpUrl: String, detailUrl: String) {
        if (wv == null) {
            Log.w(TAG, "[REPLAY] sem WebView — abortado")
            return
        }
        // 1) puxa __rcApiLog + cookies do WebView (thread Main)
        val apiSnapshot = withContext(Dispatchers.Main) {
            try {
                val fut = CompletableFuture<String>()
                wv.evaluateJavascript(
                    """(function(){
                        try{
                          const log=(window.__rcApiLog||[]).slice(-6).map(e=>e.m+','+e.url+','+(e.hdrs||'{}')).join(' || ');
                          return 'LOG='+log+' [SEP] CK='+document.cookie.slice(0,400)+' [SEP] LAST='+String(window.__rcLastApi||'').slice(0,300);
                        }catch(e){return 'ERR '+e.message;}
                    })();""".trimIndent()
                ) { r -> fut.complete(r?.removeSurrounding("\"").orEmpty()) }
                withContext(Dispatchers.IO) { fut.get(8, TimeUnit.SECONDS) }
            } catch (e: Throwable) { "ERR ${e.message?.take(80)}" }
        }
        Log.i(TAG, "[REPLAY] snapshot: ${apiSnapshot.take(900)}")
        val apiUrls = Regex("""(https?://[^,\s|]+\.api[^,\s|]*|/player3/[^\s|,|]+)""")
            .findAll(apiSnapshot).map { it.groupValues[1] }.distinct().take(4).toList()
        if (apiUrls.isEmpty()) {
            Log.w(TAG, "[REPLAY] FALHA — __rcApiLog vazio (bundle não chamou .api nesta variante)")
            return
        }
        // 2) cookies + UA no contexto do app
        val cookies = try {
            val cm = CookieManager.getInstance()
            listOf(serverPhpUrl, detailUrl, "https://redecanais.af/")
                .filter { it.isNotBlank() }.mapNotNull { runCatching { cm.getCookie(it) }.getOrNull() }
                .flatMap { it.split(";") }.map { it.trim() }.filter { it.contains("=") }
                .distinctBy { it.substringBefore("=") }.joinToString("; ")
        } catch (_: Throwable) { "" }
        val ua = CloudflareSolver.lastUserAgent ?: WebViewResolver.webViewUserAgent.orEmpty()
        Log.i(TAG, "[REPLAY] urls=${apiUrls.size} cookies_len=${cookies.length} ck_names=" +
            cookies.split(";").map { it.substringBefore("=").trim() }.joinToString(",").take(120))
        // 3) replay sequencial via OkHttp do app (mesmo client = mesmo TLS/JA3 do app)
        withContext(Dispatchers.IO) {
            try {
                val client = com.lagradost.cloudstream3.app.baseClient.newBuilder()
                    .retryOnConnectionFailure(false).followRedirects(false)
                    .followSslRedirects(false).build()
                var step2Body = ""
                apiUrls.forEachIndexed { i, raw ->
                    val abs = when {
                        raw.startsWith("http", true) -> raw
                        raw.startsWith("/", true) -> "https://redecanais.af$raw"
                        else -> "https://redecanais.af/player3/$raw"
                    }
                    try {
                        val req = okhttp3.Request.Builder().url(abs).get()
                            .header("User-Agent", ua)
                            .header("Referer", serverPhpUrl)
                            .header("Origin", "https://redecanais.af")
                            .header("X-Requested-With", "XMLHttpRequest")
                            .header("Accept", "application/json, text/javascript, */*; q=0.01")
                            .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                            .apply { if (cookies.isNotBlank()) header("Cookie", cookies) }
                            .build()
                        val t0 = android.os.SystemClock.elapsedRealtime()
                        client.newCall(req).execute().use { resp ->
                            val body = resp.body?.string().orEmpty()
                            val dt = android.os.SystemClock.elapsedRealtime() - t0
                            val setCk = resp.headers.values("Set-Cookie").joinToString(";").take(200)
                            val loc = resp.header("Location").orEmpty().take(150)
                            Log.i(TAG, "[REPLAY] step${i + 1} code=${resp.code} dt=${dt}ms len=${body.length} setCk=$setCk loc=$loc url=${abs.take(150)}")
                            Log.i(TAG, "[REPLAY] step${i + 1} preview=${body.take(300).replace("\n", " ")}")
                            if (body.contains("e18b73c9") || body.contains("c4a0f6")) step2Body = body
                            if (body.contains("\"2fa806d3\":204") && body.contains("\"e18b73c9\":[]"))
                                Log.w(TAG, "[REPLAY_204_IDENTICO] OkHttp reproduziu o 204 do WebView — túnel vazio é decisão do backend (origem morta), não sessão/TLS/ad")
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "[REPLAY] step${i + 1} FALHA err=${e.message?.take(150)} url=${abs.take(120)}")
                    }
                }
                if (step2Body.isNotBlank() && !step2Body.contains("\"2fa806d3\":204"))
                    Log.i(TAG, "[REPLAY_DIVERGE] corpo difere do 204 do WebView — pista nova, ver preview acima")
                else if (step2Body.isBlank())
                    Log.w(TAG, "[REPLAY_DIVERGE] nenhum passo retornou JSON serverforms (challenge/403/erro TLS provável — JA3-bound confirmado)")
            } catch (e: Throwable) {
                Log.w(TAG, "[REPLAY] client err=${e.message?.take(120)}")
            }
        }
    }

    /**
     * Inicia o ServerSocket local que o ExoPlayer consome (http://127.0.0.1:porta/stream.mp4).
     * v277: socket de SESSÃO — aberto 1x na porta preferida, vivo até o fim do app.
     * Cada chamada só troca o targetUrl servido (sessionTargetUrl) e retorna a mesma
     * URL local. startLocalServer NUNCA fecha socket nem destrói WebView.
     */
    private fun startLocalServer(targetUrl: String): String? {
        sessionTargetUrl = targetUrl
        synchronized(this) {
            val live = serverSocket?.let { !it.isClosed && it.isBound } == true
            if (live) {
                val port = localPort.takeIf { it > 0 } ?: serverSocket!!.localPort
                val local = "http://127.0.0.1:$port/stream.mp4"
                Log.i(TAG, "[PROXY] Servidor de sessão reaproveitado: $local -> $targetUrl")
                return local
            }
        }
        return try {
            val server = try {
                ServerSocket(PREFERRED_PORT, 16, java.net.InetAddress.getByName("127.0.0.1")).also {
                    Log.i(TAG, "[PROXY] Porta preferida $PREFERRED_PORT livre — sessão fixa nela")
                }
            } catch (_: Throwable) {
                ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1")).also {
                    Log.w(TAG, "[PROXY] Porta $PREFERRED_PORT ocupada — sessão na efêmera ${it.localPort}")
                }
            }
            serverSocket = server
            isServing = true
            val port = server.localPort
            localPort = port

            thread(isDaemon = true, name = "RCProxy-Accept") {
                while (isServing && !server.isClosed) {
                    try {
                        val client = server.accept()
                        // v277: target lido por conexão (sessão pode ter trocado de
                        // vídeo entre o loadLinks e o play do ExoPlayer).
                        val target = sessionTargetUrl ?: targetUrl
                        thread(isDaemon = true, name = "RCProxy-Conn") {
                            handleConnection(client, target)
                        }
                    } catch (e: Exception) {
                        if (isServing) Log.w(TAG, "[PROXY] accept err: ${e.message}")
                        break
                    }
                }
            }

            val local = "http://127.0.0.1:$port/stream.mp4"
            Log.i(TAG, "[PROXY] Servidor local ativo: $local -> $targetUrl")
            local
        } catch (e: Throwable) {
            Log.e(TAG, "[PROXY] Falha ao iniciar servidor local: ${e.message}")
            null
        }
    }

    private fun handleConnection(socket: Socket, targetUrl: String) {
        try {
            socket.use { sock ->
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                if (!requestLine.startsWith("GET") && !requestLine.startsWith("HEAD")) return

                // Parse headers (Range)
                var rangeStart = 0L
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    if (line.startsWith("Range:", true)) {
                        val m = Regex("""bytes=(\d+)-(\d*)""").find(line)
                        rangeStart = m?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    }
                }

                val out = sock.getOutputStream()

                // Resposta 206 com total desconhecido (*) — ExoPlayer faz streaming progressivo
                val head =
                    "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Content-Range: bytes $rangeStart-*/*\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                out.flush()

                if (requestLine.startsWith("HEAD")) return

                // v123: prefetch duplo — busca o chunk N+1 em paralelo enquanto serve o N,
                // eliminando a latência de rede/JS entre chunks (sem pausas na reprodução).
                var pos = rangeStart
                var nextChunk = CompletableFuture<ByteArray?>()
                thread(isDaemon = true, name = "RCProxy-Prefetch") {
                    nextChunk.complete(fetchChunk(targetUrl, pos, pos + CHUNK_SIZE - 1))
                }
                while (isServing) {
                    val chunk = try {
                        nextChunk.get(CHUNK_FETCH_TIMEOUT_S, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        Log.w(TAG, "[PROXY] timeout prefetch $pos..: ${e.message}")
                        null
                    } ?: break
                    if (chunk.isEmpty()) break // EOF (416)
                    val nextStart = pos + chunk.size
                    // dispara o fetch do próximo chunk ANTES de escrever o atual no socket
                    val next = CompletableFuture<ByteArray?>()
                    thread(isDaemon = true, name = "RCProxy-Prefetch") {
                        next.complete(fetchChunk(targetUrl, nextStart, nextStart + CHUNK_SIZE - 1))
                    }
                    out.write(chunk)
                    out.flush()
                    pos += chunk.size
                    if (chunk.size < CHUNK_SIZE) break // último chunk
                    nextChunk = next
                }
                Log.d(TAG, "[PROXY] conexão servida: range=$rangeStart..$pos")
            }
        } catch (e: Exception) {
            Log.d(TAG, "[PROXY] conexão encerrada: ${e.message}")
        }
    }

    /**
     * Busca um chunk da URL real fazendo fetch() DENTRO do contexto JS do WebView
     * (credentials include + Range) — mesmo TLS/fingerprint do cf_clearance.
     */
    private fun fetchChunk(url: String, start: Long, end: Long): ByteArray? {
        // v277: fallback defensivo — se o WebView foi destruído (shutdownAll) mas o
        // socket de sessão ainda recebe conexões do player, tenta capturar o
        // WebView recriado pela hierarchy em vez de falhar silencioso.
        var wv = webView
        if (wv == null) {
            wv = synchronized(this) {
                webView ?: runCatching {
                    val act = CommonActivity.activity
                    val root = act?.findViewById<ViewGroup>(android.R.id.content)
                    var found: WebView? = null
                    if (root != null) {
                        fun findWv(g: ViewGroup): WebView? {
                            for (i in 0 until g.childCount) {
                                val v = g.getChildAt(i)
                                if (v is WebView) return v
                                if (v is ViewGroup) { val f = findWv(v); if (f != null) return f }
                            }
                            return null
                        }
                        found = findWv(root)
                        if (found != null) webView = found
                    }
                    found
                }.getOrNull()
            }
            if (wv == null) return null
        }
        val future = CompletableFuture<String>()
        try {
            wv.post {
                try {
                    val js = """(async () => {
                        try {
                            const r = await fetch(${JSONObject.quote(url)}, {
                                credentials: 'include',
                                headers: {'Range': 'bytes=${start}-${end}'}
                            });
                            if (r.status === 416) return 'EOF';
                            if (!r.ok) return 'ERR:' + r.status;
                            // v123: decode nativo via FileReader.readAsDataURL (muito mais rápido
                            // que o loop String.fromCharCode + btoa para chunks de 512KB)
                            const buf = await r.arrayBuffer();
                            const blob = new Blob([buf]);
                            const dataUrl = await new Promise((resolve, reject) => {
                                const fr = new FileReader();
                                fr.onload = () => resolve(fr.result);
                                fr.onerror = () => reject(new Error('FR'));
                                fr.readAsDataURL(blob);
                            });
                            const idx = dataUrl.indexOf(',');
                            return idx >= 0 ? dataUrl.substring(idx + 1) : 'ERR:no-data';
                        } catch(e) { return 'ERR:' + e; }
                    })();""".trimIndent()
                    wv.evaluateJavascript(js) { res -> future.complete(res ?: "ERR:null") }
                } catch (e: Throwable) {
                    future.complete("ERR:${e.message}")
                }
            }
        } catch (e: Throwable) {
            return null
        }

        val raw = try {
            future.get(CHUNK_FETCH_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "[PROXY] timeout fetch chunk $start..$end: ${e.message}")
            return null
        } ?: return null

        val cleaned = raw.removeSurrounding("\"")
        if (cleaned == "EOF") return ByteArray(0)
        if (cleaned.startsWith("ERR:")) {
            Log.w(TAG, "[PROXY] chunk $start..$end erro: ${cleaned.take(120)}")
            return null
        }
        return try {
            android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT)
        } catch (e: Throwable) {
            Log.w(TAG, "[PROXY] chunk $start..$end base64 inválido: ${e.message}")
            null
        }
    }

    /** Encerra servidor e WebView (thread-safe, pode ser chamado de qualquer thread).
     * v277: shutdown() NÃO fecha mais o ServerSocket de sessão nem destrói o WebView
     * — só limpa o streamUrl pendente. O socket de sessão fica vivo até o fim do
     * app para que URLs locais cacheadas (LOAD_CACHE_HIT) continuem respondendo.
     * O teardown real acontece em shutdownAll() (fim do app). */
    fun shutdown() {
        streamUrl = null
        Log.d(TAG, "[PROXY] shutdown leve: sessão local preservada (port=$localPort)")
    }

    /** Teardown real: fecha o ServerSocket de sessão + destrói o WebView. Chamar
     * apenas no fim do app / troca de provider. */
    fun shutdownAll() {
        isServing = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        localPort = -1
        sessionTargetUrl = null
        streamUrl = null
        // Capture ownership before clearing the singleton. Dispatch independently
        // of CommonActivity: the Activity may already be gone during shutdown.
        val wv = synchronized(this) {
            webView.also { webView = null }
        } ?: return
        val cleanup = Runnable {
            runCatching { wv.stopLoading() }
            runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
            runCatching { wv.destroy() }
                .onSuccess { Log.i(TAG, "[PROXY] WebView destruido no shutdown") }
                .onFailure { Log.w(TAG, "[PROXY] Falha ao destruir WebView", it) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.run()
        } else {
            Handler(Looper.getMainLooper()).post(cleanup)
        }
    }
}
