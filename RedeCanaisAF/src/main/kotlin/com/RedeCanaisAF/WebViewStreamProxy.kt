package com.RedeCanaisAF

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CommonActivity
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
    private const val CHUNK_FETCH_TIMEOUT_S = 25L
    // v123: setup mais rápido — poll 200ms, click no DOM pronto, fallbacks por tempo real
    private const val POLL_INTERVAL_MS = 200L
    private const val CLICK_RETRY_MS = 600L
    private const val DIRECT_FALLBACK_MS = 3000L
    private const val RELOAD_FIRST_MS = 12000L
    private const val RELOAD_INTERVAL_MS = 6000L
    private const val MAX_RELOADS = 4
    // v124: se o click foi efetivo mas o player não montou em 15s, é click "dud"
    // (RCIP/RCSESS velho / IP rotacionou) — reset e reload para sessão fresca
    private const val DUD_CLICK_MS = 15000L
    private val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    @Volatile private var webView: WebView? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var streamUrl: String? = null
    @Volatile private var isServing = false

    /**
     * Captura o stream real (__RC__/proxy) abrindo server.php no WebView e clicando no recap.
     * Retorna a URL local http://127.0.0.1:<porta>/stream.mp4 que o ExoPlayer deve reproduzir.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun captureAndServe(serverPhpUrl: String): String? {
        shutdown() // limpa estado anterior

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

                val view = WebView(activity).apply {
                    visibility = android.view.View.INVISIBLE
                    val density = resources.displayMetrics.density
                    val wvW = (720 * density).toInt()
                    val wvH = (1280 * density).toInt()
                    layoutParams = android.widget.FrameLayout.LayoutParams(wvW, wvH).apply {
                        leftMargin = -(wvW + 100)
                        topMargin = -(wvH + 100)
                    }
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        blockNetworkImage = true
                        loadsImagesAutomatically = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        javaScriptCanOpenWindowsAutomatically = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        // v122: UA mobile exato (o mesmo que funcionou via browser-harness)
                        userAgentString = MOBILE_UA
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val u = request.url.toString()
                            // v122: captura a URL real do vídeo que o player usa (initiator video)
                            if (u.contains("__RC__/proxy", true) || u.contains("/proxy?src=", true)) {
                                if (!captured.get()) {
                                    streamUrl = u
                                    captured.set(true)
                                    captureHolder.set(true)
                                    Log.i(TAG, "[PROXY] __RC__/proxy capturado via shouldInterceptRequest: ${u.take(180)}")
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            CookieManager.getInstance().flush()
                            pageReady.set(true)
                            Log.d(TAG, "[PROXY] onPageFinished url=$finishedUrl")
                        }
                    }
                }
                wv = view
                webView = view
                rootLayout.addView(view)
                view.loadUrl(serverPhpUrl)
            } catch (e: Throwable) {
                Log.e(TAG, "[PROXY] Erro ao criar WebView: ${e.message}")
            }
        }

        // ===== Loop de captura v123: clica no recap assim que o DOM estiver pronto =====
        // v123: não espera mais onPageFinished inteiro — o botão .captcha_button existe antes.
        // Poll 200ms, fallbacks por tempo real (3s/6s/12s) em vez de System.currentTimeMillis() % N.
        var reloadCount = 0
        var lastClickMs = 0L
        var lastReloadCheckMs = 0L
        var lastDirectFallbackMs = 0L
        var clickDoneAtMs = 0L
        // v125: o fallback direto (rcPreloadPlayer manual) roda NO MÁXIMO uma vez por ciclo —
        // chamadas repetidas a cada 3s podem reiniciar a montagem do player (serverforms.api)
        var directFallbackDone = false
        val startMs = System.currentTimeMillis()
        // v126: orçamento por tentativa cortado p/ 45s — o framework cancela loadLinks em
        // ~120s (TimeoutCancellationException), então 2 tentativas (RCServer01 + RCFServer2)
        // precisam caber em 90s. Antigo 120s por tentativa abortava antes do fallback.
        withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            while (!captured.get()) {
                delay(POLL_INTERVAL_MS) // v123: 200ms (era 500ms)
                val now = System.currentTimeMillis()
                val wvNow = wv ?: continue

                // 1) Clica no recap assim que o DOM estiver pronto (não espera onPageFinished).
                // v123: retry a cada CLICK_RETRY_MS até o click ser efetivo ('click'/'direct') —
                // se rodar antes do DOM existir, retorna 'none' e tenta de novo.
                // v125: espera window.rcPreloadPlayer existir ANTES de clicar (como o
                // browser-harness da Fase 51 fazia) — se o script do player ainda não
                // carregou, o handler do botão não existe e o clique não dispara nada.
                if (!clickDone.get() && captured.get().not() && now - lastClickMs >= CLICK_RETRY_MS) {
                    lastClickMs = now
                    withContext(Dispatchers.Main) {
                        try {
                            wvNow.evaluateJavascript(
                                """(function() {
                                    if (typeof window.rcPreloadPlayer !== 'function') return 'wait';
                                    const b = document.getElementById('submit') || document.querySelector('.captcha_button');
                                    if (b) { b.click(); return 'click'; }
                                    window.rcPreloadPlayer(Date.now());
                                    return 'direct';
                                })();""".trimIndent()
                            ) { res ->
                                val r = res?.removeSurrounding("\"")
                                Log.i(TAG, "[PROXY] click recap -> $r")
                                if (r == "click" || r == "direct") {
                                    clickDone.set(true)
                                    clickDoneAtMs = now
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // 2) Fallback direto (rcPreloadPlayer) 3s após o click inicial, se nada capturou.
                // v125: roda UMA única vez (flag directFallbackDone) — o harness da Fase 51
                // chamava rcPreloadPlayer UMA vez; repetir a cada 3s pode reiniciar o player.
                if (clickDone.get() && !captured.get() && !directFallbackDone && now - lastClickMs >= DIRECT_FALLBACK_MS && now - lastDirectFallbackMs >= DIRECT_FALLBACK_MS) {
                    lastDirectFallbackMs = now
                    directFallbackDone = true
                    withContext(Dispatchers.Main) {
                        try {
                            wvNow.evaluateJavascript(
                                """(function() {
                                    if (typeof window.rcPreloadPlayer === 'function') {
                                        window.rcPreloadPlayer(Date.now());
                                        return 'direct2';
                                    }
                                    return 'no-fn';
                                })();""".trimIndent()
                            ) { res ->
                                if (res?.contains("direct2") == true) {
                                    Log.i(TAG, "[PROXY] rcPreloadPlayer direto chamado (fallback)")
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }

                // 3) Poll via performance entries (fallback se shouldInterceptRequest falhar)
                if (!captured.get()) {
                    val found = withContext(Dispatchers.Main) {
                        var result: String? = null
                        val future = CompletableFuture<String?>()
                        try {
                            wvNow.evaluateJavascript(
                                """(function() {
                                    try {
                                        const entries = performance.getEntriesByType('resource');
                                        for (let i = entries.length - 1; i >= 0; i--) {
                                            const n = entries[i].name;
                                            if (n.indexOf('__RC__/proxy') >= 0 || n.indexOf('/proxy?src=') >= 0) return n;
                                        }
                                    } catch (_) {}
                                    const v = document.querySelector('video');
                                    if (v) {
                                        const s = v.currentSrc || v.src || '';
                                        if (s.indexOf('__RC__') >= 0 || s.indexOf('/proxy?src=') >= 0) return s;
                                    }
                                    return '';
                                })();""".trimIndent()
                            ) { res -> future.complete(res?.removeSurrounding("\"")) }
                            result = future.get(4, TimeUnit.SECONDS)
                        } catch (_: Throwable) {}
                        result
                    }
                    if (!found.isNullOrBlank() && !captured.get()) {
                        streamUrl = found
                        captured.set(true)
                        captureHolder.set(true)
                        Log.i(TAG, "[PROXY] __RC__/proxy capturado via performance entries: ${found.take(180)}")
                    }
                }

                // 4) Retry com reload se a página não montou o player (RCIP stale / shell 921KB)
                // v124: NUNCA recarregar logo após um click efetivo — o botão .captcha_button some
                // do DOM quando o player começa a montar (hasCaptcha=false), e reload aí mata o fluxo
                // serverforms.api -> __RC__/proxy antes de capturar (era o bug do v123: reload #1 aos
                // 8s pós-click matou o player em montagem).
                // - Se NUNCA clicou e não há captcha -> reload (renova RCIP/RCSESS até 4x).
                // - Se clicou mas nada capturou em DUD_CLICK_MS -> click dud (sessão velha/IP mudou):
                //   reset clickDone + reload para sessão fresca.
                // 1ª checagem aos 12s, depois a cada 6s.
                val clickAge = if (clickDoneAtMs == 0L) Long.MAX_VALUE else now - clickDoneAtMs
                val neverClicked = clickDoneAtMs == 0L
                val dudClick = clickDone.get() && clickAge >= DUD_CLICK_MS
                if (!captured.get() &&
                    (neverClicked || dudClick) &&
                    now - startMs >= RELOAD_FIRST_MS &&
                    now - lastReloadCheckMs >= RELOAD_INTERVAL_MS &&
                    reloadCount < MAX_RELOADS
                ) {
                    lastReloadCheckMs = now
                    val hasCaptcha = withContext(Dispatchers.Main) {
                        var ok = false
                        val future = CompletableFuture<Boolean>()
                        try {
                            wvNow.evaluateJavascript(
                                """(function() {
                                    return document.querySelectorAll('.captcha_button, #submit').length > 0
                                        && typeof window.rcPreloadPlayer === 'function';
                                })();""".trimIndent()
                            ) { res -> future.complete(res?.contains("true") == true) }
                            ok = future.get(4, TimeUnit.SECONDS)
                        } catch (_: Throwable) {}
                        ok
                    }
                    if (dudClick || !hasCaptcha) {
                        reloadCount++
                        if (dudClick) {
                            Log.i(TAG, "[PROXY] click dud (player não montou em ${DUD_CLICK_MS / 1000}s) -> reload #$reloadCount p/ sessão fresca")
                        } else {
                            Log.i(TAG, "[PROXY] captcha ausente (shell?) -> reload #$reloadCount para renovar RCIP/RCSESS")
                        }
                        clickDone.set(false)
                        clickDoneAtMs = 0L
                        withContext(Dispatchers.Main) {
                            try { wvNow.reload() } catch (_: Throwable) {}
                        }
                    }
                }
            }
            true
        }

        val finalUrl = streamUrl
        if (finalUrl.isNullOrBlank()) {
            Log.w(TAG, "[PROXY] Falha: nenhuma URL __RC__/proxy capturada em $CAPTURE_TIMEOUT_MS ms")
            shutdown()
            return null
        }

        // Pausa o vídeo no WebView (não deixar o player consumir banda em paralelo)
        withContext(Dispatchers.Main) {
            try {
                wv?.evaluateJavascript(
                    """(function() { const v = document.querySelector('video'); if (v) { try { v.pause(); } catch (_) {} } })();""".trimIndent(),
                    null
                )
            } catch (_: Throwable) {}
        }

        Log.i(TAG, "[PROXY] Captura OK: $finalUrl")
        return startLocalServer(finalUrl)
    }

    /**
     * Inicia o ServerSocket local que o ExoPlayer consome (http://127.0.0.1:porta/stream.mp4).
     */
    private fun startLocalServer(targetUrl: String): String? {
        return try {
            val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            isServing = true
            val port = server.localPort

            thread(isDaemon = true, name = "RCProxy-Accept") {
                while (isServing && !server.isClosed) {
                    try {
                        val client = server.accept()
                        thread(isDaemon = true, name = "RCProxy-Conn") {
                            handleConnection(client, targetUrl)
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
        val wv = webView ?: return null
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

    /** Encerra servidor e WebView (thread-safe, pode ser chamado de qualquer thread). */
    fun shutdown() {
        isServing = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        streamUrl = null
        val wv = webView
        webView = null
        if (wv != null) {
            try {
                wv.post {
                    try {
                        wv.stopLoading()
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv.destroy()
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }
}
