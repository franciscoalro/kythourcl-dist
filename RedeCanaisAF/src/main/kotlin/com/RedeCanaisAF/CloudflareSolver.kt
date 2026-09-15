package com.RedeCanaisAF

import android.annotation.SuppressLint
import android.app.Activity
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/AP1A.240505.005) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.113 Mobile Safari/537.36"
    // v158: static.cloudflareinsights.com e acscdn.com REMOVIDOS — são infraestrutura
    // Cloudflare. Bloqueá-los impede o Turnstile managed de injetar o iframe (beacon.min.js
    // é sinal de verificação). emptyResource() retornava 200/0-bytes silenciosamente.
    private val nonCatalogHosts = setOf<String>()
    var lastUserAgent: String? = null

    // v145: persistência cf_clearance — após validar o Turnstile uma vez, o cookie é salvo em
    // SharedPreferences com TTL 12h e restaurado no cold start, evitando novo diálogo.
    private const val PREF_NAME = "redecanais_af_cf"
    private const val KEY_CF_CLEARANCE = "cf_clearance"
    private const val KEY_CF_BM = "cf_bm"
    private const val KEY_RCIPE = "rcip"
    private const val KEY_RCSESS = "rcsess"
    private const val KEY_SAVED_AT = "saved_at"
    private const val CLEARANCE_TTL_MS = 12 * 60 * 60 * 1000L
    private const val RCSESS_TTL_MS = 25 * 60 * 1000L
    @Volatile private var persistenceRestoreDone = false

    private fun prefs() = runCatching {
        CommonActivity.activity?.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
    }.getOrNull()

    fun extractClearance(cookieHeader: String?): String? {
        if (cookieHeader.isNullOrBlank()) return null
        return Regex("""\bcf_clearance=([^;\s]+)""").findAll(cookieHeader)
            .map { it.groupValues[1].trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() && it.length > 20 && it != "deleted" && it != "\"\"" }
            .lastOrNull()
    }

    fun hasValidClearance(cookieHeader: String?): Boolean {
        return extractClearance(cookieHeader) != null
    }

    fun sanitizeCookies(raw: String): String {
        if (raw.isBlank()) return ""
        val map = LinkedHashMap<String, String>()
        raw.split(";").forEach { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (name.isNotBlank() && value.isNotBlank() && value != "\"\"" && value != "deleted") {
                    map[name] = value
                }
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun saveClearanceFromCookieManager(url: String) {
        try {
            val raw = CookieManager.getInstance().getCookie(url) ?: return
            val clearance = extractClearance(raw)
            val cfBm = Regex("""\b__cf_bm=([^;\s]+)""").findAll(raw).lastOrNull()?.groupValues?.get(1)?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "deleted" }
            val rcip = Regex("""\bRCIP=([^;\s]+)""").findAll(raw).lastOrNull()?.groupValues?.get(1)?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "deleted" }
            val rcsess = Regex("""\bRCSESS=([^;\s]+)""").findAll(raw).lastOrNull()?.groupValues?.get(1)?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "deleted" }
            if (clearance.isNullOrBlank() && rcip.isNullOrBlank() && rcsess.isNullOrBlank()) return
            val now = System.currentTimeMillis()
            val ed = prefs()?.edit()
            if (!clearance.isNullOrBlank()) ed?.putString(KEY_CF_CLEARANCE, clearance)
            if (!cfBm.isNullOrBlank()) ed?.putString(KEY_CF_BM, cfBm)
            if (!rcip.isNullOrBlank()) ed?.putString(KEY_RCIPE, rcip)
            if (!rcsess.isNullOrBlank()) ed?.putString(KEY_RCSESS, rcsess)
            if (!clearance.isNullOrBlank() || !rcip.isNullOrBlank() || !rcsess.isNullOrBlank()) {
                ed?.putLong(KEY_SAVED_AT, now)
            }
            ed?.apply()
            Log.i(TAG, "[CF_PERSIST] save clearance=${!clearance.isNullOrBlank()} cf_bm=${cfBm != null} rcip=${rcip != null} rcsess=${rcsess != null} age=0ms")
        } catch (e: Throwable) {
            Log.w(TAG, "[CF_PERSIST] falha ao salvar clearance: ${e.message}")
        }
    }

    fun restoreClearanceIfValid(mainUrl: String): Boolean {
        val canonicalUrl = if (mainUrl.endsWith("/")) mainUrl else "$mainUrl/"
        if (persistenceRestoreDone) {
            val existing = runCatching { CookieManager.getInstance().getCookie(canonicalUrl) ?: "" }.getOrNull() ?: ""
            if (hasValidClearance(existing)) return true
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
            val clearance = p.getString(KEY_CF_CLEARANCE, null)?.takeIf { it.length > 20 && it != "deleted" }
            val cfBm = p.getString(KEY_CF_BM, null)?.takeIf { it.isNotBlank() && it != "deleted" }
            val rcip = p.getString(KEY_RCIPE, null)?.takeIf { it.isNotBlank() && it != "deleted" }
            val rcsess = p.getString(KEY_RCSESS, null)?.takeIf { it.isNotBlank() && it != "deleted" }
            val hasRcsessValid = !rcsess.isNullOrBlank() && age < RCSESS_TTL_MS
            if (clearance.isNullOrBlank() && rcip.isNullOrBlank() && rcsess.isNullOrBlank()) return false
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)

            if (!clearance.isNullOrBlank()) cm.setCookie("https://redecanais.af/", "cf_clearance=$clearance; Path=/; Domain=.redecanais.af; Secure; SameSite=None")
            if (!cfBm.isNullOrBlank()) cm.setCookie("https://redecanais.af/", "__cf_bm=$cfBm; Path=/; Domain=.redecanais.af; Secure; SameSite=None")
            if (!rcip.isNullOrBlank() && hasRcsessValid) cm.setCookie("https://redecanais.af/", "RCIP=$rcip; Path=/; Domain=.redecanais.af; Secure; HttpOnly; SameSite=None")
            if (!rcsess.isNullOrBlank() && hasRcsessValid) cm.setCookie("https://redecanais.af/", "RCSESS=$rcsess; Path=/; Domain=.redecanais.af; Secure; HttpOnly; SameSite=None")
            cm.setCookie("https://redecanais.af/", "adsCompleted=1; Path=/; Domain=.redecanais.af")
            cm.setCookie("https://redecanais.af/", "modalVisited=true; Path=/; Domain=.redecanais.af")
            cm.setCookie("https://redecanais.af/", "pm_elastic_player=normal; Path=/; Domain=.redecanais.af")
            cm.flush()
            persistenceRestoreDone = true
            Log.i(TAG, "[CF_PERSIST] cf_clearance restaurado age=${age / 1000}s clr=${!clearance.isNullOrBlank()} cf_bm=${cfBm != null} rcip=${rcip != null} rcsess=${hasRcsessValid} (rawAge=${age/1000}s)")
            return !clearance.isNullOrBlank()
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
        if (rawUserAgent.isBlank()) return rawUserAgent
        return rawUserAgent
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
            .trim()
            .replace(Regex("\\s+"), " ")
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
            Log.i(TAG, "[CF] User-Agent WebView normalizado para perfil Chrome Mobile: $resolved")
        }
        Log.i(TAG, "[CF] User-Agent compartilhado HTTP/WebView: $resolved")
        return resolved
    }

    fun invalidateClearance(url: String) {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setCookie(
                url,
                "cf_clearance=deleted; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/; Domain=.redecanais.af; Secure; SameSite=None"
            )
            cookieManager.setCookie(
                url,
                "__cf_bm=deleted; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/; Domain=.redecanais.af; Secure; SameSite=None"
            )
            cookieManager.flush()
            Log.w(TAG, "[CF] cf_clearance e __cf_bm invalidados para $url")
        } catch (e: Throwable) {
            Log.w(TAG, "[CF] Falha ao invalidar cf_clearance: ${e.message}")
        }
        clearPersistedClearance()
        capturedHtmlByUrl.remove(url)
    }

    // v130: cache de HTML capturado por URL dentro da MESMA sessão TLS do diálogo. O Turnstile
    // managed resolve UMA vez no WebView do diálogo; navegar para as outras URLs do catálogo no
    // MESMO WebView carrega SEM re-challenge (~2-5s por página, vs ~70s por diálogo). Os REQs
    // seguintes leem o cache e não reabrem diálogo — fica dentro do deadline de 120s do framework.
    private val capturedHtmlByUrl = ConcurrentHashMap<String, String>()
    @Volatile
    private var catalogUrls: List<String> = emptyList()

    // v146: cache de disco (12h) — HtmlGate.fetch retorna em <500ms mesmo após cold start com 403.
    // O HTML capturado do WebView é serializado em filesDir/redecanais_af_html_cache.json com ts por URL.
    // TTL 12h acompanha janela útil do cf_clearance e evita expiração prematura do catálogo em testes longos.
    private const val DISK_HTML_FILE = "redecanais_af_html_cache.json"
    private const val DISK_CACHE_TTL_MS = 12 * 60 * 60 * 1000L
    @Volatile private var diskCacheRestored = false
    private val diskHtmlTsByUrl = ConcurrentHashMap<String, Long>()

    @Volatile private var appContext: android.content.Context? = null
    fun setAppContext(ctx: android.content.Context) { appContext = ctx.applicationContext }

    private fun diskCacheFile(): File? {
        val ctx = appContext ?: CommonActivity.activity ?: CommonActivity.activity?.applicationContext ?: return null
        return try { File(ctx.filesDir, DISK_HTML_FILE) } catch (_: Throwable) { null }
    }

    fun cleanHtmlForCache(html: String): String = html

    // v225-stealth: headers indetectáveis nivel browser real (Sec-CH-UA, Sec-Fetch-*, Accept com q-values)
    internal fun stealthHeaders(referer: String): MutableMap<String, String> {
        val ua = lastUserAgent ?: WebViewResolver.webViewUserAgent ?: DEFAULT_USER_AGENT
        // deriva Sec-CH-UA do Chrome 125/133
        val chromeMajor = Regex("""Chrome/(\d+)""").find(ua)?.groupValues?.getOrNull(1) ?: "125"
        val isMobile = ua.contains("Mobile", true)
        return mutableMapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "Referer" to referer,
            "Sec-Ch-Ua" to "\"Chromium\";v=\"$chromeMajor\", \"Google Chrome\";v=\"$chromeMajor\", \"Not-A.Brand\";v=\"99\"",
            "Sec-Ch-Ua-Mobile" to if (isMobile) "?1" else "?0",
            "Sec-Ch-Ua-Platform" to "\"Android\"",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1",
            "Cache-Control" to "max-age=0",
            "Priority" to "u=0, i"
        )
    }

    private val pendingHtmlFetches = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    class HtmlBridge {
        @android.webkit.JavascriptInterface
        fun postHtml(url: String, html: String) {
            val pending = pendingHtmlFetches.remove(url)
            if (pending != null) {
                val valid = if (html.isNotBlank() && html.length > 300 && !isChallengeContent(html)) {
                    val clean = cleanHtmlForCache(html)
                    capturedHtmlByUrl[url] = clean
                    clean
                } else null
                pending.complete(valid)
            }
        }
    }

    suspend fun tryFastWebViewFetch(url: String, timeoutMs: Long = 6000L): String? {
        val activity = CommonActivity.activity ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null

        val deferred = CompletableDeferred<String?>()
        pendingHtmlFetches[url] = deferred

        withContext(Dispatchers.Main) {
            try {
                LocalImageProxy.ensureHelperWebView(activity)
                val wv = LocalImageProxy.helperWebView
                if (wv == null) {
                    deferred.complete(null)
                    pendingHtmlFetches.remove(url)
                    return@withContext
                }

                val js = """(async () => {
                    try {
                        const targetUrl = ${org.json.JSONObject.quote(url)};
                        const r = await fetch(targetUrl, { credentials: 'include' });
                        if (r.ok) {
                            const t = await r.text();
                            const isChal = t.includes('challenge-platform') || 
                                           t.includes('Just a moment') || 
                                           t.includes('Ray ID:') || 
                                           t.includes('id="challenge-form"') ||
                                           t.includes('Attention Required');
                            const hasContent = t.includes('pm-video') || 
                                               t.includes('pm-li-video') || 
                                               t.includes('entry-title') || 
                                               t.includes('server.php') || 
                                               t.includes('rcPreloadPlayer') || 
                                               t.includes('player') || 
                                               t.includes('lista-filmes') ||
                                               t.includes('iframe');
                            if (t.length > 500 && !isChal && hasContent) {
                                if (window.HtmlBridge) {
                                    window.HtmlBridge.postHtml(targetUrl, t);
                                    return;
                                }
                            }
                        }
                    } catch(e) {}
                    if (window.HtmlBridge) {
                        window.HtmlBridge.postHtml(${org.json.JSONObject.quote(url)}, '');
                    }
                })();""".trimIndent()
                wv.evaluateJavascript(js, null)
            } catch (e: Throwable) {
                deferred.complete(null)
                pendingHtmlFetches.remove(url)
            }
        }

        return withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }
    }

    suspend fun tryFastHttpGet(url: String, cookies: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val headers = stealthHeaders(if (url.contains("redecanais.af")) "https://redecanais.af/" else "https://redecanais.af/")
                val cleanCookie = sanitizeCookies(cookies)
                if (cleanCookie.isNotBlank()) headers["Cookie"] = cleanCookie
                val client = app.baseClient.newBuilder()
                    .retryOnConnectionFailure(true)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                    .build()
                val t0 = SystemClock.elapsedRealtime()
                val req = okhttp3.Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                val dt = SystemClock.elapsedRealtime() - t0
                // v229b: player pages (whitelist) contam como OK — tryFastHttpGet é o ÚNICO
                // fetch fora do mutex e o server.php sempre retorna 402+challenge-stub aqui
                // mesmo com clearance (o HTML real só vem do WebView).
                val bodyIsPlayer = body.isNotBlank() && isPlayerPage(body)
                if (resp.code in 200..299 && body.isNotBlank() && (!isChallengeContent(body) || bodyIsPlayer)) {
                    if (bodyIsPlayer) Log.i(TAG, "[FAST_GET_OK] player page url=$url dt=${dt}ms len=${body.length}")
                    else Log.d(TAG, "[FAST_GET_OK] url=$url dt=${dt}ms len=${body.length}")
                    cleanHtmlForCache(body)
                } else {
                    Log.d(TAG, "[FAST_GET_MISS] url=$url code=${resp.code} dt=${dt}ms chal=${isChallengeContent(body)} len=${body.length}")
                    null
                }
            } catch (e: Throwable) {
                Log.d(TAG, "[FAST_GET_ERR] url=$url err=${e.message}")
                null
            }
        }
    }

    private val persistLock = Any()

    fun persistCapturedHtmlToDisk() {
        Thread {
            synchronized(persistLock) {
                try {
                    val f = diskCacheFile() ?: return@synchronized
                    val now = System.currentTimeMillis()
                    val catalogSet = catalogUrls.toSet()

                    // Merge com o que já está em disco — evita regressão para 1 chave
                    // quando o primeiro WebView salva antes dos outros 5 terminarem.
                    val merged = org.json.JSONObject()
                    var existingCount = 0
                    if (f.exists() && f.length() in 1 until 2_000_000) {
                        try {
                            val raw = f.readText()
                            if (raw.isNotBlank()) {
                                val existing = org.json.JSONObject(raw)
                                val it = existing.keys()
                                while (it.hasNext()) {
                                    val k = it.next()
                                    val e = existing.optJSONObject(k) ?: continue
                                    // só mantém catálogo; descarta episódios legados
                                    if (catalogSet.isNotEmpty() && k !in catalogSet) continue
                                    val h = e.optString("html", "")
                                    if (h.isBlank() || h.length > 600_000 || isChallengeContent(h)) continue
                                    merged.put(k, e)
                                    // hidrata RAM se ainda não tem (caso app reiniciei sem restore)
                                    if (!capturedHtmlByUrl.containsKey(k)) {
                                        capturedHtmlByUrl[k] = h
                                        diskHtmlTsByUrl[k] = e.optLong("ts", now)
                                    }
                                    existingCount++
                                }
                            }
                        } catch (_: Throwable) {}
                    }

                    // Sobrescreve/atualiza com o conteúdo fresco da RAM (captured é autoritativo)
                    val toPersist = if (catalogSet.isNotEmpty()) {
                        capturedHtmlByUrl.entries.filter { it.key in catalogSet }
                    } else {
                        capturedHtmlByUrl.entries.filter { it.value.length < 650_000 }
                    }
                    var added = 0
                    for ((url, html) in toPersist) {
                        if (html.isBlank()) continue
                        val cleaned = cleanHtmlForCache(html)
                        if (cleaned.isBlank() || cleaned.length > 600_000 || isChallengeContent(cleaned)) continue
                        val ts = diskHtmlTsByUrl[url] ?: now
                        val entry = org.json.JSONObject()
                        entry.put("ts", ts)
                        entry.put("html", cleaned)
                        merged.put(url, entry)
                        added++
                    }

                    // Cap: mantém só os 8 mais recentes por ts quando extrapola (6 catálogo + folga)
                    if (merged.length() > 8) {
                        val entries = mutableListOf<Pair<String, Long>>()
                        val it2 = merged.keys()
                        while (it2.hasNext()) {
                            val k = it2.next()
                            entries.add(k to merged.optJSONObject(k)?.optLong("ts", 0L)!!)
                        }
                        entries.sortBy { it.second }
                        val toRemove = entries.take(merged.length() - 8).map { it.first }
                        for (k in toRemove) merged.remove(k)
                    }

                    if (merged.length() == 0) {
                        Log.w(TAG, "[CF_DISK] nada para persistir (captured=${capturedHtmlByUrl.size} catalog=${catalogUrls.size} existing=$existingCount)")
                        return@synchronized
                    }
                    val tmp = File(f.parent, "${f.name}.tmp")
                    val serialized = merged.toString()
                    tmp.writeText(serialized)
                    if (f.exists()) f.delete()
                    tmp.renameTo(f)
                    Log.i(TAG, "[CF_DISK] HTML cache salvo em disco: ${merged.length()} URLs (fresh=$added existing=$existingCount) file=${f.absolutePath} len=${serialized.length}")
                } catch (e: Throwable) {
                    Log.w(TAG, "[CF_DISK] falha ao salvar cache em disco: ${e.message}")
                }
            }
        }.start()
    }

    fun restoreDiskCacheIfNeeded(): Boolean {
        if (diskCacheRestored) return capturedHtmlByUrl.isNotEmpty()
        diskCacheRestored = true
        try {
            val f = diskCacheFile() ?: return false
            if (!f.exists() || f.length() == 0L) return false
            // evita parse de cache gigante de v219 (4MB+ com páginas de episódios) bloqueando o boot
            if (f.length() > 2_000_000L) {
                Log.w(TAG, "[CF_DISK] cache muito grande (${f.length()} bytes) — limpando para priorizar catálogo")
                runCatching { f.delete() }
                return false
            }
            val raw = f.readText()
            if (raw.isBlank()) return false
            val obj = org.json.JSONObject(raw)
            val now = System.currentTimeMillis()
            var restored = 0
            val it = obj.keys()
            val allowedUrls = catalogUrls.toSet()
            while (it.hasNext()) {
                val url = it.next()
                // pós-v219 só mantemos URLs do catálogo (6) — descarta episódios que inchavam o JSON
                if (allowedUrls.isNotEmpty() && url !in allowedUrls) continue
                val entry = obj.optJSONObject(url) ?: continue
                val ts = entry.optLong("ts", 0L)
                if (ts == 0L || now - ts > DISK_CACHE_TTL_MS) continue
                val html = entry.optString("html", "")
                if (html.isBlank() || html.length > 600_000 || isChallengeContent(html)) continue
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

    // v229: retorna o HTML do server.php capturado na RAM e persiste em arquivo
    // para inspeção (o server.php de ~1MB nunca vai ao cache de disco por design —
    // cap 600KB + só catálogo).
    // v231: despejo verboso uma única vez por processo — o server.php interessa
    // ao diagnóstico do player (quais forms/scripts o recap exige).
    @Volatile var verboseDumpDone = false

    // v231: espelho do DOM do server.php vindo do WebView do proxy (o solver
    // nunca captura server.php — só o detalhe — então o SERVERPHP_HTML tinha
    // dumped=null). Persiste em arquivo para inspeção direta.
    // v232: arquivos separados por sufixo (#body/#inline/head) — antes todos
    // escreviam no mesmo arquivo e se sobrescreviam (perdemos o inline full).
    fun mirrorServerPhpHtml(url: String, html: String) {
        try {
            if (html.length < 5000 && !url.contains("#")) return
            val base = url.substringBefore("#")
            capturedHtmlByUrl[base] = html
            val ctx = appContext ?: CommonActivity.activity ?: return
            val suffix = when {
                url.endsWith("#body") -> "_body"
                url.endsWith("#inline") -> "_inline"
                else -> "_head"
            }
            val f = java.io.File(ctx.filesDir, "redecanais_af_dump_serverphp$suffix.html")
            f.writeText(html)
            android.util.Log.i(TAG, "[SERVERPHP_MIRROR] url=$url len=${html.length} file=${f.absolutePath}")
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "[SERVERPHP_MIRROR] falhou: ${e.message}")
        }
    }

    fun dumpCapturedHtml(url: String, tag: String): String? {
        return try {
            val html = capturedHtmlByUrl[url] ?: return null
            val ctx = appContext ?: CommonActivity.activity ?: return html
            val safe = tag.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
            val f = java.io.File(ctx.filesDir, "redecanais_af_dump_${safe}.html")
            f.writeText(html)
            android.util.Log.i(TAG, "[SERVERPHP_HTML] tag=$tag len=${html.length} file=${f.absolutePath}")
            html
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "[SERVERPHP_HTML] falhou tag=$tag: ${e.message}")
            capturedHtmlByUrl[url]
        }
    }

    fun capturedCount(): Int = capturedHtmlByUrl.size

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

    // v237: classificador de challenge. O modo MANAGED automático (sem checkbox,
    // só "Performing security verification") NÃO responde a toques — tocar ~50x
    // só queima o IP e estoura o deadline do framework. Distingue:
    //  - "checkbox": widget interativo (iframe/button/checkbox presente) → toques valem
    //  - "managed": verificação silenciosa → NÃO tocar, só aguardar com timeout curto
    //  - "banned": Error 1006/ban → fail-fast imediato (abrir WebView só piora)
    //  - "ready": conteúdo real (cards/player) → capturar
    internal const val CHALLENGE_CLASSIFY_JS = """
        (function() {
            try {
                var html = document.documentElement ? document.documentElement.outerHTML : '';
                if (/Error 1006|banned your IP|Access denied/i.test(html)) return 'banned';
                var cards = document.querySelectorAll('#pm-grid > li, li.col-xs-6, li.col-sm-4, li.col-md-3, li.col-lg-3, li.pm-li-video, article.pm-video-item, .pm-video-thumb, .pm-category-browse li, .entry-item, li.video-item, div.pm-li-video').length;
                var hasPlayer = (document.querySelector('.entry-title, #video, iframe[src*="server"], iframe[src*="play"], .player-wrapper, #pm-video-description, .pm-video-description, #pm-video-watch-wrap, #player, .captcha_button') || typeof window.rcPreloadPlayer === 'function' || location.pathname.indexOf('server.php') !== -1 || location.pathname.indexOf('play.php') !== -1 || location.pathname.indexOf('watch.php') !== -1) ? 1 : 0;
                if (cards > 0 || hasPlayer > 0) return 'ready';
                var hasWidget = document.querySelector('iframe[src*="challenge-platform"], iframe[src*="challenges.cloudflare.com"], input[type="checkbox"], .cf-turnstile, [class*="turnstile"], button') !== null;
                var title = document.title || '';
                if (/Just a moment|Checking your browser|Um momento|Verificando|security verification|Attention Required/i.test(title + ' ' + html.slice(0, 2000))) {
                    return hasWidget ? 'checkbox' : 'managed';
                }
                return 'unknown|' + (location.href || '').slice(0, 100) + '|' + title.slice(0, 80) + '|' + html.slice(0, 300).replace(/\n/g, ' ');
            } catch(err) { return 'probe_error:' + err.message; }
        })();
    """

    // v144: o Turnstile managed renderiza o widget em iframe CROSS-ORIGIN
    // (challenges.cloudflare.com / challenge-platform). O JS do documento pai NAO pode ler o
    // conteudo do iframe (SecurityError), mas pode ler o RETANGULO dele via getBoundingClientRect.
    // O toque Android real e disparado nas coordenadas do checkbox dentro desse retangulo.
    // Fallback legado: input[id^="cf-chl-widget-"][id$="_response"] no DOM principal.
    internal const val TURNSTILE_TAP_PROBE_JS = """
        (function() {
            try {
                function getRect(type, el) {
                    var r = el.getBoundingClientRect();
                    var w = window.innerWidth || document.documentElement.clientWidth || 720;
                    var h = window.innerHeight || document.documentElement.clientHeight || 1280;
                    return [type, r.left, r.top, r.width, r.height, w, h].join('|');
                }

                var w = window.innerWidth || document.documentElement.clientWidth || 720;
                var h = window.innerHeight || document.documentElement.clientHeight || 1280;

                // 1. Procura DIRETA por botoes interativos ("Verify you are human" / "Verificar se você é humano")
                var btns = document.querySelectorAll('button, input[type="button"], input[type="submit"], [role="button"], a.btn, .btn');
                for (var b = 0; b < btns.length; b++) {
                    var btn = btns[b];
                    var txt = (btn.innerText || btn.value || btn.textContent || '').trim();
                    if (/verify|verificar|human|humano/i.test(txt)) {
                        var br = btn.getBoundingClientRect();
                        if (br.width >= 30 && br.height >= 20) {
                            return getRect('button_rect', btn);
                        }
                    }
                }

                // 2. Procura direta em IFRAMEs do Turnstile
                var iframes = document.querySelectorAll('iframe[src*="cloudflare"], iframe[src*="challenge-platform"], iframe');
                for (var i = 0; i < iframes.length; i++) {
                    var ifr = iframes[i];
                    var ir = ifr.getBoundingClientRect();
                    if (ir.width >= 100 && ir.height >= 30 && ir.top >= 50 && ir.top <= (h - 40)) {
                        return getRect('iframe_rect', ifr);
                    }
                }

                // 3. Procura recursiva em Shadow DOMs
                var all = document.querySelectorAll('*');
                for (var j = 0; j < all.length; j++) {
                    var node = all[j];
                    if (node.shadowRoot) {
                        var shadowBtn = node.shadowRoot.querySelector('button, input[type="button"], [role="button"]');
                        if (shadowBtn) {
                            var sbr = shadowBtn.getBoundingClientRect();
                            if (sbr.width > 0 && sbr.height > 0 && sbr.top >= 50 && sbr.top <= (h - 40)) return getRect('button_rect', shadowBtn);
                        }
                        var shadowIframe = node.shadowRoot.querySelector('iframe');
                        if (shadowIframe) {
                            var sir = shadowIframe.getBoundingClientRect();
                            if (sir.width >= 20 && sir.height >= 20 && sir.top >= 50 && sir.top <= (h - 40)) return getRect('iframe_rect', shadowIframe);
                        }
                        var shadowCb = node.shadowRoot.querySelector('input[type="checkbox"], .ctp-checkbox-label, [class*="checkbox"], [id*="turnstile"]');
                        if (shadowCb) {
                            var scbr = shadowCb.getBoundingClientRect();
                            if (scbr.width > 0 && scbr.height > 0 && scbr.top >= 50 && scbr.top <= (h - 40)) return getRect('checkbox_rect', shadowCb);
                        }
                    }
                }

                // 4. Procura por DIVs / containers do widget Turnstile
                var divs = document.querySelectorAll('.cf-turnstile, [class*="turnstile"], [id*="turnstile"], [id*="cf-chl-widget"], #challenge-stage div');
                for (var k = 0; k < divs.length; k++) {
                    var d = divs[k];
                    var dr = d.getBoundingClientRect();
                    if (dr.width >= 150 && dr.width <= 400 && dr.height >= 40 && dr.height <= 120 && dr.top >= 50 && dr.top <= (h - 40)) {
                        return getRect('iframe_rect', d);
                    }
                }

                // 5. Fallback calibrado para o widget Turnstile na tela mobile (top = 290 CSS px)
                return ['iframe_rect', 16, 290, 328, 65, w, h].join('|');
            } catch(err) {
                return 'probe_error:' + err.message;
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
            // v181: Botao "Verify you are human" do Cloudflare
            "button_rect" -> {
                if (parts.size != 7) return null
                val left = parts[1].toFloatOrNull() ?: return null
                val top = parts[2].toFloatOrNull() ?: return null
                val width = parts[3].toFloatOrNull() ?: return null
                val height = parts[4].toFloatOrNull() ?: return null
                if (width < 20f || height < 10f) return null
                scaleToView(
                    left + width / 2f,
                    top + height / 2f,
                    viewWidth, viewHeight, viewportWidth, viewportHeight
                )
            }
            // v144: retangulo do IFRAME do Turnstile (cross-origin) — o clique e no checkbox
            // que ocupa os primeiros ~65 CSS px do widget padrao (300 x 65).
            // Formato: iframe_rect|left|top|width|height|viewportWidth|viewportHeight
            "iframe_rect" -> {
                val fallbackX = viewWidth * 0.14f
                val fallbackY = viewHeight * 0.50f
                if (parts.size != 7) return fallbackX to fallbackY
                val left = parts[1].toFloatOrNull() ?: return fallbackX to fallbackY
                val top = parts[2].toFloatOrNull() ?: return fallbackX to fallbackY
                val width = parts[3].toFloatOrNull() ?: return fallbackX to fallbackY
                val height = parts[4].toFloatOrNull() ?: return fallbackX to fallbackY
                if (width < 30f || height < 30f) return fallbackX to fallbackY
                // O checkbox fica a ~36 CSS px da borda esquerda do iframe
                val checkboxCenterX = if (width >= 200f) 36f else width / 2f
                val checkboxCenterY = height / 2f
                val scaled = scaleToView(
                    left + checkboxCenterX,
                    top + checkboxCenterY,
                    viewWidth, viewHeight, viewportWidth, viewportHeight
                )
                if (scaled != null && scaled.second in 30f..(viewHeight * 0.95f)) {
                    scaled
                } else {
                    fallbackX to fallbackY
                }
            }
            "checkbox_rect" -> {
                if (parts.size != 7) return null
                val left = parts[1].toFloatOrNull() ?: return null
                val top = parts[2].toFloatOrNull() ?: return null
                val width = parts[3].toFloatOrNull() ?: return null
                val height = parts[4].toFloatOrNull() ?: return null
                scaleToView(
                    left + width / 2f,
                    top + height / 2f,
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

    // v172: Converte CSS px do viewport para px físicos usando proporção uniforme (device pixel ratio).
    private fun scaleToView(
        cssX: Float,
        cssY: Float,
        viewWidth: Int,
        viewHeight: Int,
        viewportWidth: Float,
        viewportHeight: Float
    ): Pair<Float, Float>? {
        val scale = viewWidth.toFloat() / viewportWidth
        val touchX = cssX * scale
        val touchY = cssY * scale
        if (touchX !in 0f..viewWidth.toFloat() || touchY !in 0f..viewHeight.toFloat()) return null
        return touchX to touchY
    }

    internal fun isPendingSearchContent(content: String): Boolean =
        content.contains("final_mapafilmes.txt") && content.contains("search-input") &&
            !content.contains("data-cs-search-ready=\"true\"")

    internal fun isChallengeContent(content: String): Boolean {
        if (content.isBlank()) return false
        if (isPendingSearchContent(content)) return true
        if (isIpBannedContent(content)) return true
        // v227: página "Offline ou Block!" é stale, não é challenge mas também não serve
        if (content.contains("Offline ou Block", ignoreCase = true) ||
            content.contains("RedeCanais - Offline", ignoreCase = true) ||
            content.contains("Webpage not available", ignoreCase = true) ||
            content.contains("ERR_PROXY_CONNECTION_FAILED", ignoreCase = true) ||
            content.contains("net::ERR_", ignoreCase = true) ||
            content.contains("<title>Carregando", ignoreCase = true)) {
            return true
        }
        if (content.contains("pm-video-thumb") ||
            content.contains("pm-li-video") ||
            content.contains("pm-video-title") ||
            content.contains("pm-grid") ||
            content.contains("pm-category-browse") ||
            content.contains("col-xs-6") ||
            content.contains("lista-filmes") ||
            content.contains("listagem") ||
            content.contains("pm-video-watch-wrap") ||
            content.contains("pm-video-description") ||
            content.contains("entry-title")) {
            return false
        }
        // v229: páginas de player (server.php/play.php/embed) não têm cards nem
        // entry-title — sem esta whitelist o requestDoc descartava o server.php
        // resolvido (978KB) como "challenge" e o loadLinks recebia doc vazio
        // (REQ#18 "Falha total" → nenhum link no celular).
        if (content.contains("rcPreloadPlayer") ||
            content.contains("captcha_button") ||
            content.contains("__RC__/proxy") ||
            content.contains("server.php") ||
            content.contains("jwplayer") ||
            content.contains("videojs") ||
            content.contains("<video")) {
            return false
        }
        return content.contains("Just a moment", ignoreCase = true) ||
            content.contains("Um momento", ignoreCase = true) ||
            content.contains("Checking your browser", ignoreCase = true) ||
            content.contains("Verificando", ignoreCase = true) ||
            content.contains("security verification", ignoreCase = true) ||
            content.contains("verifies you are not a bot", ignoreCase = true) ||
            content.contains("Attention Required", ignoreCase = true) ||
            content.contains("challenge-platform", ignoreCase = true) ||
            content.contains("cdn-cgi/content", ignoreCase = true) ||
            content.contains("id=\"challenge-form\"", ignoreCase = true)
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

    // v159: anti-detection JS compartilhado entre Flow A (solveInteractive) e Flow B (solveAndGetHtml).
    // Patches essenciais para esconder sinais de automação/WebView do Cloudflare Turnstile mantendo
    // o perfil de dispositivo móvel consistente e sem corrupção de canvas ou GPU.
    internal const val ANTI_DETECTION_JS = """
        (function() {
            try {
                if (navigator.webdriver) {
                    try {
                        Object.defineProperty(navigator, 'webdriver', {
                            get: function() { return undefined; },
                            configurable: true
                        });
                    } catch(e) {}
                }
                return 'ok';
            } catch(e) { return 'err:' + e.message; }
        })();
    """

    // v128: serializa os diálogos de verificação — o Turnstile managed de redecanais.af SÓ resolve
    // quando o WebView do diálogo tem foco/visibilidade. Com 4 REQs paralelos (uma categoria cada)
    // abrindo 4 diálogos ao mesmo tempo, NENHUM resolve (todos target_page_loaded=false após 60s).
    // Um diálogo por vez + cf_clearance compartilhado no CookieManager resolve o catálogo inteiro.
    private val interactiveMutex = Mutex()

    class HtmlCaptureInterface(private val onHtml: (String, String) -> Unit) {
        @android.webkit.JavascriptInterface
        fun onHtmlCaptured(url: String, html: String) {
            onHtml(url, html)
        }
    }

    // v237: orçamento total adaptativo — 1ª URL do ciclo pode gastar até 60s
    // (managed auto-resolve ou checkbox), mas REQs seguintes no MESMO ciclo
    // usam orçamento curto (20s): o mutex serializa e o framework estoura 180s
    // se 4 categorias gastarem 60s cada. Reseta quando há sucesso.
    @Volatile private var consecutiveSolverFails = 0
    private fun interactiveBudgetMs(): Long =
        if (consecutiveSolverFails == 0) 60000L else 20000L

    suspend fun solve(url: String): String {
        // v237: fail-fast de ban ANTES de qualquer fetch/WebView. Se o CookieManager
        // ou o cache da RAM já mostra 1006/ban para este host, abrir WebView só
        // re-bane o IP e queima 60s. Retorna "" direto (ciclo fecha rápido).
        val preBanHint = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
        if (preBanHint.contains("cf_clearance=deleted") || isIpBannedContent(capturedHtmlByUrl[url].orEmpty())) {
            Log.w(TAG, "[CF] ban pré-detectado (1006) para $url — fail-fast sem WebView")
            return ""
        }
        capturedHtmlByUrl[url]?.takeIf { it.isNotBlank() }?.let { cached ->
            // v229: aceita player pages (rcPreloadPlayer/captcha_button/__RC__) mesmo se o
            // validador antigo as marcasse como challenge — o server.php resolvido era
            // descartado aqui e o REQ#15 falhava ("Falha total") mesmo com HTML na RAM.
            if (!isChallengeContent(cached)) return cached
            if (cached.contains("rcPreloadPlayer") || cached.contains("captcha_button") ||
                cached.contains("__RC__/proxy") || cached.contains("server.php")) {
                Log.i(TAG, "[CF] HTML player reutilizado da RAM url=$url len=${cached.length}")
                return cached
            }
        }

        // Fast-path 1: se já temos cf_clearance no CookieManager, tenta fetch direto no WebView autenticado
        val existingCookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
        if (hasValidClearance(existingCookies)) {
            val fast = tryFastWebViewFetch(url)
            if (!fast.isNullOrBlank() && !isChallengeContent(fast) && (isPlayerPage(fast) || fast.contains("pm-video") || fast.contains("entry-title") || fast.contains("iframe") || fast.contains("pm-li-video"))) {
                Log.i(TAG, "[CF] Fast WebView Fetch teve sucesso para $url (len=${fast.length})")
                capturedHtmlByUrl[url] = fast
                return fast
            }
        }

        val interactiveHtml = solveInteractive(url, timeoutMs = interactiveBudgetMs(), force = false)        // v229b: solveInteractiveLocked agora retorna player pages (whitelist interna) —
        // aceitar aqui também, não só via !isChallengeContent.
        val interactiveIsPlayer = !interactiveHtml.isNullOrBlank() &&
            (interactiveHtml.contains("rcPreloadPlayer") || interactiveHtml.contains("captcha_button") ||
                interactiveHtml.contains("__RC__/proxy") || interactiveHtml.contains("server.php"))
        if (!interactiveHtml.isNullOrBlank() && (!isChallengeContent(interactiveHtml) || interactiveIsPlayer)) {
            consecutiveSolverFails = 0
            if (interactiveIsPlayer) Log.i(TAG, "[CF] HTML player retornado do interactive url=$url len=${interactiveHtml.length}")
            return interactiveHtml
        }
        consecutiveSolverFails++
        Log.w(TAG, "[CF] solve falhou ($consecutiveSolverFails seguidas) url=$url — próximo REQ usa orçamento curto")
        // v229: mesmo fallback player-page aqui — solveInteractive armazena na RAM
        // mas o validador antigo descartava antes de retornar.
        capturedHtmlByUrl[url]?.takeIf { it.isNotBlank() }?.let { cached ->
            if (!isChallengeContent(cached)) return cached
            if (cached.contains("rcPreloadPlayer") || cached.contains("captcha_button") ||
                cached.contains("__RC__/proxy") || cached.contains("server.php")) {
                Log.i(TAG, "[CF] HTML player (pós-interactive) reutilizado da RAM url=$url len=${cached.length}")
                return cached
            }
        }
        return ""
    }

    // v229b: player pages contam como resolvidas em todos os gates de cache.
    private fun isPlayerPage(html: String): Boolean =
        !isPendingSearchContent(html) && (html.contains("rcPreloadPlayer") || html.contains("captcha_button") ||
            html.contains("__RC__/proxy") || html.contains("server.php"))

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solveInteractive(url: String, timeoutMs: Long = 60000L, force: Boolean = false): String? {
        capturedHtmlByUrl[url]?.takeIf { it.isNotBlank() && (!isChallengeContent(it) || isPlayerPage(it)) }?.let {
            Log.i(TAG, "[CF] HTML do cache da sessão (outro REQ capturou) url=$url len=${it.length}")
            return it
        }
        return interactiveMutex.withLock {
            capturedHtmlByUrl[url]?.takeIf { it.isNotBlank() && (!isChallengeContent(it) || isPlayerPage(it)) }?.let {
                Log.i(TAG, "[CF] HTML do cache dentro do lock url=$url len=${it.length}")
                return@withLock it
            }
            // v228: sem 2º fast-retry dentro do lock — solve() já tentou tryFastHttpGet
            // fora do lock; repetir aqui custava ~2s por MISS serializado no mutex.
            val result = solveInteractiveLocked(url, timeoutMs, force)
            // v229b: player page não é challenge — não descarta nem faz fallback.
            val resultIsPlayer = !result.isNullOrBlank() && isPlayerPage(result)
            if (result == null || result.isBlank() || (isChallengeContent(result) && !resultIsPlayer)) {
                capturedHtmlByUrl[url]?.takeIf { it.isNotBlank() && (!isChallengeContent(it) || isPlayerPage(it)) }?.let { return@withLock it }
            }
            result
        }
    }

    // search.php fills #search-input only after both asynchronous indexes finish.
    // An empty .listagem alone cannot distinguish loading from a genuine zero match.
    private const val SEARCH_PENDING_JS = """
        (location.pathname.endsWith('/search.php') &&
         document.querySelector('#search-input') !== null &&
         (document.readyState !== 'complete' ||
          document.querySelector('#search-input').value !==
              (new URLSearchParams(location.search).get('keywords') || '')))
    """

    private val CAPTURE_READY_HTML_JS = """
        (function() {
            if ($SEARCH_PENDING_JS) return null;
            if (document.querySelector('#search-input')) {
                document.documentElement.setAttribute('data-cs-search-ready', 'true');
                console.log('[CF_SEARCH_READY] items=' + (typeof conteudos !== 'undefined' ? conteudos.length : -1) +
                    ' rendered=' + document.querySelectorAll('.listagem > div').length +
                    ' indexes=' + JSON.stringify(performance.getEntriesByType('resource').filter(function(r) {
                        return /final_mapa/.test(r.name);
                    }).map(function(r) { return {url:r.name, status:r.responseStatus, bytes:r.decodedBodySize}; })));
            }
            return document.documentElement ? document.documentElement.outerHTML : null;
        })();
    """

    private fun decodeCapturedHtml(value: String?): String? = runCatching {
        if (value.isNullOrBlank() || value == "null") null
        else {
            try {
                org.json.JSONTokener(value).nextValue() as? String
            } catch (_: Throwable) {
                if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                    value.substring(1, value.length - 1)
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\t", "\t")
                        .replace("\\\\", "\\")
                } else value
            }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solveInteractiveLocked(url: String, timeoutMs: Long, force: Boolean): String? {
        capturedHtmlByUrl.remove(url)
        val initialCookies = CookieManager.getInstance().getCookie(url) ?: ""
        val hasClearanceBefore = hasValidClearance(initialCookies)
        Log.d(TAG, "[CF] clearance_present=$hasClearanceBefore (before interactive)")

        // v228: solve() já fez tryFastHttpGet fora do lock — não repetir aqui
        // (cada repetição serializa ~100-200ms de MISS no mutex global).

        val activity: Activity? = CommonActivity.activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "[CF] Activity não disponível para abrir WebView interativo")
            return null
        }

        Log.i(TAG, "[CF] challenge_started url=$url")
        val targetLoaded = AtomicBoolean(false)
        val htmlCaptureDone = CompletableDeferred<Boolean>()
        var currentUrl = url
        val isPollingActive = AtomicBoolean(true)
        // v141: mantém uma única WebView interativa visível durante o challenge.
        var interactiveWebView: WebView? = null
        var lastTurnstileTapAt = 0L
        var tapJitterIndex = 0
        var pollAttempts = 0
        var postClearanceWaitCount = 0
        var managedPolls = 0
        var isPollScheduled = false
        var isPollRunning = false
        var hasTriggeredPostClearanceLoad = false
        var lastTapType = ""

        fun tryTapTurnstile(view: WebView, reason: String) {
            val now = SystemClock.uptimeMillis()
            val minCooldown = 3200L
            if (!isPollingActive.get() || now - lastTurnstileTapAt < minCooldown) return
            view.evaluateJavascript(TURNSTILE_TAP_PROBE_JS.trimIndent()) { probeResult ->
                if (!isPollingActive.get() || !view.isAttachedToWindow) return@evaluateJavascript
                val point = turnstileTapPoint(probeResult, view.width, view.height)
                if (point == null) {
                    Log.d(TAG, "[CF] toque Turnstile sem alvo | motivo=$reason | probe=$probeResult")
                    return@evaluateJavascript
                }

                val probeType = probeResult?.trim()?.removeSurrounding("\"")?.split('|')?.firstOrNull().orEmpty()
                lastTapType = probeType
                lastTurnstileTapAt = SystemClock.uptimeMillis()
                // Jitter leve (±4px) para naturalidade
                val jitter = ((tapJitterIndex++ % 5) - 2) * 2f
                val tapPoint = (point.first + jitter) to (point.second + jitter)
                val downTime = lastTurnstileTapAt

                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                val screenX = loc[0] + tapPoint.first
                val screenY = loc[1] + tapPoint.second

                Log.i(
                    TAG,
                    "[CF] toque no Turnstile view=(${tapPoint.first.toInt()}, ${tapPoint.second.toInt()}) | motivo=$reason | raw=(${point.first.toInt()}, ${point.second.toInt()}) | probe=$probeResult"
                )

                val properties = arrayOf(
                    MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                )
                val coordsDown = arrayOf(
                    MotionEvent.PointerCoords().apply {
                        x = tapPoint.first
                        y = tapPoint.second
                        pressure = 0.85f
                        size = 0.08f
                    }
                )

                val eventDown = MotionEvent.obtain(
                    downTime,
                    downTime,
                    MotionEvent.ACTION_DOWN,
                    1,
                    properties,
                    coordsDown,
                    0,
                    0,
                    1.0f,
                    1.0f,
                    0,
                    0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN,
                    0
                )
                view.dispatchTouchEvent(eventDown)
                eventDown.recycle()

                view.postDelayed({
                    if (!isPollingActive.get() || !view.isAttachedToWindow) return@postDelayed
                    val moveTime = SystemClock.uptimeMillis()
                    val coordsMove = arrayOf(
                        MotionEvent.PointerCoords().apply {
                            x = tapPoint.first + 0.5f
                            y = tapPoint.second + 0.5f
                            pressure = 0.80f
                            size = 0.08f
                        }
                    )
                    val eventMove = MotionEvent.obtain(
                        downTime,
                        moveTime,
                        MotionEvent.ACTION_MOVE,
                        1,
                        properties,
                        coordsMove,
                        0,
                        0,
                        1.0f,
                        1.0f,
                        0,
                        0,
                        android.view.InputDevice.SOURCE_TOUCHSCREEN,
                        0
                    )
                    view.dispatchTouchEvent(eventMove)
                    eventMove.recycle()
                }, 35L)

                view.postDelayed({
                    if (!isPollingActive.get() || !view.isAttachedToWindow) return@postDelayed
                    val upTime = SystemClock.uptimeMillis()
                    val coordsUp = arrayOf(
                        MotionEvent.PointerCoords().apply {
                            x = tapPoint.first + 0.8f
                            y = tapPoint.second + 0.8f
                            pressure = 0.0f
                            size = 0.08f
                        }
                    )
                    val eventUp = MotionEvent.obtain(
                        downTime,
                        upTime,
                        MotionEvent.ACTION_UP,
                        1,
                        properties,
                        coordsUp,
                        0,
                        0,
                        1.0f,
                        1.0f,
                        0,
                        0,
                        android.view.InputDevice.SOURCE_TOUCHSCREEN,
                        0
                    )
                    view.dispatchTouchEvent(eventUp)
                    eventUp.recycle()
                }, 85L)
            }
        }

        // P0-3: fallback por teclado (técnica `tabs_till_verify` do FlareSolverr).
        // Quando o rect do iframe vem 0x0/calibrado (widget não monta — caso Xvfb),
        // o toque por coordenada não atinge o checkbox; TAB até o foco + Espaço
        // ativa o widget via caminho de acessibilidade em vez de hit-test.
        // Disparado no máximo 1x a cada 3 taps (cooldown herdado do tryTapTurnstile).
        var tabFallbackCounter = 0
        fun tryKeyboardActivate(view: WebView, reason: String) {
            if (!isPollingActive.get() || !view.isAttachedToWindow) return
            tabFallbackCounter++
            if (tabFallbackCounter % 3 != 0) return
            Log.i(TAG, "[CF] fallback teclado TAB+Espaço | motivo=$reason | n=$tabFallbackCounter")
            view.post {
                try {
                    // foco no primeiro elemento focável e TABs até o widget
                    repeat(6) {
                        view.dispatchKeyEvent(
                            android.view.KeyEvent(
                                SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                                android.view.KeyEvent.ACTION_DOWN,
                                android.view.KeyEvent.KEYCODE_TAB, 0
                            )
                        )
                        view.dispatchKeyEvent(
                            android.view.KeyEvent(
                                SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                                android.view.KeyEvent.ACTION_UP,
                                android.view.KeyEvent.KEYCODE_TAB, 0
                            )
                        )
                    }
                    view.postDelayed({
                        if (!isPollingActive.get() || !view.isAttachedToWindow) return@postDelayed
                        view.dispatchKeyEvent(
                            android.view.KeyEvent(
                                SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                                android.view.KeyEvent.ACTION_DOWN,
                                android.view.KeyEvent.KEYCODE_SPACE, 0
                            )
                        )
                        view.dispatchKeyEvent(
                            android.view.KeyEvent(
                                SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                                android.view.KeyEvent.ACTION_UP,
                                android.view.KeyEvent.KEYCODE_SPACE, 0
                            )
                        )
                    }, 400L)
                } catch (e: Throwable) {
                    Log.w(TAG, "[CF] fallback teclado falhou: ${e.message}")
                }
            }
        }

        fun pollAndCapture(cv: WebView?) {
            isPollScheduled = false
            if (!isPollingActive.get() || cv == null) return
            isPollRunning = true
            // v237: classifica o modo do challenge ANTES de decidir tocar. No modo
            // managed (sem widget) os toques são inúteis — só aguardar com limite.
            cv.evaluateJavascript(CHALLENGE_CLASSIFY_JS.trimIndent()) { modeRaw ->
                if (!isPollingActive.get()) return@evaluateJavascript
                val mode = modeRaw?.trim()?.removeSurrounding("\"").orEmpty()
                if (mode.startsWith("unknown")) {
                    Log.d(TAG, "[CF] DEBUG unknown mode: $mode")
                }
                if (mode == "banned") {
                    // v237: fail-fast — IP banido (1006). WebView só piora (re-bane).
                    Log.w(TAG, "[CF] IP banido detectado (1006) — abortando sem tocar")
                    isPollingActive.set(false)
                    htmlCaptureDone.complete(false)
                    return@evaluateJavascript
                }
                if (mode == "managed") {
                    managedPolls++
                    // v237: modo silencioso — sem toques. Limite próprio curto (20 polls
                    // ≈ 10s): se não auto-resolveu, não vai resolver; libera o mutex
                    // para o próximo REQ/ciclo em vez de queimar 60-180s.
                    if (managedPolls == 1) Log.i(TAG, "[CF] challenge managed (sem checkbox) — aguardando auto-resolução, sem toques")
                    if (managedPolls >= 20) {
                        Log.w(TAG, "[CF] managed sem auto-resolução após ~10s — fail-fast")
                        isPollingActive.set(false)
                        htmlCaptureDone.complete(false)
                        return@evaluateJavascript
                    }
                } else if (mode == "ready") {
                    // conteúdo pronto — segue para captura abaixo via poll normal
                }
                cv.evaluateJavascript(
                """(function() {
                    var cards = document.querySelectorAll('#pm-grid > li, li.col-xs-6, li.col-sm-4, li.col-md-3, li.col-lg-3, li.pm-li-video, article.pm-video-item, .pm-video-thumb, .pm-category-browse li, .entry-item, li.video-item, div.pm-li-video').length;
                    var hasPlayer = (document.querySelector('.entry-title, #video, iframe[src*="server"], iframe[src*="play"], .player-wrapper, #pm-video-description, .pm-video-description, #pm-video-watch-wrap, #player, .captcha_button') || typeof window.rcPreloadPlayer === 'function' || location.pathname.indexOf('server.php') !== -1 || location.pathname.indexOf('play.php') !== -1 || location.pathname.indexOf('watch.php') !== -1) ? 1 : 0;
                    var links = document.querySelectorAll('a[href]').length;
                    var title = (document.title || '').replace(/[|\"']/g, ' ');
                    var htmlLen = (document.documentElement ? document.documentElement.outerHTML.length : 0);
                    var hasChallengeForm = (document.querySelector('form#challenge-form, iframe[src*="challenges.cloudflare.com"], iframe[src*="challenge-platform"]') !== null);
                    var isChalTitle = /Just a moment|Checking your browser|Um momento|Verificando|Attention Required|Error code 520|Error code 522|Web server is returning/i.test(title);
                    var isChal = isChalTitle || (hasChallengeForm && cards === 0 && hasPlayer === 0 && links < 5);
                    
                    var isTarget = location.hostname.indexOf('redecanais') !== -1;
                    if (!isTarget) {
                        return '0|0|Offsite|0|1|0';
                    }
                    var isUnpacking = (document.title && document.title.indexOf('Carregando') !== -1);
                    var searchPending = (location.pathname.indexOf('search.php') !== -1 && document.querySelectorAll('.listagem > div, #pm-grid > li, .entry-item, a[href*=".html"]').length === 0);
                    if (!searchPending && !isChal && !isUnpacking && (cards > 0 || hasPlayer > 0 || (links >= 5 && htmlLen >= 1000))) {
                        if (window.HTMLOUT && typeof window.HTMLOUT.onHtmlCaptured === 'function') {
                            if (document.querySelector('#search-input')) document.documentElement.setAttribute('data-cs-search-ready', 'true');
                            window.HTMLOUT.onHtmlCaptured(location.href, document.documentElement ? document.documentElement.outerHTML : '');
                            return cards + '|' + links + '|' + title + '|' + htmlLen + '|' + (isChal ? '1' : '0') + '|' + hasPlayer;
                        }
                    }
                    return cards + '|' + links + '|' + title + '|' + htmlLen + '|' + (isChal ? '1' : '0') + '|' + hasPlayer;
                })();"""
            ) { diagRaw ->
                isPollRunning = false
                if (!isPollingActive.get()) return@evaluateJavascript
                val clean = diagRaw?.trim()?.removeSurrounding("\"") ?: "0|0||0|0|0"
                val parts = clean.split("|")
                val cardCount = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val linkCount = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val title = parts.getOrNull(2) ?: ""
                val htmlLen = parts.getOrNull(3)?.toIntOrNull() ?: 0
                val isChalFlag = parts.getOrNull(4) == "1"
                val hasPlayer = parts.getOrNull(5) == "1"
                val isChallenge = isChalFlag || isChallengeContent(title)
                pollAttempts++
                // v237: classificador já contou managedPolls; mantém log com modo
                Log.d(TAG, "[CF] polling cards=$cardCount links=$linkCount isChallenge=$isChallenge mode=$mode title='$title' htmlLen=$htmlLen (tentativa $pollAttempts) | url=$currentUrl")

                val c1 = CookieManager.getInstance().getCookie(currentUrl) ?: ""
                val c2 = CookieManager.getInstance().getCookie(url) ?: ""
                val c3 = CookieManager.getInstance().getCookie("https://redecanais.af") ?: ""
                val cookies = "$c1; $c2; $c3"
                val hasClearance = hasValidClearance(cookies)
                
                if (hasClearance) {
                    if (!isChallenge && (cardCount > 0 || hasPlayer || (linkCount >= 5 && htmlLen >= 1000))) {
                        // Conteúdo pronto após clearance!
                    } else {
                        postClearanceWaitCount++
                        if (postClearanceWaitCount == 20) {
                            Log.i(TAG, "[CF] cf_clearance obtido mas timeout suave atingido — recarregando $url")
                            cv.loadUrl(url)
                        }
                    }
                }
                if (isChallenge && isPollingActive.get()) {
                    val now = SystemClock.uptimeMillis()
                    val cooldown = 3200L
                    // v237: só toca no modo checkbox (widget real). No managed/unknown
                    // os toques são inúteis e queimam o IP — apenas aguarda.
                    if (mode != "checkbox") {
                        if (pollAttempts % 10 == 0) Log.d(TAG, "[CF] sem widget (mode=$mode) — sem toques, só aguardando")
                    } else if (pollAttempts >= 3 && (now - lastTurnstileTapAt >= cooldown)) {
                        tryTapTurnstile(cv, "poll_$pollAttempts")
                        // P0-3: se o probe só devolve fallback calibrado (widget não
                        // monta — rect 0x0), o toque por coordenada não atinge nada;
                        // tenta também o caminho por teclado.
                        if (lastTapType == "iframe_rect") {
                            tryKeyboardActivate(cv, "poll_${pollAttempts}_calibrated")
                        }
                    }
                }

                // search.php populates results asynchronously; wait until JS is ready
                val isResolved = !isChallenge && (cardCount > 0 || hasPlayer || (linkCount >= 5 && htmlLen >= 2000))
                if (isResolved) {
                    // v233: use JSON-decoded outerHTML to handle escaped search results
                    cv.evaluateJavascript(CAPTURE_READY_HTML_JS) { htmlVal ->
                        if (!isPollingActive.get()) return@evaluateJavascript
                        val decoded = decodeCapturedHtml(htmlVal)
                        if (decoded != null) {
                            lastSolvedHtml = decoded
                            capturedHtmlByUrl[currentUrl] = decoded
                            capturedHtmlByUrl[url] = decoded
                            targetLoaded.set(true)
                            Log.i(TAG, "[CF] HTML alvo capturado (BG): len=${decoded.length} | cards=$cardCount | url=$currentUrl")
                            Log.i(TAG, "[CF] Resolvido! clearance_present=true | target_page_loaded=true")
                            runCatching { persistCapturedHtmlToDisk() }
                            isPollingActive.set(false)
                            htmlCaptureDone.complete(true)
                        } else {
                            Log.d(TAG, "[CF] search still loading, continue polling | url=$currentUrl")
                            if (isPollingActive.get() && !isPollScheduled) {
                                isPollScheduled = true
                                cv.postDelayed({ pollAndCapture(cv) }, 500)
                            }
                        }
                    }
                } else {
                    if (pollAttempts >= 180 || !isPollingActive.get()) {
                        Log.w(TAG, "[CF] polling limite atingido (90s) | isChallenge=$isChallenge")
                        isPollingActive.set(false)
                        htmlCaptureDone.complete(false)
                    } else if (mode == "managed") {
                        // v237: managed já tem limite próprio (20 polls); aqui só reagenda
                        if (isPollingActive.get() && !isPollScheduled) {
                            isPollScheduled = true
                            cv.postDelayed({ pollAndCapture(cv) }, 500)
                        }
                    } else {
                        if (isPollingActive.get() && !isPollScheduled) {
                            isPollScheduled = true
                            cv.postDelayed({ pollAndCapture(cv) }, 350)
                        }
                    }
                }
            } // v237: fecha callback do poll interno
        } // v237: fecha callback do classificador (mode)
        }

        fun triggerPoll(cv: WebView?) {
            if (cv == null || !isPollingActive.get()) return
            if (!isPollScheduled && !isPollRunning) {
                isPollScheduled = true
                cv.postDelayed({ pollAndCapture(cv) }, 200)
            }
        }

        withContext(Dispatchers.Main) {
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                val rootLayout = activity.findViewById<ViewGroup>(android.R.id.content)

                val wv = WebView(activity).apply {
                    // v236-pentest: VISIBLE MATCH_PARENT alpha 0.01 HARDWARE — GONE 1x1
                    // quebra Turnstile managed (cTplV:5): view 1x1 => scale 1/720 =>
                    // touchY fora da view => scaleToView null ("toque sem alvo" 30x).
                    // MATCH_PARENT dá viewport real 720x1280; mutex garante 1 WebView
                    // por vez (vida curta, destruído pós-capture).
                    visibility = android.view.View.VISIBLE
                    alpha = 1.0f
                    setBackgroundColor(0x00000000)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    isLongClickable = true
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        lastUserAgent?.takeIf { it.isNotBlank() }?.let { userAgentString = it }
                        blockNetworkImage = false
                        loadsImagesAutomatically = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        javaScriptCanOpenWindowsAutomatically = true
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        setSupportMultipleWindows(false)
                    }
                    // P0-4 (auditoria UA): o aviso do FlareSolverr vale aqui — o clearance
                    // é amarrado ao UA do emissor. Se lastUserAgent já existe (vindo de
                    // currentUserAgent() ou de solve anterior), RESPEITA; só deriva do
                    // default do device quando ainda não há nenhum. Antes este bloco
                    // sobrescrevia sempre, divergindo do UA que requestDoc/stealthHeaders
                    // usaram na primeira tentativa.
                    val existingUA = lastUserAgent?.takeIf { it.isNotBlank() }
                    val unifiedUA = existingUA ?: challengeUserAgent(
                        android.webkit.WebSettings.getDefaultUserAgent(activity)
                    )
                    lastUserAgent = unifiedUA
                    settings.userAgentString = unifiedUA
                    Log.i(TAG, "[CF] WebView BG User-Agent: $unifiedUA (preexistente=${existingUA != null})")

                    addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface
                        fun onHtmlCaptured(pageUrl: String, html: String) {
                            // v229b: player pages contam como válidas (whitelist interna) —
                            // sem isso o server.php era descartado neste gate também.
                            if (html.isNotBlank() && html.length > 300 &&
                                (!isChallengeContent(html) || isPlayerPage(html))) {
                                val clean = cleanHtmlForCache(html)
                                capturedHtmlByUrl[pageUrl] = clean
                                val normPage = pageUrl.substringBefore("?").trimEnd('/')
                                val normTarget = url.substringBefore("?").trimEnd('/')
                                if (normPage == normTarget) {
                                    capturedHtmlByUrl[url] = clean
                                    lastSolvedHtml = clean
                                    targetLoaded.set(true)
                                    isPollingActive.set(false)
                                    htmlCaptureDone.complete(true)
                                }
                                Log.i(TAG, "[CF_JS_INTERFACE] HTML capturado via fetch assíncrono: len=${clean.length} url=$pageUrl targetMatch=${normPage == normTarget}")
                                runCatching { persistCapturedHtmlToDisk() }
                            }
                        }
                    }, "HTMLOUT")
                    // v229b: o console do server.php não passa pelo filtro cf/turnstile —
                    // loga erros JS (ex "Uncaught (in promise) Error: 7a2f") que mostram
                    // em que estágio o bundle do player trava.
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            val msg = consoleMessage?.message() ?: ""
                            val src = consoleMessage?.sourceId()?.take(120).orEmpty()
                            if (msg.contains("cf", true) || msg.contains("turnstile", true) || msg.contains("challenge", true) ||
                                src.contains("server.php", true) || src.contains("player3", true) ||
                                msg.contains("Uncaught", true) || msg.contains("Error", true)) {
                                Log.i(TAG, "[CF_JS_CONSOLE] src=$src | $msg")
                            }
                            return true
                        }
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            view?.evaluateJavascript(ANTI_DETECTION_JS, null)
                            if (newProgress >= 70) {
                                triggerPoll(view)
                            }
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url?.toString() ?: return false
                            val host = request.url?.host?.lowercase().orEmpty()
                            if (host.contains("cloudflare.com") && !host.contains("challenges.cloudflare.com") && !host.contains("challenge-platform")) {
                                Log.w(TAG, "[CF_BLOCK_NAV] Bloqueando navegação externa do Cloudflare: $target")
                                return true
                            }
                            return super.shouldOverrideUrlLoading(view, request)
                        }

                        override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                            Log.w(TAG, "[CF_SSL] Ignorando erro SSL no proxy móvel: ${error?.primaryError}")
                            handler?.proceed()
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                Log.w(TAG, "[CF_ERR] Erro no main frame: ${error?.errorCode} - ${error?.description}")
                            }
                        }

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

                        override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, startedUrl, favicon)
                            isPollRunning = false
                            view?.evaluateJavascript(ANTI_DETECTION_JS, null)
                            if (startedUrl != null && startedUrl != "about:blank") {
                                currentUrl = startedUrl
                            }
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            isPollRunning = false
                            CookieManager.getInstance().flush()
                            val cookies = CookieManager.getInstance().getCookie(finishedUrl ?: url) ?: ""
                            val hasClearance = hasValidClearance(cookies)
                            val isTargetUrl = finishedUrl?.contains("redecanais.af") == true &&
                                    !finishedUrl.contains("challenge-platform") &&
                                    finishedUrl != "about:blank"

                            if (finishedUrl != null && finishedUrl != "about:blank") {
                                currentUrl = finishedUrl
                            }

                            Log.d(TAG, "[CF] onPageFinished url=$finishedUrl | clearance=$hasClearance | target_url=$isTargetUrl")
                            if (hasClearance && isTargetUrl) {
                                view?.evaluateJavascript("""
                                    (function() {
                                        try {
                                            if (window.HTMLOUT && typeof window.HTMLOUT.onHtmlCaptured === 'function') {
                                                var isUnpacking = (document.title && document.title.indexOf('Carregando') !== -1);
                                                var isSearchPending = (location.pathname.indexOf('search.php') !== -1 && document.querySelectorAll('.listagem > div, #pm-grid > li, .entry-item, a[href*=".html"]').length === 0);
                                                var html = document.documentElement ? document.documentElement.outerHTML : '';
                                                if (html.length > 1000 && !isUnpacking && !isSearchPending && !document.querySelector('form#challenge-form, #cf-turnstile')) {
                                                    window.HTMLOUT.onHtmlCaptured(location.href, html);
                                                }
                                            }
                                        } catch(e) {}
                                    })();
                                """.trimIndent(), null)
                                // v233: mesma barreira de prontidão da busca + decode JSON único
                                view?.evaluateJavascript(CAPTURE_READY_HTML_JS) { htmlVal ->
                                    if (!isPollingActive.get()) return@evaluateJavascript
                                    val decoded = decodeCapturedHtml(htmlVal)
                                    if (decoded != null) {
                                        // v229b: player pages contam como resolvidas (whitelist).
                                        if ((!isChallengeContent(decoded) || isPlayerPage(decoded)) && decoded.length > 300) {
                                            lastSolvedHtml = decoded
                                            capturedHtmlByUrl[finishedUrl ?: currentUrl] = decoded
                                            capturedHtmlByUrl[url] = decoded
                                            val nowTs = System.currentTimeMillis()
                                            diskHtmlTsByUrl[finishedUrl ?: currentUrl] = nowTs
                                            diskHtmlTsByUrl[url] = nowTs
                                            targetLoaded.set(true)
                                            Log.i(TAG, "[CF] HTML capturado no onPageFinished! len=${decoded.length} | url=$finishedUrl")
                                            runCatching { persistCapturedHtmlToDisk() }

                                            // Prefetch catálogo no MESMO WebView (sessão TLS já válida) — preenche as 5 URLs restantes
                                            try {
                                                val catalogForPrefetch = catalogUrls.ifEmpty {
                                                    listOf(
                                                        "https://redecanais.af/browse-filmes-videos-1-date.html",
                                                        "https://redecanais.af/browse-series-videos-1-date.html",
                                                        "https://redecanais.af/browse-animes-videos-1-date.html",
                                                        "https://redecanais.af/browse-desenhos-videos-1-date.html",
                                                        "https://redecanais.af/browse-filmes-videos-1-views.html",
                                                        "https://redecanais.af/topvideos.html"
                                                    )
                                                }
                                                val pending = catalogForPrefetch.filter { it != finishedUrl && it != url && !capturedHtmlByUrl.containsKey(it) }
                                                if (pending.isNotEmpty()) {
                                                    val prefetchJs = buildString {
                                                        append("(function(){var targets=[")
                                                        append(pending.joinToString(",") { "'$it'" })
                                                        append("];targets.forEach(function(u,i){setTimeout(function(){fetch(u,{credentials:'include'}).then(function(r){return r.text();}).then(function(html){if(html&&window.HTMLOUT&&window.HTMLOUT.onHtmlCaptured)window.HTMLOUT.onHtmlCaptured(u,html);}).catch(function(){});},i*400);});})();")
                                                    }
                                                    view?.evaluateJavascript(prefetchJs, null)
                                                    Log.i(TAG, "[CF_PREFETCH] disparado para ${pending.size} URLs restantes")
                                                }
                                            } catch (_: Throwable) {}

                                            isPollingActive.set(false)
                                            htmlCaptureDone.complete(true)
                                        }
                                    }
                                }
                            }
                            triggerPoll(view)
                        }
                    }
                }
                interactiveWebView = wv

                rootLayout.addView(wv, 0)
                Log.i(TAG, "[CF] WebView VISIBLE MATCH_PARENT acoplada ao fundo | url=$url")
                wv.loadUrl(url)
                triggerPoll(wv)

                Log.i(TAG, "[CF] WebView interativa carregando url=$url")
            } catch (e: Throwable) {
                Log.e(TAG, "[CF] Erro ao criar WebView BG: ${e.message}")
            }

        }

        try {
            // v237: watchdog absoluto — mesmo que o poll trave sem completar o
            // deferred, a WebView é destruída no timeout (evita WebViews órfãs
            // acumulando e matando o app por OOM após vários ciclos).
            val budget = timeoutMs.coerceAtMost(90000L)
            Log.i(TAG, "[CF] orçamento interactive=${budget}ms fails=$consecutiveSolverFails url=$url")
            withTimeoutOrNull(budget) { htmlCaptureDone.await() }
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
        val finalClearance = hasValidClearance(finalCookies)
        Log.i(TAG, "[CF] clearance_present=$finalClearance | target_page_loaded=${targetLoaded.get()} (finished)")
        // v145: persiste cf_clearance válido (resolver Turnstile uma vez libera cold starts seguintes)
        if (finalClearance) saveClearanceFromCookieManager(url)
        // v130: retorna o HTML da URL PEDIDA (o WebView pode ter navegado para as URLs extras do
        // catálogo depois de capturar esta — lastSolvedHtml seria o da última navegação).
        // v229: player pages (rcPreloadPlayer/captcha_button/__RC__/server.php) contam como
        // resolvidas — sem isso o server.php capturado (978KB) era descartado e o REQ#15
        // falhava mesmo com target_page_loaded=true.
        val captured = capturedHtmlByUrl[url]
        val capturedIsPlayer = !captured.isNullOrBlank() &&
            (captured.contains("rcPreloadPlayer") || captured.contains("captcha_button") ||
                captured.contains("__RC__/proxy") || captured.contains("server.php"))
        return when {
            !captured.isNullOrBlank() && (!isChallengeContent(captured) || capturedIsPlayer) -> captured
            else -> null
        }
    }

    suspend fun solveAndGetHtml(url: String, timeoutMs: Long = 15000L): String? {
        val captured = capturedHtmlByUrl[url]
        if (!captured.isNullOrBlank() && !isChallengeContent(captured)) {
            return captured
        }
        return solveInteractive(url, timeoutMs = timeoutMs)
    }
}
