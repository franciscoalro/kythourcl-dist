package com.RedeCanaisAF

import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.concurrent.TimeUnit
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

internal class StreamResolver(
    private val mainUrl: String,
    private val fetchDocument: suspend (url: String, referer: String) -> Document
) {
    private companion object {
        private const val TAG = "RedeCanaisAF-Trace"
    }

    suspend fun resolve(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false
        val cleanUrl = data.substringBefore("#")
        val visitedUrls = mutableSetOf<String>()

        Log.i(TAG, "[LOADLINKS_START] data=$cleanUrl")

        try {
            val doc = fetchDocument(cleanUrl, "$mainUrl/")
            // v228: sem doc.html()/dump em produção — 2MB de String duplicada por
            // chamada de loadLinks (LMK matava o app, signal 9). Trace leve via doc.body().
            try {
                val allIframes = doc.select("iframe")
                val iframeCount = allIframes.size
                val serverCount = doc.select("iframe[src*='server.php']").size
                val hasProxy = doc.select("script, a").any {
                    it.html().contains("__RC__/proxy")
                }
                // v238: loga src de TODOS os iframes (não só count) — revela servers
                // alternativos (ex: RCFServer3 vs RCFServer2) sem precisar de dump.
                // v239: sem filtro + dump completo do body em arquivo (watch.php tem
                // 6 iframes mas só 2 apareciam — os outros 4 podem ser players
                // alternativos fora do seletor focado).
                val srcs = allIframes.mapNotNull {
                    val s = it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { null }
                    s?.take(300)
                }.filter { it.isNotBlank() }
                Log.i(TAG, "[VERIFY_STREAM_DETAIL] url=$cleanUrl iframes=$iframeCount server.php=$serverCount hasProxy=$hasProxy")
                srcs.take(10).forEachIndexed { i, s -> Log.i(TAG, "[IFRAME_$i] $s") }
                try {
                    val ctx = com.lagradost.cloudstream3.CommonActivity.activity
                    ctx?.let { c ->
                        java.io.File(c.filesDir, "redecanais_af_last_watch.html")
                            .writeText(doc.body().html().take(600_000))
                        Log.i(TAG, "[WATCH_DUMP] body salvo len=${doc.body().html().length}")
                    }
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}

            // 1. Coleta de todos os Iframes e Embeds do DOM
            // 1. Coleta focada de Iframes legítimos de vídeo (ignora Disqus, Ads, etc.)
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

            // 2. Coleta de botões / abas de servidores alternativos ou abas dublado/legendado
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

            // 3. Resolução de cada candidato a player/iframe
            for ((embedUrl, label) in embedCandidates.distinctBy { it.first }) {
                // v122: fluxo recap -> rcPreloadPlayer -> __RC__/proxy (TLS-bound) -> proxy local.
                // A URL real do vídeo (__RC__/proxy?src=p12-common-sign...) só funciona no TLS do
                // navegador/WebView que emitiu o cf_clearance (VLC/curl/OkHttp direto = 520, provado
                // via browser-harness). O WebView captura o recap e serve a URL local 127.0.0.1 ao
                // ExoPlayer, que consome via proxy.
                // v228: resolve redirect.api?p=<base64> ANTES de checar server.php — o player
                // migrou para redecanaistv.af (server.php -> redirect.api -> player real).
                // Sem isso o WebView carregava uma URL morta (403, domínio fora do ar) e o
                // ExoPlayer nunca recebia fonte ("não encontra a fonte do vídeo").
                var embedResolved = embedUrl
                if (embedResolved.contains("redirect.api", true)) {
                    val pRaw = Regex("""[?&]p=([^&]+)""", RegexOption.IGNORE_CASE)
                        .find(embedResolved)?.groupValues?.getOrNull(1)
                    if (!pRaw.isNullOrBlank()) {
                        val decoded = tryDecodeBase64OrUrl(pRaw)
                        // v248: NÃO normaliza mais o mirror para o domínio principal.
                        // E2E v244-v247: mirror responde 403 no host mas nunca foi
                        // testado no WebView (JA3-bound) — pode ter challenge
                        // próprio válido e player vivo. Tenta o mirror REAL 1º.
                        if (decoded.startsWith("http", true) && decoded != embedResolved) {
                            Log.i(TAG, "[REDIRECT_API_EARLY] $pRaw -> $decoded (mirror real, sem normalizar)")
                            embedResolved = decoded
                        }
                    }
                }
                if (embedResolved.contains("server.php", true) && embedResolved.contains("vid=", true)) {
                    // v241 (Null_Pointer): matriz ENXUTA 2+embed — a matriz 6x do v238/v239
                    // matava o app via LMK (pid 50427 morreu na tentativa 2/6 do Arrow;
                    // cada captureAndServe monta WebView MATCH_PARENT + bundle 181K).
                    // Evidência E2E: 4 vids x 8 variantes = 100% 204+e18b73c9=[]; variar
                    // server/subfolder NÃO resolve (origem morta no backend, não parâmetro).
                    // Tenta: (1) URL canônica do iframe (com gid quando houver) 12s,
                    // (2) embed.php?vid=<id-curto-do-detalhe> 20s, (3) play.php?vid=
                    // 15s, (4) fluxo normal. Total ~47s+overhead < 180s.
                    // v242: embed usa o id CURTO da URL do detalhe (watch.php?vid=
                    // d6a3471d2), NÃO o vid LONGO do iframe (XMEN97T01EP01) — E2E
                    // provou que embed.php?vid=<longo> morre silencioso (25s sem 1
                    // subrequest). play.php nunca foi testado — 3ª tentativa.
                    val variants = buildServerVariants(embedResolved, cleanUrl)
                    Log.i(TAG, "[EMBED_VARIANTS] n=${variants.size} " + variants.joinToString(" | ").take(900))
                    var localProxyUrl: String? = null
                    // v250: WebView ÚNICO até o playing — cria 1x, navega variante a
                    // variante no MESMO jar (challenge válido), sem shutdown entre
                    // tentativas (o shutdown matava o challenge e o legado caía em
                    // Attention Required). Shutdown só no fim (sucesso/timeout).
                    // v251: a primeira variante canônica deve CRIAR o único WebView
                    // pelo captureAndServe completo, que instala WebViewClient,
                    // shouldInterceptRequest, hook JS, recap e video.play/playing.
                    // v250 criava antes um WebView vazio em ensureSingleWebView e o
                    // captureAndServeReuse apenas navegava: não havia clients/hooks,
                    // por isso o mesmo frame que toca no browser-harness ficava em
                    // no-video. captureAndServe NÃO destrói em falha; as variantes
                    // seguintes reutilizam exatamente esse único jar.
                    var attempt = 0
                    try {
                    for (variant in variants) {
                        attempt++
                        // v241: embed.php legado usa captureLegacyEmbed (player HTML5
                        // direto, sem recap) — captureAndServe travaria 45s esperando
                        // .captcha_button que não existe no player antigo.
                        // v248: watch.php?vid=<curto> usa captureAndServe (é página
                        // canônica com iframe server.php, não player legado).
                        val isLegacy = (variant.contains("embed.php", true) || variant.contains("play.php", true)) &&
                            !variant.contains("watch.php", true)
                        // v241b: budget enxuto — o framework cancela loadLinks em ~10s
                        // (provado: Job cancelled 23:52:47). Early-exit 204 aborta a
                        // canônica em ~4s; embed legado roda em paralelo orçamentário.
                        // v242: 12s canônica / 20s embed / 15s play.php.
                        // v248: watch 20s (canônico com recap, reaproveita reuse).
                        // v252: a primeira variante precisa de orçamento extra — esperar
                        // o Service Worker (controller activated) consome até 9s antes
                        // do tap. 12000ms não cabia (9s SW + 3s capture = false).
                        val budgetMs = when {
                            variant.contains("embed.php", true) -> 20000L
                            variant.contains("play.php", true) -> 15000L
                            variant.contains("watch.php", true) -> 20000L
                            attempt == 1 -> 22000L
                            else -> 18000L
                        }
                        Log.i(TAG, "[PROXY_LINK] tentativa $attempt/${variants.size} budget=${budgetMs}ms legacy=$isLegacy url=$variant")
                        localProxyUrl = when {
                            // v251: tentativa 1 cria/configura o ÚNICO WebView com
                            // clients completos. Não usar ensureSingleWebView antes.
                            attempt == 1 -> WebViewStreamProxy.captureAndServe(variant, budgetMs, cleanUrl)
                            isLegacy -> WebViewStreamProxy.captureLegacyOnSingleWebView(variant, budgetMs)
                            else -> WebViewStreamProxy.captureAndServeSingle(variant, budgetMs, cleanUrl)
                        }
                        if (localProxyUrl != null) break
                        Log.i(TAG, "[PROXY_LINK] tentativa $attempt falhou (204/timeout) — próxima variante")
                    }
                    } finally {
                        if (localProxyUrl == null) WebViewStreamProxy.shutdown()
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
                                this.referer = embedResolved
                                this.quality = Qualities.P1080.value
                                this.headers = mapOf(
                                    "User-Agent" to CloudflareSolver.lastUserAgent.orEmpty(),
                                    "Referer" to embedResolved
                                )
                            }
                        )
                        foundAny = true
                        continue
                    }
                    Log.w(TAG, "[PROXY_LINK] proxy local falhou para $embedResolved — tentando fluxo normal")
                }
                if (resolveStreamOrExtractor(embedUrl, label, cleanUrl, subtitleCallback, callback, visitedUrls, depth = 0)) {
                    foundAny = true
                }
            }

            // 4. Extração de streams diretos (.m3u8 / .mp4) no próprio HTML da página
            // principal — v228: usa bodyHtml() (sem <head> de 4MB de ads) em vez do
            // doc.html() completo; o head só tem tracking/facebook/disqus.
            if (extractDirectStreamsFromHtml(doc.body().html(), cleanUrl, "Player Direto", callback)) {
                foundAny = true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[LOADLINKS_ERROR] url=$cleanUrl err=${e.message}", e)
        }

        Log.i(TAG, "[LOADLINKS_DONE] foundAny=$foundAny")
        return foundAny
    }

    private fun isNonVideoUrl(url: String): Boolean {
        val l = url.lowercase()
        return l.contains("chatango.com") || l.contains("disqus.com") ||
            l.contains("facebook.com") || l.contains("twitter.com") ||
            l.contains("googletagmanager") || l.contains("google-analytics") ||
            l.contains("recaptcha") || l.contains("turnstile") ||
            l.contains("doubleclick") || l.contains("adsystem") ||
            l.contains("histats") || l.contains("whos.amung.us") ||
            l.contains("sharethis") || l.contains("addthis") ||
            l.contains("cloudflare.com") || l.contains("challenge-platform")
    }

    // v229b: player pages (server.php resolvido) contam como HTML válido mesmo se o
    // validador antigo de challenge as marcasse (sem cards/entry-title).
    private fun isPlayerHtml(html: String): Boolean =
        html.contains("rcPreloadPlayer") || html.contains("captcha_button") ||
            html.contains("__RC__/proxy") || html.contains("server.php")

    // v241 (Null_Pointer): matriz enxuta (canônica + embed legado). Lab E2E
    // REFUTOU a matriz 6x server/subfolder: 4 vids (AMRTDPDMNIOEMCHMS,
    // UNTLDODEPMTDVNCYNG, cbae1c53, ARROWT01EP01) x 8 variantes = 100%
    // {"2fa806d3":204,"e18b73c9":[]} — variar server/subfolder não ressuscita
    // origem morta no backend, e 6 WebViews MATCH_PARENT estouram o LMK
    // (pid 50427 morreu na tentativa 2/6 do Arrow). Mantém o fix v240 (gid).
    // v242: embed.php?vid=<ID-CURTO-DO-DETALHE> — o id curto de 9 hex vive na
    // URL do detalhe (musicvideo.php?vid=d6a3471d2), NÃO no iframe (o vid do
    // iframe é LONGO: XMEN97T01EP01 — embed.php?vid=<longo> morre silencioso,
    // E2E 23:59 X-Men: 25s sem 1 subrequest). + play.php?vid= (3ª tentativa,
    // nunca testada em lab).
    private fun buildServerVariants(embedUrl: String, detailUrl: String = ""): List<String> {
        val out = linkedSetOf(embedUrl)
        // v248: base = host do próprio embed (mirror redecanaistv.af quando o
        // redirect.api resolve para ele) — variantes no host errado herdariam
        // challenge/origem do domínio errado.
        val base = try {
            val u = java.net.URI(embedUrl)
            if (!u.host.isNullOrBlank()) "${u.scheme ?: "https"}://${u.host}" else mainUrl
        } catch (_: Throwable) { mainUrl }
        if (base != mainUrl) Log.i(TAG, "[MIRROR_BASE] variantes no host do embed: $base")
        val vid = Regex("""[?&]vid=([^&]+)""", RegexOption.IGNORE_CASE)
            .find(embedUrl)?.groupValues?.getOrNull(1).orEmpty()
        if (vid.isBlank()) return out.toList()
        // v248: preserva o gid do iframe canônico também no embed/play legado
        // (nunca testado: gid=0B265... pode ser exigido em qualquer endpoint).
        val gid = Regex("""[?&](gid|token|key|auth)=([^&]+)""", RegexOption.IGNORE_CASE)
            .find(embedUrl)?.let { "&${it.groupValues[1]}=${it.groupValues[2]}" }.orEmpty()
        // id curto: 1º do detalhe (watch/musicvideo.php?vid=<9hex> ou slug
        // terminando em _<9hex>.html — detalhe de filme usa slug!), senão do
        // próprio embed, senão o longo. v246b: slug _a915c0263.html NÃO casava
        // [?&]vid= — o embed recebia CAPTAMRC3LEG (E2E 00:56 prova).
        val shortFromDetail = Regex("""[?&]vid=([0-9a-f]{9})\b""", RegexOption.IGNORE_CASE)
            .find(detailUrl)?.groupValues?.getOrNull(1)
            ?: Regex("""_([0-9a-f]{9})\.html""", RegexOption.IGNORE_CASE)
                .find(detailUrl)?.groupValues?.getOrNull(1)
        val shortFromEmbed = Regex("""[?&]vid=([0-9a-f]{9})\b""", RegexOption.IGNORE_CASE)
            .find(embedUrl)?.groupValues?.getOrNull(1)
        val embedId = shortFromDetail ?: shortFromEmbed ?: vid
        Log.i(TAG, "[EMBED_ID] embedId=$embedId curtoDetalhe=$shortFromDetail curtoEmbed=$shortFromEmbed iframeVid=$vid gid=[$gid] detail=${detailUrl.take(120)}")
        out.add("$base/embed.php?vid=$embedId$gid")
        out.add("$base/play.php?vid=$embedId$gid")
        // v248: watch.php?vid=<curto> — endpoint canônico de série/filme nunca
        // testado no lab (só server.php foi). 4ª tentativa.
        out.add("$base/watch.php?vid=$embedId")
        return out.take(4)
    }

    /**
     * Resolução recursiva de embeds, iframes intermediários, extratores e links diretos.
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
        if (depth > 3 || isNonVideoUrl(url) || !visitedUrls.add(url)) {
            return false
        }

        Log.d(TAG, "[RESOLVE_STREAM][Depth $depth] url=$url | server=$serverLabel")

        // v229: usa o server.php resolvido na RAM (~1MB) em vez de refazer
        // fetch (tryFastHttpGet sempre dá 402+challenge-stub para server.php).
        // Player page conta como válida mesmo se o validador a marcasse.
        if (url.contains("server.php", true)) {
            val dumped = CloudflareSolver.dumpCapturedHtml(url, "serverphp")
            val dumpedOk = !dumped.isNullOrBlank() &&
                (!CloudflareSolver.isChallengeContent(dumped) || isPlayerHtml(dumped))
            if (dumpedOk) {
                Log.i(TAG, "[SERVERPHP_HTML] usando HTML da RAM len=${dumped!!.length}")
                // v231: despejo verboso 1x por processo — forms, botão recap,
                // scripts e iframes do server.php (o que o recap exige p/ montar).
                if (!CloudflareSolver.verboseDumpDone) {
                    CloudflareSolver.verboseDumpDone = true
                    try {
                        val doc = org.jsoup.Jsoup.parse(dumped, url)
                        val forms = doc.select("form").map {
                            "action=" + it.attr("action").take(80) + " method=" + it.attr("method") +
                                " inputs=" + it.select("input").map { i -> (i.attr("name").ifBlank { i.attr("id") }).take(30) + ":" + i.attr("value").take(30) }.joinToString(",").take(300)
                        }
                        Log.i(TAG, "[SERVERPHP_STRUCT] forms=${forms.size} " + forms.take(4).joinToString(" || ").take(900))
                        val btn = doc.select("#submit, .captcha_button").firstOrNull()
                        Log.i(TAG, "[SERVERPHP_STRUCT] btn=" + (btn?.let { it.tagName() + "#" + it.id() + "." + it.className().take(40) + " type=" + it.attr("type") + " txt=" + it.text().take(60) } ?: "AUSENTE"))
                        val scripts = doc.select("script[src]").map { it.attr("src").take(90) }
                        Log.i(TAG, "[SERVERPHP_STRUCT] scripts=${scripts.size} " + scripts.take(10).joinToString(",").take(700))
                        val ifr = doc.select("iframe[src]").map { it.attr("src").take(120) }
                        Log.i(TAG, "[SERVERPHP_STRUCT] iframes=${ifr.size} " + ifr.take(6).joinToString(" | ").take(700))
                    } catch (e: Throwable) {
                        Log.w(TAG, "[SERVERPHP_STRUCT] falhou: ${e.message}")
                    }
                }
                if (extractDirectStreamsFromHtml(dumped, url, serverLabel, callback)) {
                    return true
                }
                val innerIframes = org.jsoup.Jsoup.parse(dumped, url).select("iframe[src], iframe[data-src]")
                for (iframe in innerIframes) {
                    val innerSrc = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                    if (innerSrc.isNotBlank() && !isNonVideoUrl(innerSrc) && !innerSrc.contains("about:blank", true)) {
                        val nestedUrl = fixUrl(innerSrc)
                        if (resolveStreamOrExtractor(nestedUrl, "$serverLabel -> Aninhado", url, subtitleCallback, callback, visitedUrls, depth + 1)) {
                            return true
                        }
                    }
                }
            } else {
                Log.w(TAG, "[SERVERPHP_HTML] sem HTML na RAM para $url (dumped=${dumped?.length ?: "null"})")
            }
        }

        // v120/v228: intercepta redirect.api?p=<base64> — o player migrou para
        // redecanaistv.af (server.php -> bundle.js -> dt.api -> redirect.api?p=<base64>).
        // v228: redecanaistv.af está fora do ar — normaliza para redecanais.af.
        if (url.contains("redirect.api", true)) {
            val pMatch = Regex("""[?&]p=([^&]+)""", RegexOption.IGNORE_CASE).find(url)
            val pRaw = pMatch?.groupValues?.getOrNull(1)
            if (!pRaw.isNullOrBlank()) {
                val decoded = tryDecodeBase64OrUrl(pRaw)
                    .replace("redecanaistv.af", "redecanais.af", ignoreCase = true)
                if (decoded.isNotBlank() && !decoded.equals(url, ignoreCase = true)) {
                    Log.i(TAG, "[REDIRECT_API] redirect.api?p= decodificado: $pRaw -> $decoded")
                    if (resolveStreamOrExtractor(decoded, serverLabel, referer, subtitleCallback, callback, visitedUrls, depth + 1)) {
                        return true
                    }
                    return false
                }
            }
        }

        var success = false

        // 1. Resolução Direta de Parâmetros de Stream do RedeCanais AF (player3/server.php)
        val vid = Regex("""[?&]vid=([^&]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)
        val serverParam = Regex("""[?&]server=([^&]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1) ?: "RCServer"
        val subfolder = Regex("""[?&]subfolder=([^&]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1) ?: "ondemand"

        // v228: subdomínios de mídia (s1/rcfserver2.redecanais.af) NÃO têm DNS
        // (NXDOMAIN confirmado) — emitir esses candidatos só polui o player com
        // links mortos ("não encontra a fonte"). A fonte real vem do server.php via
        // WebViewStreamProxy (proxy local) ou do HTML do player (__RC__/proxy).
        // Mantém apenas log diagnóstico do vid para rastreio.
        if (!vid.isNullOrBlank()) {
            Log.d(TAG, "[SKIP_DEAD_CDN] vid=$vid server=$serverParam subfolder=$subfolder — s1/rcfserver2 sem DNS, aguardando proxy local/HTML")
        }

        // 2. URL com parâmetro codificado (?url=, ?file=, ?src=)
        val decodedParam = extractParamAndDecode(url)
        if (!decodedParam.isNullOrBlank() && decodedParam != url) {
            if (resolveStreamOrExtractor(decodedParam, serverLabel, url, subtitleCallback, callback, visitedUrls, depth + 1)) {
                success = true
            }
        }

        // 3. Extratores padrão do CloudStream
        try {
            if (loadExtractor(url, referer, subtitleCallback, callback)) {
                Log.i(TAG, "[LOAD_EXTRACTOR_SUCCESS] url=$url | server=$serverLabel")
                success = true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[LOAD_EXTRACTOR_FAIL] url=$url err=${e.message}")
        }

        // 4. HTML intermediário (PHP Melody / player3.php / play.php / embed.php)
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

                    if (innerSrc.isNotBlank() && !isNonVideoUrl(innerSrc) && !innerSrc.contains("about:blank", true) && !innerSrc.contains("recaptcha", true)) {
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

        // 0. Fast-path proxy __RC__/proxy + novo punycode xn--/tos-alisg/neoso capturado via browser-harness (206 provado)
        if (fullLower.contains("__rc__/proxy") || fullLower.contains("/proxy?src=") || fullLower.contains("p12-common-sign") ||
            fullLower.contains("tos-alisg") || fullLower.contains("xn--l") || fullLower.contains("neosoro.gq") ||
            fullLower.contains("container=videos") || fullLower.contains("/proxy?container=")) {
            if (decodedLower.contains(".mp4") || decodedLower.contains(".m3u8") || decodedLower.contains(".mkv") || decodedLower.contains(".mpd") || decodedLower.contains(".webm")) {
                return true
            }
            // proxy encapsulado já é stream mesmo sem extensão decodificada no path outer
            if (fullLower.contains("tos-alisg") || fullLower.contains("xn--l")) return true
        }

        // 1. Rejeicao de assets estaticos
        if (clean.endsWith(".js") || clean.endsWith(".css") || clean.endsWith(".html") ||
            clean.endsWith(".htm") || clean.endsWith(".json") || clean.endsWith(".xml") ||
            clean.endsWith(".jpg") || clean.endsWith(".png") || clean.endsWith(".gif") ||
            clean.endsWith(".svg") || clean.endsWith(".webp") || clean.endsWith(".woff") ||
            clean.endsWith(".woff2") || clean.endsWith(".ttf")
        ) {
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
            fullLower.contains("browse-")
        ) {
            return false
        }

        // 3. Whitelist streams (raw + decoded) — inclui novo proxy punycode xn--/tos-alisg (HAR 2026-09-07)
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
            fullLower.contains("/proxy?container=") || decodedLower.contains("/proxy?container=") ||
            fullLower.contains("container=videos") || decodedLower.contains("container=videos") ||
            fullLower.contains("tos-alisg") || decodedLower.contains("tos-alisg") ||
            fullLower.contains("xn--l") || decodedLower.contains("xn--l") ||
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
        // v105: Regex direto solicitado na missão + padrões existentes (jwplayer/file/source/src/hls)
        // + HAR 2026-09-07: https://xn--l---...null-null.shop/tos-alisg-avt-.../proxy?container=videos&...&url=https://neosoro.gq/V/RCFServer2/ondemand/xxx.mp4
        val streamPatterns = listOf(
            Regex("""https?://[^\s"'"'"]+tos-alisg[^\s"'"'"]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\s"'"'"]+xn--l[^\s"'"'"]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\s"'"'"]+/proxy\?container=[^\s"'"'"]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\s"'"'"]+/__RC__/proxy\?src=[^\s"'"'"]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\s"']+\.(?:m3u8|mp4)[^\s"']*""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^\s"'\\]+\.(?:m3u8|mp4)(?:\?[^\s"'\\]*)?)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|source|src|stream|hls|video)\s*[:=]\s*["'](https?://[^\s"'\\]+)["']""", RegexOption.IGNORE_CASE),
            Regex("""\{[^}]*file\s*:\s*["'](https?://[^\s"'\\]+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<source[^>]+src=["'](https?://[^\s"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<video[^>]+src=["'](https?://[^\s"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["'](https?://[^\s"']+)["']""", RegexOption.IGNORE_CASE),
            // v119: src do vídeo capturado pelo WebView (data-cs-video-src) nas páginas de player
            Regex("""data-cs-video-src=["'](https?://[^\s"']+)["']""", RegexOption.IGNORE_CASE),
            // v229: endpoints internos do bundle do player (server.php -> bundle.js ->
            // dt.api/serverforms.api/query -> redirect.api?p=<base64> -> player real).
            // O WebView executa o JS; aqui capturamos as URLs intermediárias do HTML.
            Regex("""["']((?:https?://[^\s"'\\]*?)?/(?:player3/)?(?:dt\.api|serverforms\.api|query\.api|query\.js|getvid\.php|getlink\.php)[^"'\s\\]*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']((?:https?://[^\s"'\\]*?)?/player3/redirect\.api\?p=[^"'\s\\]+)["']""", RegexOption.IGNORE_CASE)
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
        } catch (_: Throwable) {
        }

        if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
            return text
        }

        try {
            val decodedBytes = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
            val decoded = String(decodedBytes, Charsets.UTF_8).trim()
            if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true) ||
                decoded.contains(".m3u8", true) || decoded.contains(".mp4", true)
            ) {
                return decoded
            }
        } catch (_: Throwable) {
        }

        try {
            val decodedBytes = android.util.Base64.decode(text, android.util.Base64.URL_SAFE)
            val decoded = String(decodedBytes, Charsets.UTF_8).trim()
            if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true) ||
                decoded.contains(".m3u8", true) || decoded.contains(".mp4", true)
            ) {
                return decoded
            }
        } catch (_: Throwable) {
        }

        return text
    }

    private suspend fun fetchHtmlSafe(url: String, referer: String): String {
        return try {
            val doc = fetchDocument(url, referer)
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

            val res = app.get(url, headers = probeHeaders, timeout = 6L)
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
                Log.w(TAG, "[MEDIA_PROBE_REJECTED] Resposta não é mídia real: status=$status isHtml=$isHtml")
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
        // v228: nunca emitir redecanaistv.af — domínio fora do ar (timeout).
        // Normaliza para redecanais.af antes de qualquer validação/probe.
        val normalizedUrl = streamUrl.replace("redecanaistv.af", "redecanais.af", ignoreCase = true)
        if (normalizedUrl != streamUrl) {
            Log.i(TAG, "[EMIT_LINK_NORMALIZE] redecanaistv.af -> redecanais.af: $normalizedUrl")
        }
        if (!isValidStreamUrl(normalizedUrl)) {
            Log.w(TAG, "[EMIT_LINK_IGNORED] URL não é um stream de vídeo válido: $streamUrl")
            return false
        }

        val userAgent = CloudflareSolver.lastUserAgent
            ?: WebViewResolver.webViewUserAgent
            ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

        val cookies = try {
            CookieManager.getInstance().getCookie(referer)
                ?: CookieManager.getInstance().getCookie(mainUrl)
                ?: ""
        } catch (_: Throwable) {
            ""
        }

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
        } catch (_: Throwable) {
        }

        // Validação ativa da mídia antes de entregar ao ExoPlayer
        // v118: bypass probe para proxy (206 provado via browser-harness mesma sessao)
        // v228: o probe OkHttp usa TLS/JA3 diferente do WebView — o servidor responde 520
        // mesmo para URLs de mídia válidas (__RC__/proxy é TLS-bound). Como o caminho
        // preferencial (WebViewStreamProxy.captureAndServe) já entregou o link via proxy
        // local, o probe aqui só servia para descartar links bons. Mantém o probe apenas
        // para diagnóstico (log), sem abortar a emissão.
        val isProxy = normalizedUrl.contains("__RC__/proxy", true) || normalizedUrl.contains("p12-common-sign", true) || normalizedUrl.contains("/proxy?src=", true)
        if (isProxy) {
            Log.i(TAG, "[EMIT_LINK_BYPASS_PROBE] proxy __RC__ validado via WebView 206, emitindo direto sem probe OkHttp: $normalizedUrl")
        } else {
            // v228: diagnóstico apenas — emissão não é mais abortada pelo probe.
            val probePassed = probeMediaStream(normalizedUrl, headers)
            if (!probePassed) {
                Log.w(TAG, "[EMIT_LINK_PROBE_FAIL] probe OkHttp falhou mas emitindo mesmo assim (TLS-bound): $normalizedUrl")
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
                url = normalizedUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.headers = headers
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            url.startsWith("http", true) -> url
            else -> "$mainUrl/$url"
        }
    }
}
