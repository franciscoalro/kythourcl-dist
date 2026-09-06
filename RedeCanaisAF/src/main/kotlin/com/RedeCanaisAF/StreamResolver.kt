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
            val html = doc.html()

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
                if (embedUrl.contains("server.php", true) && embedUrl.contains("vid=", true)) {
                    var localProxyUrl = WebViewStreamProxy.captureAndServe(embedUrl)
                    // v125/v126/v127: o iframe pode apontar p/ servidor morto (RCServer27 NXDOMAIN)
                    // ou ineficiente p/ WebView (RCServer01/videos nunca montou em 2 ciclos de 120s
                    // no v125 — click ok mas __RC__/proxy nunca veio). O harness provou que
                    // server=RCFServer2/ondemand monta __RC__/proxy p/ qualquer vid (206).
                    // v126: timeout cortado p/ 45s/captura — 2 tentativas cabem em ~90s dentro do
                    // deadline de 120s do CloudStream e o fallback realmente roda.
                    // v127: força subfolder=ondemand no fallback (series usam videos; só ondemand foi
                    // provado via browser-harness — NAOIDNTFCA -> 206 ftypisom).
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

            // 4. Extração de streams diretos (.m3u8 / .mp4) no próprio HTML da página principal
            if (extractDirectStreamsFromHtml(html, cleanUrl, "Player Direto", callback)) {
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

        // 1. Resolução Direta de Parâmetros de Stream do RedeCanais AF (player3/server.php)
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
                // v119: não considera sucesso antes de probe+callback; evita falso positivo com URL 520/403
                if (emitExtractorLink(
                        streamUrl = candidate,
                        name = "RedeCanais AF ($serverLabel)",
                        referer = url,
                        isM3u8 = candidate.contains(".m3u8", true),
                        callback = callback
                    )
                ) {
                    success = true
                }
            }
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
        // v105: Regex direto solicitado na missão + padrões existentes (jwplayer/file/source/src/hls)
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
        if (!isValidStreamUrl(streamUrl)) {
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
        val isProxy = streamUrl.contains("__RC__/proxy", true) || streamUrl.contains("p12-common-sign", true) || streamUrl.contains("/proxy?src=", true)
        if (isProxy) {
            Log.i(TAG, "[EMIT_LINK_BYPASS_PROBE] proxy __RC__ validado via WebView 206, emitindo direto sem probe OkHttp: $streamUrl")
        } else {
            val probePassed = probeMediaStream(streamUrl, headers)
            if (!probePassed) {
                Log.e(TAG, "[EMIT_LINK_ABORTED] O servidor de mídia rejeitou a requisição do stream: $streamUrl")
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
