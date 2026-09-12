package com.RedeCanaisAF

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.network.WebViewResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object LocalImageProxy {
    private const val TAG = "RedeCanaisAF-Trace"
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private var serverSocket: ServerSocket? = null
    @Volatile var port: Int = 0
        private set

    @Volatile var helperWebView: WebView? = null
    val isHelperReady = AtomicBoolean(false)
    private val pendingFetches = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()

    private val directHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    init {
        startServer()
    }

    @Synchronized
    fun startServer() {
        if (serverSocket != null && !serverSocket!!.isClosed) return
        try {
            val preferredPort = 43691
            val s = try {
                ServerSocket(preferredPort, 50, InetAddress.getByName("127.0.0.1"))
            } catch (_: Throwable) {
                ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            }
            serverSocket = s
            port = s.localPort
            Log.i(TAG, "[IMG_PROXY] Servidor iniciado com sucesso na porta $port")

            thread(isDaemon = true, name = "RCImgProxy-Server") {
                while (!s.isClosed) {
                    try {
                        val client = s.accept()
                        thread(isDaemon = true, name = "RCImgProxy-Worker") {
                            handleClient(client)
                        }
                    } catch (e: Throwable) {
                        if (!s.isClosed) {
                            Log.w(TAG, "[IMG_PROXY] Erro accept: ${e.message}")
                        }
                        break
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[IMG_PROXY] Falha ao iniciar ServerSocket: ${e.message}", e)
        }
    }

    fun wrapUrl(url: String): String {
        if (url.isBlank() || url.startsWith("data:")) {
            return url
        }
        var actualUrl = url
        if (actualUrl.contains("/img?url=")) {
            try {
                actualUrl = URLDecoder.decode(actualUrl.substringAfter("/img?url="), "UTF-8")
            } catch (_: Throwable) {}
        }
        if (port <= 0) {
            startServer()
        }

        // Pré-aquece o helper WebView na Activity
        CommonActivity.activity?.let { act ->
            if (!act.isFinishing && !act.isDestroyed && helperWebView == null) {
                act.runOnUiThread { ensureHelperWebView(act) }
            }
        }

        val encoded = URLEncoder.encode(actualUrl, "UTF-8")
        return "http://127.0.0.1:$port/img?url=$encoded"
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun getCacheDir(): File? {
        val cache = CommonActivity.activity?.cacheDir ?: return null
        val dir = File(cache, "rc_posters")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val firstLine = reader.readLine()
            if (firstLine.isNullOrBlank()) {
                socket.close()
                return
            }
            val parts = firstLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                socket.close()
                return
            }

            val path = parts[1]
            if (!path.startsWith("/img?url=")) {
                send404(socket)
                return
            }

            val rawEncoded = path.substringAfter("/img?url=")
            var targetUrl = try { URLDecoder.decode(rawEncoded, "UTF-8") } catch (_: Throwable) { rawEncoded }
            if (targetUrl.contains("/img?url=")) {
                try { targetUrl = URLDecoder.decode(targetUrl.substringAfter("/img?url="), "UTF-8") } catch (_: Throwable) {}
            }
            targetUrl = targetUrl.replace(" ", "%20").replace("%2520", "%20")
            if (targetUrl.isBlank()) {
                send404(socket)
                return
            }

            // 1. Checar cache de disco
            val hash = md5(targetUrl)
            val cacheDir = getCacheDir()
            val cachedFile = cacheDir?.let { File(it, "$hash.jpg") }

            if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
                val bytes = cachedFile.readBytes()
                sendResponse(socket, bytes)
                return
            }

            Log.i(TAG, "[IMG_PROXY] Buscando imagem: $targetUrl")

            // 2. Tenta OkHttp direto primeiro com Cookies
            val directBytes = tryDirectOkHttp(targetUrl)
            if (directBytes != null && directBytes.isNotEmpty()) {
                try {
                    cachedFile?.writeBytes(directBytes)
                } catch (_: Throwable) {}
                Log.i(TAG, "[IMG_PROXY] Sucesso via OkHttp! Servindo ${directBytes.size} bytes para $targetUrl")
                sendResponse(socket, directBytes)
                return
            }

            // 3. Fallback: Fetch via WebView + JavascriptInterface Bridge
            var resolvedUrl = targetUrl
            var bytes = fetchImageBytes(targetUrl)
            // Search index rows contain no poster path. Older series/details expose
            // the same filename under /Legado/ instead of the guessed /Series/ or
            // /Desenhos/ path. Retry only after the observed primary URL fails.
            if (bytes == null || bytes.isEmpty()) {
                val legacyUrl = targetUrl
                    .replace("/imgs-videos/Series/", "/imgs-videos/Legado/")
                    .replace("/imgs-videos/Desenhos/", "/imgs-videos/Legado/")
                if (legacyUrl != targetUrl) {
                    Log.i(TAG, "[IMG_PROXY] Tentando fallback Legado para $targetUrl")
                    bytes = tryDirectOkHttp(legacyUrl) ?: fetchImageBytes(legacyUrl)
                    if (bytes != null && bytes.isNotEmpty()) resolvedUrl = legacyUrl
                }
            }
            val finalBytes = bytes
            if (finalBytes != null && finalBytes.isNotEmpty()) {
                try {
                    cachedFile?.writeBytes(finalBytes)
                } catch (_: Throwable) {}
                Log.i(TAG, "[IMG_PROXY] Sucesso via fallback! Servindo ${finalBytes.size} bytes de $resolvedUrl")
                sendResponse(socket, finalBytes)
            } else {
                Log.w(TAG, "[IMG_PROXY] Falha total para $targetUrl (404)")
                send404(socket)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[IMG_PROXY] Erro ao servir imagem: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun tryDirectOkHttp(url: String): ByteArray? {
        return try {
            val cookies = CloudflareSolver.sanitizeCookies(runCatching {
                CookieManager.getInstance().getCookie("https://redecanais.af")
            }.getOrNull().orEmpty())
            val stealth = CloudflareSolver.stealthHeaders("https://redecanais.af/")
            val reqBuilder = Request.Builder().url(url)
            for ((k, v) in stealth) {
                if (k != "Cookie") reqBuilder.header(k, v)
            }
            // preserva cookies frescos + referer já no stealth
            if (cookies.isNotBlank()) reqBuilder.header("Cookie", cookies)
            val t0 = android.os.SystemClock.elapsedRealtime()
            val resp = directHttpClient.newCall(reqBuilder.build()).execute()
            val dt = android.os.SystemClock.elapsedRealtime() - t0
            if (resp.isSuccessful) {
                val bytes = resp.body?.bytes()
                if (bytes != null && bytes.isNotEmpty() && !bytes.isHtmlResponse()) {
                    Log.d(TAG, "[IMG_PROXY] OkHttp hit ${bytes.size}b in ${dt}ms for $url")
                    return bytes
                }
            }
            Log.d(TAG, "[IMG_PROXY] OkHttp miss code=${resp.code} dt=${dt}ms for $url")
            null
        } catch (e: Throwable) {
            Log.d(TAG, "[IMG_PROXY] OkHttp err ${e.message} for $url")
            null
        }
    }

    private fun ByteArray.isHtmlResponse(): Boolean {
        if (size < 15) return false
        val prefix = String(take(15).toByteArray(), Charsets.UTF_8).lowercase()
        return prefix.contains("<!doctype") || prefix.contains("<html")
    }

    private fun sendResponse(socket: Socket, data: ByteArray) {
        val out = socket.getOutputStream()
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(data)
        out.flush()
    }

    private fun send404(socket: Socket) {
        val out = socket.getOutputStream()
        val header = "HTTP/1.1 404 Not Found\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun fetchImageBytes(url: String): ByteArray? {
        val existing = pendingFetches[url]
        if (existing != null) {
            return try {
                existing.get(15, TimeUnit.SECONDS)
            } catch (_: Throwable) {
                null
            }
        }

        val future = CompletableFuture<ByteArray?>()
        pendingFetches[url] = future

        val activity = CommonActivity.activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "[IMG_PROXY] Activity indisponível para $url")
            future.complete(null)
            pendingFetches.remove(url)
            return null
        }

        activity.runOnUiThread {
            ensureHelperWebView(activity)
            val wv = helperWebView
            if (wv == null) {
                Log.w(TAG, "[IMG_PROXY] Helper WebView indisponível para $url")
                future.complete(null)
                pendingFetches.remove(url)
                return@runOnUiThread
            }

            val js = """(async () => {
                const targetUrl = ${JSONObject.quote(url)};
                
                function arrayBufferToBase64(buffer) {
                    let binary = '';
                    const bytes = new Uint8Array(buffer);
                    const len = bytes.byteLength;
                    const chunkSize = 8192;
                    for (let i = 0; i < len; i += chunkSize) {
                        const sub = bytes.subarray(i, Math.min(i + chunkSize, len));
                        binary += String.fromCharCode.apply(null, sub);
                    }
                    return btoa(binary);
                }

                // 1. Tenta Fetch direto no contexto do site (credenciais do navegador)
                try {
                    const r = await fetch(targetUrl, {
                        credentials: 'include'
                    });
                    if (r.ok) {
                        const buffer = await r.arrayBuffer();
                        const b64 = arrayBufferToBase64(buffer);
                        if (b64 && window.ImageBridge) {
                            window.ImageBridge.postImage(targetUrl, b64);
                            return;
                        }
                    }
                } catch(e) {}

                // 2. Fallback: Image Tag + Canvas Draw
                try {
                    const img = new Image();
                    img.crossOrigin = 'anonymous';
                    img.onload = () => {
                        try {
                            const canvas = document.createElement('canvas');
                            canvas.width = img.naturalWidth || img.width;
                            canvas.height = img.naturalHeight || img.height;
                            const ctx = canvas.getContext('2d');
                            ctx.drawImage(img, 0, 0);
                            const dataUrl = canvas.toDataURL('image/jpeg', 0.85);
                            const idx = dataUrl.indexOf(',');
                            const b64 = idx >= 0 ? dataUrl.substring(idx + 1) : '';
                            if (window.ImageBridge) {
                                window.ImageBridge.postImage(targetUrl, b64);
                            }
                        } catch(err) {
                            if (window.ImageBridge) window.ImageBridge.postError(targetUrl, 'canvas_' + err.message);
                        }
                    };
                    img.onerror = () => {
                        if (window.ImageBridge) window.ImageBridge.postError(targetUrl, 'img_load_fail');
                    };
                    img.src = targetUrl;
                    setTimeout(() => {
                        if (window.ImageBridge) window.ImageBridge.postError(targetUrl, 'timeout');
                    }, 8000);
                } catch(e) {
                    if (window.ImageBridge) window.ImageBridge.postError(targetUrl, e.message);
                }
            })();""".trimIndent()

            wv.evaluateJavascript(js, null)
        }

        return try {
            future.get(15, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            Log.w(TAG, "[IMG_PROXY] Timeout aguardando imagem: $url")
            pendingFetches.remove(url)
            null
        }
    }

    private class ImageBridge {
        @JavascriptInterface
        fun postImage(url: String, base64Data: String) {
            val future = pendingFetches.remove(url)
            if (base64Data.isBlank()) {
                future?.complete(null)
                return
            }
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                Log.i(TAG, "[IMG_PROXY] Bridge recebeu ${bytes.size} bytes para $url")
                future?.complete(bytes)
            } catch (e: Throwable) {
                Log.e(TAG, "[IMG_PROXY] Erro ao decodificar Base64: ${e.message}")
                future?.complete(null)
            }
        }

        @JavascriptInterface
        fun postError(url: String, error: String) {
            Log.w(TAG, "[IMG_PROXY] Bridge erro ($error) para $url")
            val future = pendingFetches.remove(url)
            future?.complete(null)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    fun shutdownHelper() {
        val wv = synchronized(this) { helperWebView.also { helperWebView = null } } ?: return
        isHelperReady.set(false)
        val cleanup = Runnable {
            runCatching { wv.stopLoading() }
            runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
            runCatching { wv.destroy() }
            Log.i(TAG, "[IMG_PROXY] Helper WebView destruído")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else Handler(Looper.getMainLooper()).post(cleanup)
    }

    fun ensureHelperWebView(activity: Activity) {
        if (helperWebView != null) return
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            // v228: GONE + 1x1 + SOFTWARE — helper só precisa de sessão/cookies,
            // sem surface de composição (LMK matava o app com WebViews tela cheia).
            val wv = WebView(activity).apply {
                visibility = android.view.View.GONE
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false
                isLongClickable = false
                setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    val ua = CloudflareSolver.lastUserAgent
                        ?: WebViewResolver.webViewUserAgent
                        ?: DEFAULT_UA
                    userAgentString = ua
                }
                addJavascriptInterface(ImageBridge(), "ImageBridge")
                addJavascriptInterface(CloudflareSolver.HtmlBridge(), "HtmlBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isHelperReady.set(true)
                        Log.i(TAG, "[IMG_PROXY] Helper WebView pronto: $url")
                    }
                }
            }
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            root.addView(wv, 0)
            wv.loadUrl("https://redecanais.af/")
            helperWebView = wv
            Log.i(TAG, "[IMG_PROXY] Helper WebView inicializado offscreen")
        } catch (e: Throwable) {
            Log.w(TAG, "[IMG_PROXY] Falha ao criar Helper WebView: ${e.message}")
        }
    }
}
