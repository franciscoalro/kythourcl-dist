package com.RedeCanaisAF

import android.annotation.SuppressLint
import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object CloudflareSolver {
    private val catalogMutex = kotlinx.coroutines.sync.Mutex()
    private const val TAG = "RedeCanaisAF-Trace"
    private val nonCatalogHosts = setOf(
        "static.cloudflareinsights.com",
        "acscdn.com"
    )
    var lastUserAgent: String? = null

    // v145: persistência cf_clearance — após validar o Turnstile uma vez, o cookie é salvo em
    // SharedPreferences com TTL 12h e restaurado no cold start, evitando novo diálogo.
    private const val PREF_NAME = "redecanais_af_cf"
    private const val KEY_CF_CLEARANCE = "cf_clearance"
    private const val KEY_CF_BM = "cf_bm"
    private const val KEY_SAVED_AT = "saved_at"
    private const val CLEARANCE_TTL_MS = 12 * 60 * 60 * 1000L
    @Volatile private var persistenceRestoreDone = false

    private fun prefs() = runCatching {
        CommonActivity.activity?.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
    }.getOrNull()

    fun saveClearanceFromCookieManager(url: String) {
        try {
            val raw = CookieManager.getInstance().getCookie(url) ?: return
            val clearance = Regex("cf_clearance=([^;]+)").find(raw)?.groupValues?.get(1) ?: return
            if (clearance.isBlank()) return
            val cfBm = Regex("__cf_bm=([^;]+)").find(raw)?.groupValues?.get(1)
            val now = System.currentTimeMillis()
            prefs()?.edit()
                ?.putString(KEY_CF_CLEARANCE, clearance)
                ?.putString(KEY_CF_BM, cfBm)
                ?.putLong(KEY_SAVED_AT, now)
                ?.apply()
            Log.i(TAG, "[CF_PERSIST] cf_clearance salvo (${clearance.take(12)}...) cf_bm=${cfBm != null} age=0ms")
        } catch (e: Throwable) {
            Log.w(TAG, "[CF_PERSIST] falha ao salvar clearance: ${e.message}")
        }
    }

    fun restoreClearanceIfValid(mainUrl: String): Boolean {
        if (persistenceRestoreDone) {
            val existing = runCatching { CookieManager.getInstance().getCookie(mainUrl) ?: "" }.getOrNull() ?: ""
            if (existing.contains("cf_clearance")) return true
        }
        try {
            val p = prefs() ?: return false
            val savedAt = p.getLong(KEY_SAVED_AT, 0L)
            val age = System.currentTimeMillis() - savedAt
            if (savedAt == 0L || age > CLEARANCE_TTL_MS) {
                if (savedAt != 0L) {
                    Log.i(TAG, "[CF_PERSIST] clearance expirado age=${age / 1000}s > 12h — limpando")
                    p.edit().clear().apply()
                }
                persistenceRestoreDone = true
                return false
            }
            val clearance = p.getString(KEY_CF_CLEARANCE, null) ?: return false
            if (clearance.isBlank()) return false
            val cfBm = p.getString(KEY_CF_BM, null)
            val cm = CookieManager.getInstance()
            // restaura no CookieManager para o host canônico e para redecanaistv (player)
            fun setFor(url: String) {
                cm.setCookie(url, "cf_clearance=$clearance; Path=/; Domain=.redecanais.af; Secure; SameSite=None")
                if (!cfBm.isNullOrBlank()) cm.setCookie(url, "__cf_bm=$cfBm; Path=/; Domain=.redecanais.af; Secure; SameSite=None")
            }
            setFor(mainUrl)
            setFor("https://redecanaistv.af/")
            cm.flush()
            persistenceRestoreDone = true
            Log.i(TAG, "[CF_PERSIST] cf_clearance restaurado age=${age / 1000}s (${clearance.take(12)}...) cf_bm=${cfBm != null}")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "[CF_PERSIST] falha ao restaurar clearance: ${e.message}")
            return false
        }
    }

    fun clearPersistedClearance() {
        try {
            prefs()?.edit()?.clear()?.apply()
            persistenceRestoreDone = false
            Log.i(TAG, "[CF_PERSIST] persistência limpa")
        } catch (_: Throwable) {}
    }

    internal fun challengeUserAgent(rawUserAgent: String): String {
        val chromeToken = Regex("Chrome/[0-9.]+").find(rawUserAgent)?.value ?: return rawUserAgent
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) $chromeToken Safari/537.36"
    }

    suspend fun currentUserAgent(): String {
        lastUserAgent?.takeIf { it.isNotBlank() }?.let { return it }
        val rawUserAgent = withContext(Dispatchers.Main) {
            runCatching {
                CommonActivity.activity?.let { WebSettings.getDefaultUserAgent(it) }
            }.getOrNull()
        }?.takeIf { it.isNotBlank() }
            ?: WebViewResolver.webViewUserAgent
            ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"
        val resolved = challengeUserAgent(rawUserAgent)
        lastUserAgent = resolved
        if (resolved != rawUserAgent) {
            Log.i(TAG, "[CF] User-Agent WebView normalizado para perfil Chrome desktop")
        }
        Log.i(TAG, "[CF] User-Agent compartilhado HTTP/WebView: $resolved")
        return resolved
    }

    fun invalidateClearance(url: String) {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setCookie(
                url,
                "cf_clearance=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/; Domain=.redecanais.af; Secure; SameSite=None"
            )
            cookieManager.flush()
            Log.w(TAG, "[CF] cf_clearance invalidado após resposta de challenge")
        } catch (e: Throwable) {
            Log.w(TAG, "[CF] Falha ao invalidar cf_clearance: ${e.message}")
        }
        clearPersistedClearance()
    }

    // v130: cache de HTML capturado por URL dentro da MESMA sessão TLS do diálogo. O Turnstile
    // managed resolve UMA vez no WebView do diálogo; navegar para as outras URLs do catálogo no
    // MESMO WebView carrega SEM re-challenge (~2-5s por página, vs ~70s por diálogo). Os REQs
    // seguintes leem o cache e não reabrem diálogo — fica dentro do deadline de 120s do framework.
    private val capturedHtmlByUrl = ConcurrentHashMap<String, String>()
    @Volatile
    private var catalogUrls: List<String> = emptyList()

    // v146: cache de disco (30min) — HtmlGate.fetch retorna em <500ms mesmo após cold start com 403.
    // O HTML capturado do WebView é serializado em filesDir/redecanais_af_html_cache.json com ts por URL.
    private const val DISK_HTML_FILE = "redecanais_af_html_cache.json"
    private const val DISK_CACHE_TTL_MS = 30 * 60 * 1000L
    @Volatile private var diskCacheRestored = false
    private val diskHtmlTsByUrl = ConcurrentHashMap<String, Long>()

    private fun diskCacheFile(): File? = runCatching {
        val ctx = CommonActivity.activity ?: return null
        File(ctx.filesDir, DISK_HTML_FILE)
    }.getOrNull()

    fun persistCapturedHtmlToDisk() {
        try {
            val f = diskCacheFile() ?: return
            val now = System.currentTimeMillis()
            // atualiza timestamps das entradas atuais
            capturedHtmlByUrl.keys.forEach { diskHtmlTsByUrl[it] = now }
            val obj = org.json.JSONObject()
            for ((url, html) in capturedHtmlByUrl) {
                val ts = diskHtmlTsByUrl[url] ?: now
                val entry = org.json.JSONObject()
                entry.put("ts", ts)
                entry.put("html", html)
                obj.put(url, entry)
            }
            // escreve atômico
            val tmp = File(f.parent, "${f.name}.tmp")
            tmp.writeText(obj.toString())
            if (f.exists()) f.delete()
            tmp.renameTo(f)
            Log.i(TAG, "[CF_DISK] HTML cache salvo em disco: ${capturedHtmlByUrl.size} URLs, file=${f.absolutePath} len=${obj.toString().length}")
        } catch (e: Throwable) {
            Log.w(TAG, "[CF_DISK] falha ao salvar cache em disco: ${e.message}")
        }
    }

    fun restoreDiskCacheIfNeeded(): Boolean {
        if (diskCacheRestored) return capturedHtmlByUrl.isNotEmpty()
        diskCacheRestored = true
        try {
            val f = diskCacheFile() ?: return false
            if (!f.exists() || f.length() == 0L) return false
            val raw = f.readText()
            if (raw.isBlank()) return false
            val obj = org.json.JSONObject(raw)
            val now = System.currentTimeMillis()
            var restored = 0
            val it = obj.keys()
            while (it.hasNext()) {
                val url = it.next()
                val entry = obj.optJSONObject(url) ?: continue
                val ts = entry.optLong("ts", 0L)
                if (ts == 0L || now - ts > DISK_CACHE_TTL_MS) continue
                val html = entry.optString("html", "")
                if (html.isBlank() || isChallengeContent(html)) continue
                // só preenche se ainda não está em RAM (RAM tem prioridade — mais recente)
                if (!capturedHtmlByUrl.containsKey(url)) {
                    capturedHtmlByUrl[url] = html
                    diskHtmlTsByUrl[url] = ts
                    restored++
                }
            }
            if (restored > 0) Log.i(TAG, "[CF_DISK] HTML cache restaurado do disco: $restored URLs (total RAM=${capturedHtmlByUrl.size})")
            return restored > 0
        } catch (e: Throwable) {
            Log.w(TAG, "[CF_DISK] falha ao restaurar cache de disco: ${e.message}")
            return false
        }
    }

    fun getDiskCachedHtml(url: String): String? {
        // tenta RAM primeiro
        capturedHtml(url)?.let { return it }
        restoreDiskCacheIfNeeded()
        return capturedHtmlByUrl[url]?.takeIf { !isChallengeContent(it) }
    }

    fun setCatalogUrls(urls: List<String>) {
        if (catalogUrls != urls) {
            catalogUrls = urls
            // v148: preserva HTML de disco/RAM que já pertence a estas URLs (antes: clear() apagava
            // o cache restaurado em <100ms e forçava WebView de 30-45s em TODO cold start → "Pré-
            // carregamento" infinito). Só descarta entradas fora do catálogo atual.
            val keep = capturedHtmlByUrl.filterKeys { it in urls }
            val removed = capturedHtmlByUrl.size - keep.size
            if (removed != 0 || capturedHtmlByUrl.size != keep.size) {
                capturedHtmlByUrl.clear()
                capturedHtmlByUrl.putAll(keep)
                // mantém só timestamps das URLs mantidas
                val keepTs = diskHtmlTsByUrl.filterKeys { it in urls }
                diskHtmlTsByUrl.clear()
                diskHtmlTsByUrl.putAll(keepTs)
            }
            Log.i(TAG, "[CF] catalogUrls setadas (${urls.size}) — cache preservado keep=${keep.size} removed=$removed RAM=${capturedHtmlByUrl.size}")
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

    // v144: o Turnstile managed renderiza o widget em iframe CROSS-ORIGIN
    // (challenges.cloudflare.com / challenge-platform). O JS do documento pai NAO pode ler o
    // conteudo do iframe (SecurityError), mas pode ler o RETANGULO dele via getBoundingClientRect.
    // O toque Android real e disparado nas coordenadas do checkbox dentro desse retangulo.
    // Fallback legado: input[id^="cf-chl-widget-"][id$="_response"] no DOM principal.
private const val TURNSTILE_TAP_PROBE_JS = """
        (function() {
            try {
                var frames = document.querySelectorAll(
                    'iframe[src*="challenges.cloudflare.com"], ' +
                    'iframe[src*="challenge-platform"], ' +
                    'iframe[id^="cf-chl-"], ' +
                    'iframe[data-testid*="turnstile"], ' +
                    'iframe[src*="turnstile"]'
                );
                var viewportWidth = window.innerWidth || document.documentElement.clientWidth || 0;
                var viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
                for (var i = 0; i < frames.length; i++) {
                    var rect = frames[i].getBoundingClientRect();
                    if (rect.width >= 30 && rect.height >= 30) {
                        return [
                            'iframe_rect', rect.left, rect.top, rect.width, rect.height,
                            viewportWidth, viewportHeight
                        ].join('|');
                    }
                }
                var response = document.querySelector(
                    'input[id^="cf-chl-widget-"][id$="_response"], input[name="cf-turnstile-response"]'
                );
                if (!response) return 'iframe_missing';
                var host = response.parentElement;
                while (host && host !== document.body) {
                    var r2 = host.getBoundingClientRect();
                    if (r2.width > 0 && r2.height >= 40 && r2.height <= 150) {
                        return [
                            'turnstile_rect', r2.left, r2.top, r2.height,
                            viewportWidth, viewportHeight
                        ].join('|');
                    }
                    host = host.parentElement;
                }
                return 'turnstile_rect_missing';
            } catch (e) {
                return 'iframe_probe_error:' + e.message;
            }
        })();

    """

    internal fun turnstileTapPoint(
        probeResult: String?,
        viewWidth: Int,
        viewHeight: Int
    ): Pair<Float, Float>? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val decoded = probeResult
            ?.trim()
            ?.removeSurrounding("\"")
            ?.replace("\\\"", "\"")
            ?: return null
        val parts = decoded.split('|')
        if (parts.size < 6) return null

        val viewportWidth = parts[parts.size - 2].toFloatOrNull()?.takeIf { it > 0f } ?: return null
        val viewportHeight = parts[parts.size - 1].toFloatOrNull()?.takeIf { it > 0f } ?: return null

        return when (parts[0]) {
            // v144: retangulo do IFRAME do Turnstile (cross-origin) — o clique e no checkbox
            // que ocupa os primeiros ~65 CSS px do widget padrao (300 x 65).
            // Formato: iframe_rect|left|top|width|height|viewportWidth|viewportHeight
            "iframe_rect" -> {
                if (parts.size != 7) return null
                val left = parts[1].toFloatOrNull() ?: return null
                val top = parts[2].toFloatOrNull() ?: return null
                val width = parts[3].toFloatOrNull() ?: return null
                val height = parts[4].toFloatOrNull() ?: return null
                if (width < 30f || height < 30f) return null
                val checkboxCenterX = if (width >= 200f) 40f else width / 2f
                scaleToView(
                    left + checkboxCenterX,
                    top + minOf(height / 2f, 32.5f),
                    viewWidth, viewHeight, viewportWidth, viewportHeight
                )
            }
            // fallback legado: turnstile_rect|left|top|height|viewportWidth|viewportHeight
            "turnstile_rect" -> {
                if (parts.size != 6) return null
                val left = parts[1].toFloatOrNull() ?: return null
                val top = parts[2].toFloatOrNull() ?: return null
                val height = parts[3].toFloatOrNull() ?: return null
                if (height <= 0f) return null
                scaleToView(
                    left + 32f,
                    top + minOf(height / 2f, 32.5f),
                    viewWidth, viewHeight, viewportWidth, viewportHeight
                )
            }
            else -> null
        }
    }

    // v144: converte CSS px do viewport do WebView para px fisicos da view (escala dpr).
    private fun scaleToView(
        cssX: Float,
        cssY: Float,
        viewWidth: Int,
        viewHeight: Int,
        viewportWidth: Float,
        viewportHeight: Float
    ): Pair<Float, Float>? {
        val touchX = cssX * viewWidth / viewportWidth
        val touchY = cssY * viewHeight / viewportHeight
        if (touchX !in 0f..viewWidth.toFloat() || touchY !in 0f..viewHeight.toFloat()) return null
        return touchX to touchY
    }

    internal fun isChallengeContent(content: String): Boolean {
        if (content.isBlank()) return false
        if (isIpBannedContent(content)) return true
        return content.contains("Just a moment", ignoreCase = true) ||
            content.contains("Um momento", ignoreCase = true) ||
            content.contains("Checking your browser", ignoreCase = true) ||
            content.contains("Verificando", ignoreCase = true) ||
            content.contains("Error code 520", ignoreCase = true) ||
            content.contains("Error code 522", ignoreCase = true) ||
            content.contains("Error code 524", ignoreCase = true) ||
            content.contains("Web server is returning", ignoreCase = true) ||
            content.contains("id=\"challenge-form\"", ignoreCase = true) ||
            (content.contains("challenge-platform", ignoreCase = true) &&
                content.contains("cf-chl-", ignoreCase = true))
    }

    // v147: Error 1006 / "Access denied — banned your IP" é bloqueio PERMANENTE do IP pelo dono do site
    // no firewall da Cloudflare. Não é challenge solucionável — abrir WebView só piora (re-bane).
    // Detectado via browser-harness em 2026-08-30 (IP 177.37.187.217 banido após ~20 navegações agressivas).
    internal fun isIpBannedContent(content: String): Boolean {
        if (content.isBlank()) return false
        return content.contains("Error 1006", ignoreCase = true) ||
            content.contains("Access denied", ignoreCase = true) ||
            content.contains("banned your IP", ignoreCase = true) ||
            content.contains("Attention Required", ignoreCase = true) && content.contains("cf-error-details", ignoreCase = true)
    }

    // v128: serializa os diálogos de verificação — o Turnstile managed de redecanais.af SÓ resolve
    // quando o WebView do diálogo tem foco/visibilidade. Com 4 REQs paralelos (uma categoria cada)
    // abrindo 4 diálogos ao mesmo tempo, NENHUM resolve (todos target_page_loaded=false após 60s).
    // Um diálogo por vez + cf_clearance compartilhado no CookieManager resolve o catálogo inteiro.
    private val interactiveMutex = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solveInteractive(url: String, timeoutMs: Long = 30000L, force: Boolean = false): String? {
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
            if (cookiesNow.contains("cf_clearance") && !force) {
                Log.i(TAG, "[CF] cf_clearance presente após lock (outro REQ resolveu) — pulando diálogo")
                return@withLock ""
            }
            if (cookiesNow.contains("cf_clearance") && force) {
                Log.w(TAG, "[CF] cf_clearance presente, mas force=true — reabrindo diálogo")
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
        val targetLoaded = AtomicBoolean(false)
        // v128: sinaliza que o HTML da página alvo foi capturado (ou falhou) — o poller aguarda
        // antes de destruir o WebView, senão o callback do evaluateJavascript nunca roda.
        val htmlCaptureDone = CompletableDeferred<Boolean>()
        // v130: o primeiro diálogo a resolver navega o MESMO WebView (sessão TLS que resolveu o
        // Turnstile) pelas demais URLs do catálogo — sem re-challenge, ~2-5s por página. A URL da
        // captura atual acompanha o onPageFinished (o challenge serve a URL alvo no finishedUrl).
        var currentUrl = url
        val pendingExtras = extraUrls.toMutableList()
        val isPollingActive = AtomicBoolean(true)
        // v141: mantém uma única WebView interativa visível durante o challenge.
        var interactiveWebView: WebView? = null
        var lastTurnstileTapAt = 0L
        var tapJitterIndex = 0


        fun tryTapTurnstile(view: WebView, reason: String) {
            val now = SystemClock.uptimeMillis()
            if (!isPollingActive.get() || now - lastTurnstileTapAt < 5000L) return
            view.evaluateJavascript(TURNSTILE_TAP_PROBE_JS.trimIndent()) { probeResult ->
                if (!isPollingActive.get() || !view.isAttachedToWindow) return@evaluateJavascript
                val point = turnstileTapPoint(probeResult, view.width, view.height)
                if (point == null) {
                    Log.d(TAG, "[CF] toque Turnstile sem alvo | motivo=$reason | probe=$probeResult")
                    return@evaluateJavascript
                }

                lastTurnstileTapAt = SystemClock.uptimeMillis()
                // v144: jitter leve (±6px) para o toque nao cair sempre no mesmo pixel.
                val jitter = ((tapJitterIndex++ % 5) - 2) * 3f
                val tapPoint = (point.first + jitter) to (point.second + jitter)
                val downTime = lastTurnstileTapAt
                MotionEvent.obtain(
                    downTime,
                    downTime,
                    MotionEvent.ACTION_DOWN,
                    tapPoint.first,
                    tapPoint.second,
                    0
                ).also { event ->
                    view.dispatchTouchEvent(event)
                    event.recycle()
                }
                view.postDelayed({
                    if (!isPollingActive.get() || !view.isAttachedToWindow) return@postDelayed
                    MotionEvent.obtain(
                        downTime,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_UP,
                        tapPoint.first,
                        tapPoint.second,
                        0
                    ).also { event ->
                        view.dispatchTouchEvent(event)
                        event.recycle()
                    }
                    Log.i(
                        TAG,
                        "[CF] toque Android no Turnstile x=${tapPoint.first.toInt()} y=${tapPoint.second.toInt()} | motivo=$reason"
                    )
                }, 80L)
            }
        }

        withContext(Dispatchers.Main) {
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                val rootLayout = activity.findViewById<ViewGroup>(android.R.id.content)

                val wv = WebView(activity).apply {
                    visibility = android.view.View.VISIBLE
                    alpha = 1.0f
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        lastUserAgent?.takeIf { it.isNotBlank() }?.let { userAgentString = it }
                        // v140: Turnstile pode depender de recursos gráficos/preloads para finalizar a prova.
                        // Mantém imagens habilitadas no WebView interativo; a otimização fica fora do challenge.
                        blockNetworkImage = false
                        loadsImagesAutomatically = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        javaScriptCanOpenWindowsAutomatically = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    lastUserAgent = settings.userAgentString
                    Log.i(TAG, "[CF] WebView BG User-Agent: $lastUserAgent")
                    webViewClient = object : WebViewClient() {
                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            Log.w(TAG, "[CF_WV] onRenderProcessGone seguro acionado (didCrash=${detail?.didCrash()})")
                            try {
                                (view?.parent as? ViewGroup)?.removeView(view)
                                view?.destroy()
                            } catch (_: Throwable) {}
                            return true
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (request != null && shouldBlockResource(request)) {
                                return emptyResource()
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            CookieManager.getInstance().flush()
                            val cookies = CookieManager.getInstance().getCookie(finishedUrl ?: url) ?: ""
                            val hasClearance = cookies.contains("cf_clearance")
                            val isTargetUrl = finishedUrl?.contains("redecanais.af") == true &&
                                    !finishedUrl.contains("challenge-platform") &&
                                    finishedUrl != "about:blank"

                            Log.d(TAG, "[CF] onPageFinished url=$finishedUrl | clearance=$hasClearance | target_url=$isTargetUrl")

                            // v132: auto-click no checkbox Turnstile dentro do iframe via JS.
                            // Tenta o input direto no document principal e também via iframe.contentDocument.
                            view?.evaluateJavascript("""
                                (function() {
                                    try {
                                        // Direto no documento (alguns layouts)
                                        var cb = document.querySelector('input[type="checkbox"], .ctp-checkbox-label, [id*="turnstile"] input');
                                        if (cb) { cb.click(); return 'clicked_direct'; }
                                        // Dentro dos iframes (Turnstile managed)
                                        var frames = document.querySelectorAll('iframe');
                                        for (var i = 0; i < frames.length; i++) {
                                            try {
                                                var doc = frames[i].contentDocument || frames[i].contentWindow.document;
                                                if (!doc) continue;
                                                var icb = doc.querySelector('input[type="checkbox"], .ctp-checkbox-label, [class*="checkbox"]');
                                                if (icb) { icb.click(); return 'clicked_iframe_' + i; }
                                            } catch(e2) {}
                                        }
                                        return 'no_checkbox';
                                    } catch(e) { return 'error:' + e.message; }
                                })();
                            """.trimIndent()) { result ->
                                Log.d(TAG, "[CF] auto-click resultado=$result | url=$finishedUrl")
                            }
                            view?.let { tryTapTurnstile(it, "page_finished") }

                            if (isTargetUrl) {
                                currentUrl = finishedUrl ?: url
                                var pollAttempts = 0
                                fun fetchNext(cv: WebView?) {
                                    if (!isPollingActive.get() || cv == null) {
                                        isPollingActive.set(false)
                                        htmlCaptureDone.complete(true)
                                        return
                                    }
                                    val next = pendingExtras.firstOrNull()
                                    if (next == null) {
                                        isPollingActive.set(false)
                                        htmlCaptureDone.complete(true)
                                        return
                                    }
                                    pendingExtras.remove(next)
                                    Log.i(TAG, "[CF] Buscando próxima URL do catálogo via fetch (mesma sessão TLS): $next")
                                    cv.evaluateJavascript(
                                        "(function(){return fetch('$next',{credentials:'include'}).then(function(r){return r.text();}).then(function(t){return t;}).catch(function(e){return 'FETCH_ERROR';});})();"
                                    ) { htmlOrErr ->
                                        if (!isPollingActive.get()) return@evaluateJavascript
                                        val raw = htmlOrErr?.trim()?.removeSurrounding("\"")
                                            ?.replace("\\u003C", "<")
                                            ?.replace("\\u003E", ">")
                                            ?.replace("\\\"", "\"") ?: ""
                                        val ok = !raw.startsWith("FETCH_ERROR") &&
                                            raw.contains("pm-video-thumb") &&
                                            !isChallengeContent(raw) &&
                                            raw.length > 1000
                                        if (ok) {
                                            lastSolvedHtml = raw
                                            capturedHtmlByUrl[next] = raw
                                            runCatching { persistCapturedHtmlToDisk() }
                                            Log.i(TAG, "[CF] HTML extra via fetch: len=${raw.length} | url=$next")
                                            fetchNext(cv)
                                        } else {
                                            Log.w(TAG, "[CF] fetch sem cards (len=${raw.length}) — fallback loadUrl: $next")
                                            cv.loadUrl(next)
                                        }
                                    }
                                }

                                fun pollAndCapture(cv: WebView?) {
                                    if (!isPollingActive.get() || cv == null) return
                                    cv.evaluateJavascript(
                                        "(function() { var c = document.querySelector('.pm-video-thumb, .pm-li-video, .video-thumb, article, div[class*=\"video-thumb\"]'); var cards = document.querySelectorAll('.pm-video-thumb, .pm-li-video, .video-thumb, article, div[class*=\"video-thumb\"]').length; var links = document.querySelectorAll('a[href]').length; return JSON.stringify({cards: cards, links: links, title: document.title || '', firstCard: (c ? c.outerHTML.substring(0, 400) : ''), body: (document.body ? document.body.innerText : '').substring(0, 120)}); })();"
                                    ) { diagJson ->
                                        if (!isPollingActive.get()) return@evaluateJavascript
                                        val diag = diagJson?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: "{}"
                                        pollAttempts++
                                        val cardCount = Regex("\"cards\":(\\d+)").find(diag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                        val linkCount = Regex("\"links\":(\\d+)").find(diag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                        val title = Regex("\"title\":\"([^\"]*)\"").find(diag)?.groupValues?.get(1) ?: ""
                                        val isChallenge = isChallengeContent("$title $diag")
                                        Log.d(TAG, "[CF] polling cards=$cardCount links=$linkCount isChallenge=$isChallenge (tentativa $pollAttempts) | url=$currentUrl")

                                        // Re-dispara auto-click periodicamente enquanto o challenge estiver na tela
                                        if (isChallenge && pollAttempts % 3 == 0 && isPollingActive.get()) {
                                            cv.evaluateJavascript("""
                                                (function() {
                                                    try {
                                                        var cb = document.querySelector('input[type="checkbox"], .ctp-checkbox-label, [id*="turnstile"] input');
                                                        if (cb) { cb.click(); return 'reclick_direct'; }
                                                        var frames = document.querySelectorAll('iframe');
                                                        for (var i = 0; i < frames.length; i++) {
                                                            try {
                                                                var doc = frames[i].contentDocument || frames[i].contentWindow.document;
                                                                if (!doc) continue;
                                                                var icb = doc.querySelector('input[type="checkbox"], .ctp-checkbox-label, [class*="checkbox"]');
                                                                if (icb) { icb.click(); return 'reclick_iframe_' + i; }
                                                            } catch(e2) {}
                                                        }
                                                        return 'no_cb';
                                                    } catch(e) { return 'err'; }
                                                })();
                                            """.trimIndent()) { r ->
                                                Log.d(TAG, "[CF] repolling auto-click: $r")
                                            }
                                            tryTapTurnstile(cv, "poll_$pollAttempts")
                                        }

                                        if (cardCount > 0 || (linkCount >= 10 && !isChallenge)) {
                                            cv.evaluateJavascript(
                                                "(function() { return document.getElementsByTagName('html')[0].outerHTML; })();"
                                            ) { html ->
                                                if (!isPollingActive.get()) return@evaluateJavascript
                                                if (!html.isNullOrBlank() && html != "null") {
                                                    val decoded = html.removeSurrounding("\"")
                                                        .replace("\\u003C", "<")
                                                        .replace("\\u003E", ">")
                                                        .replace("\\\"", "\"")
                                                        .replace("\\n", "\n")
                                                        .replace("\\r", "\r")
                                                    lastSolvedHtml = decoded
                                                    capturedHtmlByUrl[currentUrl] = decoded
                                                    targetLoaded.set(true)
                                                    Log.i(TAG, "[CF] HTML alvo capturado (BG): len=${decoded.length} | cards=$cardCount | url=$currentUrl")
                                                    Log.i(TAG, "[CF] Resolvido! clearance_present=$hasClearance | target_page_loaded=true")
                                                    runCatching { persistCapturedHtmlToDisk() }
                                                }
                                                fetchNext(cv)
                                            }
                                        } else if (pollAttempts >= 60 || !isPollingActive.get()) {
                                            Log.w(TAG, "[CF] polling limite atingido (30s) sem cards reais | isChallenge=$isChallenge")
                                            isPollingActive.set(false)
                                            htmlCaptureDone.complete(false)
                                        } else {
                                            if (isPollingActive.get()) {
                                                cv.postDelayed({ pollAndCapture(cv) }, 500)
                                            }
                                        }
                                    }
                                }
                                pollAndCapture(view)
                            }
                        }
                    }
                }
                interactiveWebView = wv

                // v141: a WebView precisa estar visível e receber foco para permitir interação manual.
                wv.visibility = android.view.View.VISIBLE
                wv.alpha = 0.01f
                wv.translationX = -50000f
                wv.translationY = -50000f
                wv.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                rootLayout.addView(wv)
                Log.i(TAG, "[CF] WebView 100% HEADLESS (invisível) acoplada em background | url=$url")
                wv.loadUrl(url)

                // O widget costuma estar pronto antes de onPageFinished, que pode aguardar por
                // sub-recursos do challenge por dezenas de segundos. Sonda o alvo durante a carga.
                fun probeTurnstileWhileLoading(attempt: Int) {
                    if (!isPollingActive.get() || attempt > 30) return
                    tryTapTurnstile(wv, "loading_$attempt")
                    wv.postDelayed({ probeTurnstileWhileLoading(attempt + 1) }, 1000L)
                }
                wv.postDelayed({ probeTurnstileWhileLoading(1) }, 1000L)

                Log.i(TAG, "[CF] WebView interativa carregando url=$url")
            } catch (e: Throwable) {
                Log.e(TAG, "[CF] Erro ao criar WebView BG: ${e.message}")
            }

        }

        try {
            withTimeoutOrNull(timeoutMs) { htmlCaptureDone.await() }
        } finally {
            isPollingActive.set(false)
            withContext(NonCancellable + Dispatchers.Main) {
                try {
                    // v132: não há mais AlertDialog — nenhum dismiss necessário.
                    interactiveWebView?.let { wv ->
                        wv.stopLoading()
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv.destroy()
                    }
                    interactiveWebView = null
                    Log.d(TAG, "[CF] WebView BG removido e destruído")
                } catch (_: Throwable) {}
            }
        }
        val finalCookies = CookieManager.getInstance().getCookie(url) ?: ""
        val finalClearance = finalCookies.contains("cf_clearance")
        Log.i(TAG, "[CF] clearance_present=$finalClearance | target_page_loaded=${targetLoaded.get()} (finished)")
        // v145: persiste cf_clearance válido (resolver Turnstile uma vez libera cold starts seguintes)
        if (finalClearance) saveClearanceFromCookieManager(url)
        // v130: retorna o HTML da URL PEDIDA (o WebView pode ter navegado para as URLs extras do
        // catálogo depois de capturar esta — lastSolvedHtml seria o da última navegação).
        val captured = capturedHtmlByUrl[url]
        return when {
            !captured.isNullOrBlank() && !isChallengeContent(captured) -> captured
            finalClearance -> ""
            else -> null
        }
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
                            lastUserAgent?.takeIf { it.isNotBlank() }?.let { userAgentString = it }
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
                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            Log.w(TAG, "[CF_WV] onRenderProcessGone seguro acionado (didCrash=${detail?.didCrash()})")
                            try {
                                (view?.parent as? ViewGroup)?.removeView(view)
                                view?.destroy()
                            } catch (_: Throwable) {}
                            return true
                        }

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
                                    
                                    val isChallenge = isChallengeContent(unquoted) ||
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
                wv.visibility = android.view.View.VISIBLE
                wv.alpha = 0.01f
                wv.translationX = -50000f
                wv.translationY = -50000f
                wv.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                rootLayout.addView(wv)
                wv.loadUrl(url)
            } catch (e: Throwable) {
                Log.e(TAG, "[CF_HTML] Erro na extração via WebView: ${e.message}")
            }
        }

        try {
            withTimeoutOrNull(timeoutMs) {
                while (!isDone.get()) {
                    delay(300)
                }
                true
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                try {
                    wvVar?.let {
                        it.stopLoading()
                        rootLayout.removeView(it)
                        it.destroy()
                    }
                    wvVar = null
                    Log.d(TAG, "[CF_HTML] WebView removido e destruído")
                } catch (_: Throwable) {}
            }
        }

        return resultHtml
    }
}
