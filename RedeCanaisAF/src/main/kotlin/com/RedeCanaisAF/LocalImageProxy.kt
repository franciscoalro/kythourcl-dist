package com.RedeCanaisAF

import android.annotation.SuppressLint
import android.app.Activity
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

    @Volatile private var helperWebView: WebView? = null
    private val isHelperReady = AtomicBoolean(false)
    private val pendingFetches = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()

    private val directHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .proxy(java.net.Proxy.NO_PROXY)
            .build()
    }

    init {
        startServer()
    }

    @Synchronized
    fun startServer() {
        if (serverSocket != null && !serverSocket!!.isClosed) return
        try {
            val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
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
        if (url.isBlank() || url.startsWith("http://127.0.0.1") || url.startsWith("data:")) {
            return url
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

        val encoded = URLEncoder.encode(url, "UTF-8")
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
            val targetUrl = URLDecoder.decode(rawEncoded, "UTF-8")
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
            val bytes = fetchImageBytes(targetUrl)
            if (bytes != null && bytes.isNotEmpty()) {
                try {
                    cachedFile?.writeBytes(bytes)
                } catch (_: Throwable) {}
                Log.i(TAG, "[IMG_PROXY] Sucesso via WebView! Servindo ${bytes.size} bytes para $targetUrl")
                sendResponse(socket, bytes)
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
            val cookies = runCatching {
                CookieManager.getInstance().getCookie("https://redecanais.af")
            }.getOrNull().orEmpty()

            val ua = CloudflareSolver.lastUserAgent
                ?: WebViewResolver.webViewUserAgent
                ?: DEFAULT_UA

            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Referer", "https://redecanais.af/")
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")

            if (cookies.isNotBlank()) {
                reqBuilder.header("Cookie", cookies)
            }

            val resp = directHttpClient.newCall(reqBuilder.build()).execute()
            if (resp.isSuccessful) {
                val bytes = resp.body?.bytes()
                if (bytes != null && bytes.isNotEmpty() && !bytes.isHtmlResponse()) {
                    return bytes
                }
            }
            null
        } catch (_: Throwable) {
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
                
                // 1. Tenta Fetch direto no contexto do site
                try {
                    const r = await fetch(targetUrl, {
                        credentials: 'include'
                    });
                    if (r.ok) {
                        const blob = await r.blob();
                        const fr = new FileReader();
                        fr.onload = () => {
                            const res = fr.result || '';
                            const idx = res.indexOf(',');
                            const b64 = idx >= 0 ? res.substring(idx + 1) : '';
                            if (b64 && window.ImageBridge) {
                                window.ImageBridge.postImage(targetUrl, b64);
                            }
                        };
                        fr.readAsDataURL(blob);
                        return;
                    }
                } catch(e) {}

                // 2. Fallback: Image Tag + Canvas Draw (same-origin, no CORS restriction)
                try {
                    const img = new Image();
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
                    }, 10000);
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
    fun ensureHelperWebView(activity: Activity) {
        if (helperWebView != null) return
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val wv = WebView(activity).apply {
                visibility = android.view.View.VISIBLE
                alpha = 0.01f
                translationX = -50000f
                translationY = -50000f
                isFocusable = false
                isClickable = false
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
