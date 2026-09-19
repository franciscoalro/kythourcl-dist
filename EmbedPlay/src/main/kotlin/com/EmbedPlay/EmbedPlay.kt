package com.EmbedPlay

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class EmbedPlay : MainAPI() {
    override var mainUrl = "https://embedplayapi.top"
    override var name = "EmbedPlay"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private val BROWSER_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        private const val ONE_BASE = "https://www.embedplay.one"
    }

    data class SuggestResp(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: SuggestData? = null
    )

    data class SuggestData(
        @JsonProperty("results") val results: String? = null
    )

    data class StreamLinkResp(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: StreamLinkData? = null
    )

    data class StreamLinkData(
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("token") val token: String? = null,
        @JsonProperty("_played") val played: String? = null
    )

    data class OnePlayerResp(
        @JsonProperty("errors") val errors: String? = null,
        @JsonProperty("data") val data: OnePlayerData? = null
    )

    data class OnePlayerData(
        @JsonProperty("video_url") val videoUrl: String? = null,
        @JsonProperty("video_caption_url") val captionUrl: String? = null
    )

    data class OneOptionsResp(
        @JsonProperty("errors") val errors: String? = null,
        @JsonProperty("data") val data: OneOptionsData? = null
    )

    data class OneOptionsData(
        @JsonProperty("options") val options: List<OneOption>? = null
    )

    data class OneOption(
        @JsonProperty("ID") val id: String? = null,
        @JsonProperty("server") val server: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class VsStreamResp(
        @JsonProperty("status_code") val statusCode: String? = null,
        @JsonProperty("data") val data: VsStreamData? = null,
        @JsonProperty("vs") val vs: VsDecryptor? = null
    )

    data class VsStreamData(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("stream_urls") val streamUrls: Any? = null
    )

    data class VsDecryptor(
        @JsonProperty("w") val w: Long? = null,
        @JsonProperty("wasm_url") val wasmUrl: String? = null,
        @JsonProperty("wasm") val wasm: String? = null
    )

    override val mainPage = mainPageOf(
        "$mainUrl/library/movies" to "Filmes",
        "$mainUrl/library/shows" to "Séries",
        "$mainUrl/trending/movies" to "Em Alta",
        "$mainUrl/recent-releases" to "Lançamentos",
        "$mainUrl/imdb-top/movies" to "Top IMDb"
    )

    private fun parseLibraryCard(element: Element, isSeriesSection: Boolean = false): SearchResponse? {
        // v2: library usa link /embed/{tmdb} (data-tmdb só existe no suggest)
        val card = element.selectFirst(".movie-card") ?: element
        val tmdb = card.attr("data-tmdb").takeIf { it.isNotBlank() }
            ?: card.selectFirst("a[href*='/embed/']")?.attr("href")
                ?.let { Regex("""/embed/(\d+)""").find(it)?.groupValues?.getOrNull(1) }
            ?: element.selectFirst("a[href*='/embed/']")?.attr("href")
                ?.let { Regex("""/embed/(\d+)""").find(it)?.groupValues?.getOrNull(1) }
            ?: return null
        val title = card.selectFirst("p.title")?.text()?.trim()?.substringBefore(" - ")?.trim()
            ?: element.selectFirst("p.title")?.text()?.trim()?.substringBefore(" - ")?.trim()
            ?: return null
        if (title.isBlank()) return null
        // v4: cinemaplay.top exige Referer (403 sem) — w300 -> w500 p/ capas nítidas
        val poster = (card.selectFirst(".poster-img") ?: element.selectFirst(".poster-img"))?.attr("data-bg-multi")
            ?.let { Regex("""url\((https?://[^)]+)\)""").find(it)?.groupValues?.getOrNull(1) }
            ?.replace("/w300/", "/w500/")
            ?.let { fixUrlNull(it) }
        val url = "$mainUrl/view/$tmdb"
        return if (isSeriesSection) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val doc = app.get(request.data, headers = BROWSER_HEADERS, timeout = 30).document
            val isSeries = request.data.contains("/shows")
            val items = doc.select(".movie-card").mapNotNull { parseLibraryCard(it, isSeries) }
                .distinctBy { it.url }
            newHomePageResponse(request.name, items, hasNext = false)
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        // v9: busca filmes E séries (era só type=movie — série nunca aparecia)
        val out = mutableListOf<SearchResponse>()
        for (type in listOf("movie", "tv")) {
            try {
                val resp = app.get(
                    "$mainUrl/ajax/get_suggest?title=${URLEncoder.encode(query.trim(), "UTF-8")}&type=$type",
                    headers = BROWSER_HEADERS + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "$mainUrl/"
                    ),
                    timeout = 30
                ).parsedSafe<SuggestResp>()
                val html = resp?.data?.results ?: continue
                val doc = Jsoup.parse(html)
                val isTv = type == "tv"
                doc.select(".movie-card").mapNotNullTo(out) { card ->
                    val tmdb = card.attr("data-tmdb").takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
                    val title = card.selectFirst("p.title")?.text()?.trim() ?: return@mapNotNullTo null
                    if (title.isBlank()) return@mapNotNullTo null
                    val poster = card.selectFirst(".poster-img")?.attr("data-bg-multi")
                        ?.let { Regex("""url\((https?://[^)]+)\)""").find(it)?.groupValues?.getOrNull(1) }
                        ?.replace("/w300/", "/w500/")
                        ?.let { fixUrlNull(it) }
                    if (isTv) {
                        newTvSeriesSearchResponse(title, "$mainUrl/view/$tmdb", TvType.TvSeries) {
                            this.posterUrl = poster
                            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                        }
                    } else {
                        newMovieSearchResponse(title, "$mainUrl/view/$tmdb", TvType.Movie) {
                            this.posterUrl = poster
                            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = BROWSER_HEADERS, timeout = 30).document
        val rawTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.selectFirst("h1.title, h1")?.text()
            ?: doc.selectFirst("title")?.text()
            ?: "EmbedPlay"
        val title = rawTitle
            .replace(Regex("""\s*-\s*\d{4}\s*(-.*)?$"""), "")
            .replace(Regex("""\s*—.*$"""), "")
            .replace(Regex("""\s*-\s*Api de streaming.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrlNull(it) }
        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc.select(".card p").map { it.text().trim() }.firstOrNull { it.length > 30 }
        val year = Regex("""\b(19|20)\d{2}\b""").find(rawTitle)?.value?.toIntOrNull()
        val imdbId = doc.selectFirst(".active-movie-id")?.text()?.trim()
            ?.let { Regex("""(tt\d+)""").find(it)?.groupValues?.getOrNull(1) }.orEmpty()
        val tmdbId = Regex("""/view/(\d+)""").find(url)?.groupValues?.getOrNull(1).orEmpty()

        val isSeriesUrl = url.contains("/shows") || doc.html().contains("/embed/$tmdbId/1/1")
        // v9: payload da série carrega o mapa real de temporadas (contentid por episódio,
        // extraído do view .top) — o loadLinks resolve via getOptions sem re-baixar a página
        var seriesMap = ""
        if (isSeriesUrl && tmdbId.isNotBlank()) {
            try {
                val seasons = mutableListOf<String>()
                val seasonNames = mutableMapOf<Int, String>()
                doc.select("#season-select option").forEach { opt ->
                    val sn = opt.attr("value").toIntOrNull() ?: return@forEach
                    seasonNames[sn] = opt.text().trim().ifBlank { "Temporada $sn" }
                }
                doc.select("select[id^='sea-'][id\$='--episodes']").forEach { sel ->
                    val sn = Regex("""sea-(\d+)--episodes""").find(sel.attr("id"))
                        ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEach
                    sel.select("option").forEach { opt ->
                        val en = opt.attr("value").toIntOrNull() ?: return@forEach
                        val epId = opt.attr("data-id").takeIf { it.isNotBlank() } ?: return@forEach
                        val epName = opt.text().trim().substringAfter(":").trim()
                            .ifBlank { opt.text().trim() }
                        seasons.add("$sn:$en:$epId:$epName")
                    }
                }
                seriesMap = seasons.joinToString(";")
            } catch (_: Exception) {}
        }
        val payload = "tmdb:$tmdbId|imdb:$imdbId|series:$isSeriesUrl|seasons:$seriesMap"

        return if (isSeriesUrl && tmdbId.isNotBlank()) {
            val episodes = seriesMap.split(";").filter { it.isNotBlank() }.mapNotNull { entry ->
                val parts = entry.split(":", limit = 4)
                if (parts.size < 3) return@mapNotNull null
                val sn = parts[0].toIntOrNull() ?: return@mapNotNull null
                val en = parts[1].toIntOrNull() ?: return@mapNotNull null
                val epId = parts[2]
                val epName = parts.getOrNull(3).orEmpty()
                newEpisode("$payload|season:$sn|episode:$en|contentid:$epId") {
                    this.name = if (epName.isNotBlank()) "E$en - $epName" else "Episódio $en"
                    this.season = sn
                    this.episode = en
                }
            }
            val eps = if (episodes.isEmpty()) {
                // fallback: 1 episódio (fluxo antigo por /embed/tmdb/1/1)
                listOf(
                    newEpisode("$payload|season:1|episode:1") {
                        this.name = "T1:E1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            } else episodes
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, payload) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false
        val tmdbId = Regex("""tmdb:(\d+)""").find(data)?.groupValues?.getOrNull(1).orEmpty()
        val season = Regex("""season:(\d+)""").find(data)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val episode = Regex("""episode:(\d+)""").find(data)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val contentId = Regex("""contentid:(\d+)""").find(data)?.groupValues?.getOrNull(1).orEmpty()
        val isSeries = data.contains("series:true")
        if (tmdbId.isBlank()) return false

        return try {
            // v9: série via .one getOptions (contentid do view .top) — cadeia provada no lab:
            // getOptions(contentid) -> options[] ABYS/BYSE/UPN -> getPlayer(ID) -> video_url.
            // É o caminho oficial do site p/ séries; o embed .top de série também funciona
            // como fallback (mesmo fluxo do filme).
            if (isSeries && contentId.isNotBlank()) {
                try {
                    val optResp = app.post(
                        "$ONE_BASE/api",
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "$ONE_BASE/",
                            "X-Requested-With" to "XMLHttpRequest"
                        ),
                        data = mapOf("action" to "getOptions", "contentid" to contentId),
                        timeout = 30
                    ).parsedSafe<OneOptionsResp>()
                    val options = optResp?.data?.options.orEmpty()
                    // UPN+BYSE primeiro (HTTP-puro), Abyss por último (WebView)
                    val ordered = options.sortedBy {
                        when {
                            (it.url.orEmpty().contains("upns.xyz") || it.url.orEmpty().contains("embedplayapiupn")) -> 0
                            (it.server.orEmpty().equals("BYSE", true) || it.url.orEmpty().contains("byse")) -> 1
                            it.url.orEmpty().contains(".m3u8") -> 2
                            else -> 3
                        }
                    }
                    for (opt in ordered) {
                        try {
                            if (resolveOneOption(opt.id.orEmpty(), opt.url.orEmpty(), opt.server.orEmpty(), "$ONE_BASE/", subtitleCallback, callback)) {
                                return true
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
            // v5: VidSrc GATE (mesmo do CineVision v151/v155, que funciona no app).
            // loadExtractor NÃO resolve gate VidSrc (data-api -> cloudorchestranova
            // -> playerUrl -> data.vidsrc.sh cifrado); precisa do fluxo HTTP-puro.
            val imdbId = Regex("""imdb:(tt\d+)""").find(data)?.groupValues?.getOrNull(1).orEmpty()
            if (imdbId.isNotBlank()) {
                try {
                    val gateUrl = if (isSeries)
                        "https://vidsrc.sh/embed/tv/$imdbId/$season-$episode"
                    else
                        "https://vidsrc.sh/embed/movie/$imdbId"
                    if (resolveVidSrcGate(gateUrl, "$ONE_BASE/", callback)) {
                        return true
                    }
                } catch (_: Exception) {}
            }

            // 1. embed .top -> data-movie-id + server data-id reais
            val embedPath = if (isSeries) "/embed/$tmdbId/$season/$episode" else "/embed/$tmdbId"
            val embedHtml = app.get(
                "$mainUrl$embedPath",
                headers = BROWSER_HEADERS + mapOf("Referer" to "$mainUrl/"),
                timeout = 30
            ).text
            val movieCode = Regex("""data-movie-id="([^"]+)"""").find(embedHtml)?.groupValues?.getOrNull(1)
            // v8: servers .top agora são alfa (_default=grupo, nrN6D=server real);
            // pega TODOS os data-id e pula _default (grupo, sempre erro "unknown error")
            val serverIds = Regex("""data-id="([a-zA-Z0-9_]+)"""")
                .findAll(embedHtml).map { it.groupValues[1] }.distinct()
                .filter { it != "_default" }.toList()
            if (movieCode.isNullOrBlank() || serverIds.isEmpty()) {
                // sem server .top mas com imdb: tenta o .one direto pelo padrão de URL
                if (imdbId.isNotBlank()) {
                    try {
                        val oneHtml = app.get(
                            "$ONE_BASE/filme/$imdbId",
                            headers = BROWSER_HEADERS + mapOf("Referer" to "$mainUrl$embedPath"),
                            timeout = 30
                        ).text
                        if (resolveOnePlayers(oneHtml, "$ONE_BASE/filme/$imdbId", subtitleCallback, callback)) {
                            foundAny = true
                        }
                    } catch (_: Exception) {}
                }
                // último recurso: gate VidSrc com TMDB id (vidsrc.sh aceita tmdb)
                if (!foundAny && tmdbId.isNotBlank()) {
                    try {
                        val gateUrl = if (isSeries)
                            "https://vidsrc.sh/embed/tv/$tmdbId/$season-$episode"
                        else
                            "https://vidsrc.sh/embed/movie/$tmdbId"
                        if (resolveVidSrcGate(gateUrl, "$ONE_BASE/", callback)) {
                            foundAny = true
                        }
                    } catch (_: Exception) {}
                }
                return foundAny
            }

            // 2. ajax/get_stream_link -> link embedplay.one (tenta cada server)
            for (serverId in serverIds) {
                try {
                    val linkResp = app.get(
                        "$mainUrl/ajax/get_stream_link?id=$serverId&movie=$movieCode&is_init=false",
                        headers = BROWSER_HEADERS + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to "$mainUrl$embedPath"
                        ),
                        timeout = 30
                    ).parsedSafe<StreamLinkResp>()
                    val oneLink = linkResp?.data?.link?.takeIf { it.isNotBlank() } ?: continue

                    // 2b. página .one: VidSrc direto (data-url) + getPlayer por data-id
                    try {
                        val oneHtml = app.get(
                            oneLink,
                            headers = BROWSER_HEADERS + mapOf("Referer" to "$mainUrl$embedPath"),
                            timeout = 30
                        ).text
                        if (resolveOnePlayers(oneHtml, oneLink, subtitleCallback, callback)) {
                            foundAny = true
                            break
                        }
                    } catch (_: Exception) { continue }
                } catch (_: Exception) { continue }
            }
            foundAny
        } catch (_: Exception) {
            foundAny
        }
    }

        // v9 (log aparelho tt27989067): o gate responde, mas stream_urls vem
        // CIFRADA (ChaCha20 + wasm por janela `w`) — e o WebView no player expira
        // em 25s sem montar o HLS. Decrypt HTTP-puro abaixo (ChaCha20 RFC 8439 com
        // chave extraída do próprio wasm: seg[0] XOR seg[3200] — engenharia reversa
        // comprovada no lab, todos os blocos batem).
        // v5: VidSrc Gate — cópia do fluxo CineVision v151/v155 (comprovado no app).
        // vidsrc.sh/embed -> data-api (/vs_src.php) -> gate {"src": cloudorchestranova}
        // -> playerUrl -> data.vidsrc.sh/api.php stream_urls (lista) -> HLS direto.
    private suspend fun resolveVidSrcGate(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val startUrl = embedUrl.replace("vidsrcme.su", "vidsrc.sh")
            val startHtml = app.get(
                startUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer),
                timeout = 30
            ).text
            val apiPath = Regex("""data-api="([^"]+)"""").find(startHtml)?.groupValues?.getOrNull(1)
                ?.replace("&amp;", "&") ?: return false
            val apiUrl = if (apiPath.startsWith("http")) apiPath else "https://vidsrc.sh$apiPath"

            val gateJson = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to startUrl,
                    "Accept" to "application/json"
                ),
                timeout = 30
            ).text
            val innerSrc = Regex(""""src"\s*:\s*"([^"]+)"""").find(gateJson)?.groupValues?.getOrNull(1)
                ?: return false
            val innerHtml = app.get(
                innerSrc,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://vidsrc.sh/"),
                timeout = 30
            ).text

            val imdb = Regex("""tt\d+""").find(innerSrc)?.value ?: Regex("""tt\d+""").find(embedUrl)?.value
            val playerPath = Regex("""playerUrl\\?":\\?"([^"]+)""").find(innerHtml)?.groupValues?.getOrNull(1)
                ?.replace("\\u0026", "&") ?: return false
            val playerUrl = if (playerPath.startsWith("http")) playerPath else "https://cloudorchestranova.com$playerPath"
            val playerHtml = app.get(
                playerUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to innerSrc),
                timeout = 30
            ).text
            val streamApi = Regex("""\\"api\\?":\\?"([^"]+)""").find(playerHtml)?.groupValues?.getOrNull(1)
                ?.replace("\\u0026", "&")
                ?: if (!imdb.isNullOrBlank()) {
                    val isTv = embedUrl.contains("/tv/")
                    val type = if (isTv) "tv" else "movie"
                    "https://data.vidsrc.sh/api.php?type=$type&imdb=$imdb&stream_urls"
                } else return false

            val streamJson = app.get(
                streamApi,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to playerUrl,
                    "Accept" to "application/json"
                ),
                timeout = 30
            ).text
            // stream_urls pode ser lista (direto) ou string cifrada (ChaCha20+wasm)
            val resp = tryParseJson<VsStreamResp>(streamJson)
            val rawUrls = resp?.data?.streamUrls
            val urls: List<String> = when (rawUrls) {
                is List<*> -> rawUrls.filterIsInstance<String>()
                    .flatMap { Regex(""""?(https?://[^"\s,]+\.m3u8[^"\s,]*)""").findAll(it).map { m -> m.groupValues[1] } }
                    .ifEmpty { rawUrls.filterIsInstance<String>() }
                    .distinct()
                is String -> decryptVsStreamUrls(rawUrls, resp.vs, playerUrl)
                else -> {
                    // fallback: regex direto no JSON (formato antigo sem wrapper)
                    val listMatch = Regex(""""stream_urls"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                        .find(streamJson)?.groupValues?.getOrNull(1)
                    listMatch?.let { Regex(""""(https?://[^"]+\.m3u8[^"]*)"""").findAll(it).map { m -> m.groupValues[1] }.distinct().toList() }
                        .orEmpty()
                }
            }
            for (m3u8 in urls.distinct()) {
                if (!m3u8.contains(".m3u8")) continue
                callback.invoke(
                    newExtractorLink(
                        source = "EmbedPlay",
                        name = "EmbedPlay VidSrc (HLS)",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = playerUrl
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to playerUrl)
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            if (urls.isNotEmpty()) return true
            return false
        } catch (_: Exception) {
            false
        }
    }

    // v9: decrypt ChaCha20 (RFC 8439) do stream_urls cifrado — SEM WebView, SEM WASM.
    // Engenharia reversa do vsdec.js + wasm (lab): o wasm exporta alloc/decrypt; o cipher
    // é ChaCha20 com sigma padrão; chave = data-seg[0] XOR data-seg[3200] (32 bytes);
    // enc = base64(nonce12 || ciphertext); blocos com counter u32 LE a partir de 0;
    // saída em ptr+12. Muda por janela `w` — por isso baixa o wasm da `wasm_url` sempre.
    private suspend fun decryptVsStreamUrls(encB64: String, vs: VsDecryptor?, referer: String): List<String> {
        return try {
            val wasmBytes: ByteArray = if (!vs?.wasm.isNullOrBlank()) {
                android.util.Base64.decode(vs!!.wasm, android.util.Base64.DEFAULT)
            } else {
                val wasmUrl = vs?.wasmUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
                app.get(
                    wasmUrl,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer),
                    timeout = 30
                ).body.bytes()
            }
            val key = vsWasmKey(wasmBytes) ?: return emptyList()
            val enc = try {
                android.util.Base64.decode(encB64.trim(), android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                return emptyList()
            }
            if (enc.size <= 12) return emptyList()
            val nonce = enc.copyOfRange(0, 12)
            val ct = enc.copyOfRange(12, enc.size)
            val pt = chacha20(key, nonce, ct) ?: return emptyList()
            val txt = pt.toString(Charsets.UTF_8)
            txt.split("\n").map { it.trim() }.filter { it.contains(".m3u8") }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Extrai a chave ChaCha20 do wasm: parse das data-sections (id 11),
    // chave = bytes do segmento no offset 0 XOR bytes do segmento no offset 3200.
    private fun vsWasmKey(wasm: ByteArray): ByteArray? {
        return try {
            var p = 8
            var seg0: ByteArray? = null
            var seg3200: ByteArray? = null
            while (p < wasm.size) {
                val id = wasm[p++].toInt() and 0xFF
                var n = 0
                var s = 0
                while (true) {
                    val m = wasm[p++].toInt() and 0xFF
                    n = n or ((m and 0x7F) shl s)
                    s += 7
                    if (m and 0x80 == 0) break
                }
                if (id == 11) {
                    var q = p
                    var cnt = 0
                    var ss = 0
                    while (true) {
                        val m = wasm[q++].toInt() and 0xFF
                        cnt = cnt or ((m and 0x7F) shl ss)
                        ss += 7
                        if (m and 0x80 == 0) break
                    }
                    repeat(cnt) {
                        q++ // memidx
                        var off = 0
                        var so = 0
                        q++ // opcode i32.const
                        while (true) {
                            val m = wasm[q++].toInt() and 0xFF
                            off = off or ((m and 0x7F) shl so)
                            so += 7
                            if (m and 0x80 == 0) break
                        }
                        q++ // end
                        var len = 0
                        var sl = 0
                        while (true) {
                            val m = wasm[q++].toInt() and 0xFF
                            len = len or ((m and 0x7F) shl sl)
                            sl += 7
                            if (m and 0x80 == 0) break
                        }
                        val chunk = wasm.copyOfRange(q, q + len)
                        if (off == 0 && len >= 32) seg0 = chunk.copyOfRange(0, 32)
                        if (off == 3200 && len >= 32) seg3200 = chunk.copyOfRange(0, 32)
                        q += len
                    }
                }
                p += n
            }
            val a = seg0 ?: return null
            val b = seg3200 ?: return null
            ByteArray(32) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }
        } catch (_: Exception) {
            null
        }
    }

    // ChaCha20 RFC 8439 puro (20 rounds, sigma "expand 32-byte k", counter u32 LE).
    private fun chacha20(key: ByteArray, nonce: ByteArray, ct: ByteArray): ByteArray? {
        return try {
            if (key.size != 32 || nonce.size != 12 || ct.isEmpty()) return null
            fun le(b: ByteArray, o: Int): Int =
                (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
            fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))
            fun qr(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
                x[a] = x[a] + x[b]; x[d] = rotl(x[d] xor x[a], 16)
                x[c] = x[c] + x[d]; x[b] = rotl(x[b] xor x[c], 12)
                x[a] = x[a] + x[b]; x[d] = rotl(x[d] xor x[a], 8)
                x[c] = x[c] + x[d]; x[b] = rotl(x[b] xor x[c], 7)
            }
            val out = ByteArray(ct.size)
            var ctr = 0
            var pos = 0
            while (pos < ct.size) {
                val s = IntArray(16)
                s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
                for (i in 0 until 8) s[4 + i] = le(key, i * 4)
                s[12] = ctr
                s[13] = le(nonce, 0); s[14] = le(nonce, 4); s[15] = le(nonce, 8)
                val w = s.copyOf()
                repeat(10) {
                    qr(w, 0, 4, 8, 12); qr(w, 1, 5, 9, 13); qr(w, 2, 6, 10, 14); qr(w, 3, 7, 11, 15)
                    qr(w, 0, 5, 10, 15); qr(w, 1, 6, 11, 12); qr(w, 2, 7, 8, 13); qr(w, 3, 4, 9, 14)
                }
                val ks = ByteArray(64)
                for (i in 0 until 16) {
                    val v = w[i] + s[i]
                    ks[i * 4] = (v and 0xFF).toByte()
                    ks[i * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
                    ks[i * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
                    ks[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
                }
                val n = minOf(64, ct.size - pos)
                for (i in 0 until n) out[pos + i] = (ct[pos + i].toInt() xor ks[i].toInt()).toByte()
                pos += n
                ctr++
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    // v9: resolve UMA opção de player (filme via getPlayer listado ou série via
    // getOptions). videoUrl direto pode vir no option (série) ou via getPlayer(ID).
    private suspend fun resolveOneOption(
        videoId: String,
        directUrl: String,
        server: String,
        oneLink: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var videoUrl = directUrl.takeIf { it.isNotBlank() }
        if (videoUrl == null) {
            if (videoId.isBlank()) return false
            try {
                val apiResp = app.post(
                    "$ONE_BASE/api",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to oneLink,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    data = mapOf("action" to "getPlayer", "video_id" to videoId),
                    timeout = 30
                ).parsedSafe<OnePlayerResp>()
                videoUrl = apiResp?.data?.videoUrl?.takeIf { it.isNotBlank() } ?: return false
            } catch (_: Exception) {
                return false
            }
        }
        val url = videoUrl!!
        if (url.contains(".m3u8")) {
            callback.invoke(
                newExtractorLink(
                    source = "EmbedPlay",
                    name = "EmbedPlay (HLS)",
                    url = url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = oneLink
                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to oneLink)
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }
        // UPN direto: /api/v1/video?id={hash} (HTTP-puro, provado no lab)
        if (url.contains("upns.xyz") || url.contains("embedplayapiupn")) {
            try {
                if (resolveUpnDirect(url, oneLink, callback)) return true
            } catch (_: Exception) {}
        }
        // Byse -> espelho Streamwish (extrator nativo do app)
        if (url.contains("embedplaybyse.top") || server.equals("BYSE", true)) {
            // v8: code Byse pode ter - e _ (1mwapi8cwto7, 1xn1h1ntef3d)
            val code = Regex("""/e/([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.getOrNull(1)
            if (!code.isNullOrBlank()) {
                try {
                    if (loadExtractor("https://streamwish.to/e/$code", oneLink, subtitleCallback, callback)) {
                        return true
                    }
                } catch (_: Exception) {}
            }
        }
        // Abyss REAL (player.abyssplayer.com, SoTrym) via WebView — ÚNICO WebView do fluxo.
        if (url.contains("abyss") || url.contains("abysscdn") || server.equals("ABYS", true)) {
            try {
                val real = resolveAbyssShell(url, oneLink) ?: url
                if (resolveViaWebView(real, oneLink, callback)) return true
            } catch (_: Exception) {}
        }
        // Qualquer outro: extrator nativo como última tentativa (sem WebView)
        return try {
            loadExtractor(url, oneLink, subtitleCallback, callback)
        } catch (_: Exception) {
            false
        }
    }

    // Resolve os players da página embedplay.one: VidSrc Gate (data-url) +
    // api getPlayer por data-id -> ABYS / BYSE / UPN.
    // v6 (log do aparelho): ordem por velocidade comprovada —
    //   1º VidSrc Gate (HTTP-puro, ~5s, thumbnails externos OK);
    //   2º UPN /api/v1/video (HTTP-puro, provado no lab);
    //   3º Streamwish espelho do Byse via loadExtractor (extrator nativo);
    //   4º WebView SOMENTE no Abyss real (abysscdn.com, SoTrym descriptografa).
    private suspend fun resolveOnePlayers(
        oneHtml: String,
        oneLink: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false
        // 1. VidSrc Gate via data-url (HTTP-puro, sem WebView)
        val vidsrcUrl = Regex("""data-url="([^"]+)"""").find(oneHtml)?.groupValues?.getOrNull(1)
            ?.replace("&amp;", "&")
        if (!vidsrcUrl.isNullOrBlank()) {
            try {
                if (resolveVidSrcGate(vidsrcUrl, oneLink, callback)) {
                    return true
                }
            } catch (_: Exception) {}
            try {
                if (loadExtractor(vidsrcUrl, oneLink, subtitleCallback, callback)) {
                    return true
                }
            } catch (_: Exception) {}
        }
        // 2+3+4. getPlayer por data-id
        val playerIds = Regex("""data-id="(\d+)"""").findAll(oneHtml)
            .map { it.groupValues[1] }.distinct().toList()
        // v6: tenta TODOS e coleta nomes p/ priorizar UPN/BYSE antes do ABYSS
        data class PlayerEntry(val videoUrl: String, val name: String)
        val entries = mutableListOf<PlayerEntry>()
        for (vid in playerIds) {
            try {
                val apiResp = app.post(
                    "$ONE_BASE/api",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to oneLink,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    data = mapOf("action" to "getPlayer", "video_id" to vid),
                    timeout = 30
                ).parsedSafe<OnePlayerResp>()
                val videoUrl = apiResp?.data?.videoUrl?.takeIf { it.isNotBlank() } ?: continue
                entries.add(PlayerEntry(videoUrl, apiResp?.data?.captionUrl ?: videoUrl))
            } catch (_: Exception) {}
        }
        // UPN+BYSE primeiro (HTTP-puro), Abyss por último (WebView)
        val ordered = entries.sortedBy {
            when {
                it.videoUrl.contains("upns.xyz") || it.videoUrl.contains("embedplayapiupn") -> 0
                it.videoUrl.contains("byse") -> 1
                it.videoUrl.contains(".m3u8") -> 2
                else -> 3
            }
        }
        for ((videoUrl, _) in ordered) {
            if (videoUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = "EmbedPlay",
                        name = "EmbedPlay (HLS)",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = oneLink
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to oneLink)
                        this.quality = Qualities.P1080.value
                    }
                )
                foundAny = true
                break
            }
            // 2. UPN direto: /api/v1/video?id={hash} (HTTP-puro, provado no lab)
            if (videoUrl.contains("upns.xyz") || videoUrl.contains("embedplayapiupn")) {
                try {
                    if (resolveUpnDirect(videoUrl, oneLink, callback)) {
                        foundAny = true
                        break
                    }
                } catch (_: Exception) {}
            }
            // 3. Byse -> espelho Streamwish (extrator nativo do app)
            if (videoUrl.contains("embedplaybyse.top")) {
                // v8: code Byse pode ter - e _ (1mwapi8cwto7, 1xn1h1ntef3d)
                val code = Regex("""/e/([a-zA-Z0-9_-]+)""").find(videoUrl)?.groupValues?.getOrNull(1)
                if (!code.isNullOrBlank()) {
                    try {
                        if (loadExtractor("https://streamwish.to/e/$code", oneLink, subtitleCallback, callback)) {
                            foundAny = true
                            break
                        }
                    } catch (_: Exception) {}
                }
            }
            // 4. Abyss REAL (player.abyssplayer.com, SoTrym) via WebView — ÚNICO WebView do fluxo.
            // O shell embedplayabyss.top/player.html?v= é casca vazia (só monta iframe);
            // resolve p/ https://player.abyssplayer.com/{slug} antes (log do aparelho
            // prova que o .one já retorna esse domínio p/ filmes novos).
            if (videoUrl.contains("abyss") || videoUrl.contains("abysscdn")) {
                try {
                    val real = resolveAbyssShell(videoUrl, oneLink) ?: videoUrl
                    if (resolveViaWebView(real, oneLink, callback)) {
                        foundAny = true
                        break
                    }
                } catch (_: Exception) {}
            }
            // 5. Qualquer outro: extrator nativo como última tentativa (sem WebView)
            try {
                if (loadExtractor(videoUrl, oneLink, subtitleCallback, callback)) {
                    foundAny = true
                    break
                }
            } catch (_: Exception) {}
        }
        return foundAny
    }

    // UPN direto: extrai o hash do fragmento (#hash) e chama /api/v1/video?id=.
    // Resposta é AES-CBC (hex) — sem WebCrypto no app, então devolve false e o
    // chamador cai no WebView; mas tenta primeiro um .m3u8 embutido no JSON.
    private suspend fun resolveUpnDirect(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val hash = playerUrl.substringAfter("#", "").substringBefore("&").trim()
            if (hash.isBlank()) return false
            val host = Regex("""(https?://[^/]+)""").find(playerUrl)?.groupValues?.getOrNull(1)
                ?: return false
            val json = app.get(
                "$host/api/v1/video?id=$hash",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to playerUrl,
                    "Accept" to "application/json"
                ),
                timeout = 30
            ).text
            val urls = Regex("""(https?://[^"\s<>]+\.m3u8[^"\s<>]*)""").findAll(json)
                .map { it.groupValues[1] }.distinct().toList()
            for (m3u8 in urls) {
                callback.invoke(
                    newExtractorLink(
                        source = "EmbedPlay",
                        name = "EmbedPlay UPN (HLS)",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = playerUrl
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to playerUrl)
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            urls.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    // v7 (log aparelho): o .one agora retorna player.abyssplayer.com/{slug}
    // (player real SoTrym, substitui embedplayabyss.top + abysscdn.com).
    // Shell embedplayabyss.top/player.html?v={slug} -> player.abyssplayer.com/{slug}.
    // O shell é casca vazia (1.9KB, só monta o iframe via JS); o SoTrym que monta
    // o HLS roda no player real — é LÁ que o WebView precisa carregar.
    private suspend fun resolveAbyssShell(playerUrl: String, referer: String): String? {
        return try {
            if (playerUrl.contains("abysscdn.com") || playerUrl.contains("abyssplayer.com")) return playerUrl
            // v8: slugs Abyss novos têm - e _ (cPk-jXmMH); truncar em "cPk" dava 404
            val slug = Regex("""[?&]v=([a-zA-Z0-9_-]+)""").find(playerUrl)?.groupValues?.getOrNull(1)
                ?: return null
            "https://player.abyssplayer.com/$slug"
        } catch (_: Exception) {
            null
        }
    }

    // WebView SOMENTE p/ o Abyss real (abysscdn.com, SoTrym descriptografa e o
    // player monta o HLS). Timeout 25s (CineVision usa 25s no Abyss/UPN).
    private suspend fun resolveViaWebView(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val intercept = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            val resp = app.get(
                playerUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer),
                interceptor = WebViewResolver(intercept, timeout = 25000L),
                timeout = 35
            )
            val html = try { resp.text } catch (_: Throwable) { "" }
            val found = mutableSetOf<String>()
            intercept.findAll(html).forEach { found.add(it.value) }
            intercept.find(resp.url)?.let { found.add(it.value) }
            for (m3u8 in found) {
                if (m3u8.contains("googlesyndication") || m3u8.contains("morphify")) continue
                callback.invoke(
                    newExtractorLink(
                        source = "EmbedPlay",
                        name = "EmbedPlay (HLS)",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = playerUrl
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to playerUrl)
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            found.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }
}
