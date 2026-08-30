package com.RedeCanaisAF

import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.atomic.AtomicInteger

internal class HtmlGate(
    private val mainUrl: String
) {
    private companion object {
        private const val TAG = "RedeCanaisAF-Trace"
        private val reqCounter = AtomicInteger(0)
    }

    suspend fun fetch(url: String, referer: String = "$mainUrl/"): Document {
        val reqId = reqCounter.incrementAndGet()
        val startRealtime = android.os.SystemClock.elapsedRealtime()

        Log.i(TAG, "[REQ#$reqId][START] url=$url | referer=$referer")
        logCookieState("BEFORE_REQ", url, reqId)

        return try {
            // v145: restaura cf_clearance persistido (12h) — evita novo diálogo após cold start
            CloudflareSolver.restoreClearanceIfValid(mainUrl)
            val freshCookies = CookieManager.getInstance().getCookie(url) ?: ""
            val userAgent = CloudflareSolver.currentUserAgent()

            // v109: O site NÃO emite cf_clearance persistente (liberação amarrada à sessão TLS do
            // navegador). O CloudflareKiller/OkHttp NUNCA passa (JA3 diferente). A via confiável é
            // o WebView: o app.get retorna 403 rápido e o FALLBACK_WV abaixo usa o HTML renderizado
            // pelo CloudflareSolver DIRETO (conteúdo real em ~7s). Capas via data-cs-poster.

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

            // v146: cache em disco (30min) — cold start retorna em <500ms sem WebView
            CloudflareSolver.getDiskCachedHtml(url)?.let { cachedHtml ->
                if (!CloudflareSolver.isChallengeContent(cachedHtml)) {
                    val parsed = Jsoup.parse(cachedHtml, url)
                    if (parsed.select("a[href]").isNotEmpty()) {
                        Log.i(TAG, "[REQ#$reqId][CACHE_FAST_HIT] url=$url | htmlLen=${cachedHtml.length}")
                        return parsed
                    }
                }
            }

            val respResult = runCatching {
                app.get(
                    url,
                    headers = reqHeaders,
                    timeout = 8L
                )
            }
            val resp = respResult.getOrNull()
            val httpCode = resp?.code ?: -1
            val bodyText = resp?.text ?: ""

            val httpRealtime = android.os.SystemClock.elapsedRealtime()
            val snippet = bodyText.replace("\n", " ").take(250)
            Log.i(TAG, "[REQ#$reqId][HTTP_RESULT] code=$httpCode | elapsedMs=${httpRealtime - startRealtime} | bodyLen=${bodyText.length} | snippet=$snippet")
            logCookieState("AFTER_REQ", url, reqId)

            var finalDoc = resp?.document ?: Document(url)
            val initialLinks = finalDoc.select("a[href]").size

            // v147: Error 1006 = IP banido pelo dono do site (firewall Cloudflare). Não é solucionável
            // via WebView — serve do cache de disco ou retorna erro amigável (evita re-ban).
            if (CloudflareSolver.isIpBannedContent(bodyText)) {
                Log.e(TAG, "[REQ#$reqId][IP_BANNED] Error 1006 detectado — IP banido pelo site. Tentando cache de disco.")
                CloudflareSolver.getDiskCachedHtml(url)?.let { cachedHtml ->
                    if (!CloudflareSolver.isChallengeContent(cachedHtml) && !CloudflareSolver.isIpBannedContent(cachedHtml)) {
                        val parsed = Jsoup.parse(cachedHtml, url)
                        if (parsed.select("a[href]").isNotEmpty()) {
                            Log.i(TAG, "[REQ#$reqId][IP_BANNED][CACHE_HIT] servindo cache de disco | len=${cachedHtml.length}")
                            return parsed
                        }
                    }
                }
                Log.e(TAG, "[REQ#$reqId][IP_BANNED] sem cache — retornando Document vazio (evita WebView que re-bane)")
                throw IllegalStateException("IP_BANNED_1006: seu IP foi banido pelo site redecanais.af (Error 1006). Troque de rede/VPN ou aguarde algumas horas.")
            }

            val initialChallenge = resp == null || httpCode != 200 || initialLinks == 0 || CloudflareSolver.isChallengeContent(bodyText)
            if (initialChallenge) {
                Log.w(TAG, "[REQ#$reqId][FALLBACK_WV] HTTP code=$httpCode | links=$initialLinks -> Extraindo DOM renderizado via WebView V8 silencioso")
                // v120: domínios de player externos (redecanaistv.af) precisam de timeout maior —
                // o Turnstile managed resolve sozinho em 30-60s sem interação
                val isForeignPlayer = url.contains("redecanaistv", true) || (
                    (url.contains("server.php", true) || url.contains("player", true) || url.contains("redirect.api", true)) &&
                        !url.contains("redecanais.af", true)
                )
                val wvTimeout = if (isForeignPlayer) 60000L else 20000L
                Log.i(TAG, "[REQ#$reqId][FALLBACK_WV] foreignPlayer=$isForeignPlayer | wvTimeout=$wvTimeout")
                // v128: o CF_HTML invisível SÓ funciona quando o cf_clearance já está válido (extrai em
                // ~7s). Sem cf_clearance ele gasta 40s e falha ("Challenge persistente") — e como o
                // diálogo interativo agora é SERIALIZADO (mutex), esses 40s desperdiçados por REQ
                // estouram o deadline de 120s do framework para os REQs seguintes. Sem clearance, vai
                // direto ao diálogo (o único caminho que resolve o Turnstile managed).
                val hasClearanceNow = (CookieManager.getInstance().getCookie(url) ?: "").contains("cf_clearance")
                var html: String?
                if (initialChallenge) {
                    // v142: um 403 com challenge prova que o clearance atual não foi aceito. As quatro
                    // categorias entram diretamente no solver serializado; a primeira WebView captura
                    // as demais URLs na mesma sessão e as outras requisições recebem o HTML do cache.
                    if (hasClearanceNow) CloudflareSolver.invalidateClearance(url)
                    Log.w(TAG, "[REQ#$reqId][FALLBACK_WV] challenge confirmado -> solver interativo serializado")
                    html = CloudflareSolver.solveInteractive(url, timeoutMs = 45000L, force = true)
                } else if (hasClearanceNow) {
                    html = CloudflareSolver.solveAndGetHtml(url, timeoutMs = wvTimeout)
                } else {
                    Log.w(TAG, "[REQ#$reqId][FALLBACK_WV] sem cf_clearance -> pulando CF_HTML (40s) e indo direto ao diálogo serializado")
                    html = null
                }
                if (html.isNullOrBlank() && !initialChallenge) {
                    // v122: WebView invisível não resolveu o challenge (cf_clearance expirado por IP dinâmico).
                    // Abre o diálogo interativo para o usuário tocar na caixinha do Turnstile uma vez.
                    Log.w(TAG, "[REQ#$reqId][FALLBACK_WV_INTERACTIVE] HTML vazio/inválido -> abrindo diálogo de verificação")
                    // v128: retorna o HTML capturado do MESMO WebView do diálogo (sessão TLS que resolveu
                    // o Turnstile). null = falhou; "" = resolveu mas sem HTML (ex: cf_clearance de outro
                    // REQ no lock) -> solveAndGetHtml agora funciona com o cookie válido.
                    val dialogHtml = CloudflareSolver.solveInteractive(url, timeoutMs = 45000L, force = true)
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
                if (!html.isNullOrBlank() && !CloudflareSolver.isChallengeContent(html) && !CloudflareSolver.isIpBannedContent(html)) {
                    val parsed = Jsoup.parse(html, url)
                    val linkCount = parsed.select("a[href]").size
                    Log.i(TAG, "[REQ#$reqId][FALLBACK_WV_SUCCESS] htmlLen=${html.length} | linksEncontrados=$linkCount")
                    if (linkCount > 0) {
                        finalDoc = parsed
                        // v145: sucesso com HTML do WebView — o clearance do CookieManager deve ser persistido
                        CloudflareSolver.saveClearanceFromCookieManager(url)
                    }
                } else if (!html.isNullOrBlank()) {
                    Log.w(TAG, "[REQ#$reqId][FALLBACK_WV_REJECTED] HTML ainda contém challenge; parser não será alimentado")
                }
            }

            // v145: caminho direto (sem challenge) também persiste clearance se disponível
            if (!initialChallenge) {
                CloudflareSolver.saveClearanceFromCookieManager(url)
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
