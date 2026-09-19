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
        return try {
            val resp = app.get(
                "$mainUrl/ajax/get_suggest?title=${URLEncoder.encode(query.trim(), "UTF-8")}&type=movie",
                headers = BROWSER_HEADERS + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/"
                ),
                timeout = 30
            ).parsedSafe<SuggestResp>()
            val html = resp?.data?.results ?: return emptyList()
            val doc = Jsoup.parse(html)
            doc.select(".movie-card").mapNotNull { card ->
                val tmdb = card.attr("data-tmdb").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = card.selectFirst("p.title")?.text()?.trim() ?: return@mapNotNull null
                if (title.isBlank()) return@mapNotNull null
                val poster = card.selectFirst(".poster-img")?.attr("data-bg-multi")
                    ?.let { Regex("""url\((https?://[^)]+)\)""").find(it)?.groupValues?.getOrNull(1) }
                    ?.replace("/w300/", "/w500/")
                    ?.let { fixUrlNull(it) }
                newMovieSearchResponse(title, "$mainUrl/view/$tmdb", TvType.Movie) {
                    this.posterUrl = poster
                    this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                }
            }.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
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
        val payload = "tmdb:$tmdbId|imdb:$imdbId|series:$isSeriesUrl"

        return if (isSeriesUrl && tmdbId.isNotBlank()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(
                newEpisode("$payload|season:1|episode:1") {
                    this.name = "T1:E1"
                    this.season = 1
                    this.episode = 1
                }
            )) {
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
        val isSeries = data.contains("series:true")
        if (tmdbId.isBlank()) return false

        return try {
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
            // v4: pega QUALQUER server válido (era só o 1º); tenta cada um até achar link
            val serverIds = Regex("""class="server[^"]*"[^>]*data-id="([a-zA-Z0-9]+)"""")
                .findAll(embedHtml).map { it.groupValues[1] }.distinct().toList()
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

    // v5: VidSrc Gate — cópia do fluxo CineVision v151/v155 (comprovado no app).
    // vidsrc.sh/embed -> data-api (/vs_src.php) -> gate {"src": cloudorchestranova}
    // -> playerUrl -> data.vidsrc.sh/api.php stream_urls (lista) -> HLS direto.
    // Se stream_urls vier cifrada (string), delega ao WebView (fetch no player).
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
            // stream_urls pode ser lista (direto) ou string cifrada (WebView)
            val listMatch = Regex(""""stream_urls"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                .find(streamJson)?.groupValues?.getOrNull(1)
            val urls = listMatch?.let { Regex(""""(https?://[^"]+\.m3u8[^"]*)"""").findAll(it).map { m -> m.groupValues[1] }.distinct().toList() }
                .orEmpty()
            for (m3u8 in urls) {
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
            // stream_urls cifrada -> WebView no player intercepta o .m3u8 montado
            resolveViaWebView(playerUrl, innerSrc, callback)
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
                val code = Regex("""/e/([a-zA-Z0-9]+)""").find(videoUrl)?.groupValues?.getOrNull(1)
                if (!code.isNullOrBlank()) {
                    try {
                        if (loadExtractor("https://streamwish.to/e/$code", oneLink, subtitleCallback, callback)) {
                            foundAny = true
                            break
                        }
                    } catch (_: Exception) {}
                }
            }
            // 4. Abyss REAL (abysscdn.com, SoTrym) via WebView — ÚNICO WebView do fluxo.
            // O shell embedplayabyss.top/player.html?v= é casca vazia (só monta iframe);
            // resolve p/ https://abysscdn.com/?v={slug} antes, que é onde o SoTrym roda.
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

    // Shell embedplayabyss.top/player.html?v={slug} -> https://abysscdn.com/?v={slug}.
    // O shell é casca vazia (1.9KB, só monta o iframe via JS); o SoTrym que monta
    // o HLS roda no abysscdn.com — é LÁ que o WebView precisa carregar.
    private suspend fun resolveAbyssShell(playerUrl: String, referer: String): String? {
        return try {
            if (playerUrl.contains("abysscdn.com")) return playerUrl
            val slug = Regex("""[?&]v=([a-zA-Z0-9]+)""").find(playerUrl)?.groupValues?.getOrNull(1)
                ?: return null
            "https://abysscdn.com/?v=$slug"
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
