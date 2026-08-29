package com.CineVision

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object LocalHlsServer {
    private var serverSocket: ServerSocket? = null
    private var localPort: Int = 0
    private val playlists = ConcurrentHashMap<String, String>()
    private val idCounter = AtomicInteger(1)
    private val executor = Executors.newCachedThreadPool()

    init {
        try {
            val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            localPort = ss.localPort
            serverSocket = ss

            executor.submit {
                while (!ss.isClosed) {
                    try {
                        val client = ss.accept()
                        handleClient(client)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun handleClient(client: Socket) {
        executor.submit {
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val requestLine = reader.readLine().orEmpty()
                val path = requestLine.split(" ").getOrNull(1).orEmpty()
                val id = path.substringAfter("/hls/").substringBefore("/")
                val playlist = playlists[id]

                val output: OutputStream = client.getOutputStream()
                if (playlist != null) {
                    val body = playlist.toByteArray(StandardCharsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/vnd.apple.mpegurl\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Connection: close\r\n\r\n"
                    output.write(header.toByteArray(StandardCharsets.UTF_8))
                    output.write(body)
                    output.flush()
                } else {
                    val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    output.write(notFound.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
                client.close()
            } catch (_: Exception) {}
        }
    }

    fun serve(manifest: String): String {
        val id = idCounter.incrementAndGet().toString()
        playlists[id] = manifest
        return "http://127.0.0.1:$localPort/hls/$id/master.m3u8"
    }
}

class CineVision : MainAPI() {
    override var mainUrl = "https://www.cinevision.lat"
    override var name = "CineVision"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
        TvType.AsianDrama
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val BROWSER_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://www.cinevision.lat/"
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/filmes/" to "Filmes Recentes",
        "$mainUrl/series/" to "Séries Atualizadas",
        "$mainUrl/category/acao/" to "Ação",
        "$mainUrl/category/animacao/" to "Animação & Animes",
        "$mainUrl/category/aventura/" to "Aventura",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/crime/" to "Crime",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/sci-fi-fantasy/" to "Sci-Fi & Fantasia",
        "$mainUrl/category/familia/" to "Família & Kids",
        "$mainUrl/category/documentario/" to "Documentário"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.removeSuffix("/")
        val url = if (page <= 1) {
            "$baseUrl/"
        } else {
            "$baseUrl/page/$page/"
        }

        val doc = app.get(url, headers = BROWSER_HEADERS).document
        val elements = doc.select("article.post, .items article, li.movies, li.tvshows, .film-card, article")
        val homeList = elements.mapNotNull { parseCard(it) }.distinctBy { it.url }

        val hasNext = hasNextPage(doc, page, homeList.size)
        return newHomePageResponse(
            listOf(HomePageList(request.name, homeList)),
            hasNext = hasNext
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/?s=$encoded"

        return try {
            val doc = app.get(searchUrl, headers = BROWSER_HEADERS).document
            val elements = doc.select("article.post, .items article, li.movies, li.tvshows, .film-card, article")
            elements.mapNotNull { parseCard(it) }.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = BROWSER_HEADERS).document

        val rawTitle = doc.selectFirst("h1.entry-title, h1")?.text()
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "CineVision"

        val title = cleanMediaTitle(rawTitle)

        val poster = doc.selectFirst(".poster img, .imagen img, img[src*='tmdb']")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-original") }.ifBlank { img.attr("src") }
        } ?: doc.selectFirst("meta[property='og:image']")?.attr("content")

        val plot = doc.select(".description p, .sinopse p, .overview p, .entry-content p")
            .map { it.text().trim() }
            .firstOrNull { it.length > 25 && !it.contains("Assistir", true) && !it.contains("CineVision", true) }
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")

        val year = doc.selectFirst(".year, span.year, .date, a[href*='/ano/']")?.text()
            ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }

        val tags = doc.select(".genres a, .meta-generos a, a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val rating = doc.selectFirst(".nota, .rating, .dt_rating_vgs, span.imdb")?.text()
            ?.let { Regex("""[\d.]+""").find(it)?.value?.toDoubleOrNull() }
            ?.let { Score.from10(it) }

        val embedIframe = doc.selectFirst("iframe[src*='painel-aso.sbs']")?.attr("src")
            ?: doc.selectFirst("iframe[src]")?.attr("src")

        val isSeriesPage = url.contains("/serie/") || (embedIframe != null && embedIframe.contains("/embed/"))

        val mediaId = extractMediaId(embedIframe.orEmpty(), doc)

        if (isSeriesPage && !embedIframe.isNullOrBlank()) {
            val episodes = extractSeriesEpisodes(embedIframe, url, mediaId)
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = optimizePosterUrl(poster.orEmpty())
                    this.plot = plot
                    this.tags = tags
                    this.year = year
                    this.score = rating
                }
            }
        }

        val moviePayload = if (mediaId.isNotBlank()) "$url#mediaId=$mediaId" else url

        return newMovieLoadResponse(title, url, TvType.Movie, moviePayload) {
            this.posterUrl = optimizePosterUrl(poster.orEmpty())
            this.plot = plot
            this.tags = tags
            this.year = year
            this.score = rating
        }
    }

    private fun extractMediaId(embedUrl: String, doc: Document): String {
        val imdbMatch = Regex("""(tt\d+)""").find(embedUrl)?.groupValues?.getOrNull(1)
        if (!imdbMatch.isNullOrBlank()) return imdbMatch

        val tmdbMatch = Regex("""/(?:embed|filme)/(\d+)""").find(embedUrl)?.groupValues?.getOrNull(1)
        if (!tmdbMatch.isNullOrBlank()) return tmdbMatch

        val html = doc.html()
        val tmdbData = Regex("""data-tmdb-id=["'](\d+)["']""").find(html)?.groupValues?.getOrNull(1)
        if (!tmdbData.isNullOrBlank()) return tmdbData

        return ""
    }

    private suspend fun extractSeriesEpisodes(embedUrl: String, seriesPageUrl: String, seriesMediaId: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        try {
            val embedHtml = app.get(
                embedUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to seriesPageUrl)
            ).text

            val soup = Jsoup.parse(embedHtml)

            val seasonsMap = mutableMapOf<String, Int>()
            soup.select("li[data-season-id]").forEach { sli ->
                val sId = sli.attr("data-season-id")
                val sNum = sli.attr("data-season-number").toIntOrNull() ?: 1
                if (sId.isNotBlank()) seasonsMap[sId] = sNum
            }

            soup.select("li[data-episode-id]").forEach { eli ->
                val sId = eli.attr("data-season-id")
                val epId = eli.attr("data-episode-id")
                if (epId.isBlank()) return@forEach

                val sNum = seasonsMap[sId] ?: 1
                val rawText = eli.text().trim()
                val epNum = Regex("""\b(\d+)\b""").find(rawText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

                val cleanName = rawText
                    .replace(Regex("""^\d+\s*[-–:]*\s*"""), "")
                    .replace(Regex("""(?i)\bEpis[oó]dio\s*\d+\b"""), "")
                    .trim()
                    .ifBlank { "Episódio $epNum" }

                val epDataUrl = "https://www.painel-aso.sbs/episodio/$epId#tmdb=$seriesMediaId&season=$sNum&episode=$epNum"

                episodes.add(
                    newEpisode(epDataUrl) {
                        this.name = cleanName
                        this.season = sNum
                        this.episode = epNum
                    }
                )
            }
        } catch (_: Exception) {}
        return episodes.distinctBy { it.data }
    }

    data class GleamPlayData(
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("player") val player: String? = null,
        @JsonProperty("audio_type") val audioType: String? = null
    )

    data class LoadVidPayload(
        @JsonProperty("token") val token: String,
        @JsonProperty("hash") val hash: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false

        val tmdbParam = Regex("""tmdb=([^&]+)""").find(data)?.groupValues?.getOrNull(1)
        val mediaIdParam = Regex("""mediaId=([^&]+)""").find(data)?.groupValues?.getOrNull(1) ?: tmdbParam
        val seasonParam = Regex("""season=(\d+)""").find(data)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episodeParam = Regex("""episode=(\d+)""").find(data)?.groupValues?.getOrNull(1)?.toIntOrNull()

        // 1. Resolução dos Players do painel-aso.sbs (Prioridade Máxima para LoadVid e Players Nativos)
        if (data.contains("painel-aso.sbs/episodio/")) {
            val cleanEpUrl = data.substringBefore("#")
            try {
                val epHtml = app.get(
                    cleanEpUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://www.painel-aso.sbs/",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).text

                val soup = Jsoup.parse(epHtml)

                // Coleta todos os botões e ordena colocando LoadVid primeiro
                val buttons = soup.select("button[data-source], [data-source]")
                    .map { it.attr("data-source") to it.text().trim().ifBlank { "Player" } }
                    .filter { it.first.isNotBlank() }
                    .sortedByDescending { it.first.contains("loadvid.com") }

                for ((src, label) in buttons) {
                    if (resolveStreamOrExtractor(src, label, cleanEpUrl, subtitleCallback, callback)) {
                        foundAny = true
                    }
                }

                val playMatch = Regex("""play\s*\(\s*(\{.*?\})\s*\)""").find(epHtml)?.groupValues?.getOrNull(1)
                if (!playMatch.isNullOrBlank()) {
                    val playObj = tryParseJson<GleamPlayData>(playMatch)
                    val src = playObj?.source
                    val title = playObj?.title ?: "Player Principal"
                    if (!src.isNullOrBlank()) {
                        if (resolveStreamOrExtractor(src, title, cleanEpUrl, subtitleCallback, callback)) {
                            foundAny = true
                        }
                    }
                }
            } catch (_: Exception) {}
        } else {
            val cleanMovieUrl = data.substringBefore("#")
            try {
                val doc = app.get(cleanMovieUrl, headers = BROWSER_HEADERS).document
                val embedUrl = doc.selectFirst("iframe[src*='painel-aso.sbs']")?.attr("src")
                    ?: doc.selectFirst("iframe[src]")?.attr("src")

                if (!embedUrl.isNullOrBlank()) {
                    val embedHtml = app.get(
                        embedUrl,
                        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to cleanMovieUrl)
                    ).text

                    val soup = Jsoup.parse(embedHtml)

                    val buttons = soup.select("button[data-source], [data-source]")
                        .map { it.attr("data-source") to it.text().trim().ifBlank { "Player" } }
                        .filter { it.first.isNotBlank() }
                        .sortedByDescending { it.first.contains("loadvid.com") }

                    for ((src, label) in buttons) {
                        if (resolveStreamOrExtractor(src, label, embedUrl, subtitleCallback, callback)) {
                            foundAny = true
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Resolução Multi-Server Direta de Fallback (MegaEmbed, SuperFlix, PlayerFlix)
        if (!mediaIdParam.isNullOrBlank()) {
            if (seasonParam != null && episodeParam != null) {
                if (resolveMegaEmbed("https://megaembed.com/embed/$mediaIdParam/$seasonParam/$episodeParam", callback)) foundAny = true
                if (resolveSuperFlix("https://superflixapi.pro/serie/$mediaIdParam/$seasonParam/$episodeParam", callback)) foundAny = true
                if (resolvePlayerFlix("https://playerflixapi.com/serie/$mediaIdParam/$seasonParam/$episodeParam", callback)) foundAny = true
            } else {
                if (resolveMegaEmbed("https://megaembed.com/embed/$mediaIdParam", callback)) foundAny = true
                if (resolveSuperFlix("https://superflixapi.pro/filme/$mediaIdParam", callback)) foundAny = true
                if (resolvePlayerFlix("https://playerflixapi.com/filme/$mediaIdParam", callback)) foundAny = true
            }
        }

        return foundAny
    }

    private suspend fun resolveMegaEmbed(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val html = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://www.cinevision.lat/")).text
            val m3u8s = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").findAll(html).map { it.value }.toList()
            for ((idx, m3u8) in m3u8s.distinct().withIndex()) {
                val serverName = if (idx == 0) "MegaEmbed Principal" else "MegaEmbed Backup $idx"
                callback.invoke(
                    newExtractorLink(
                        source = "MegaEmbed",
                        name = "$serverName (HLS)",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://megaembed.com/")
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            m3u8s.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveSuperFlix(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val html = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://www.cinevision.lat/")).text
            val m3u8s = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").findAll(html).map { it.value }.toList()
            for (m3u8 in m3u8s.distinct()) {
                callback.invoke(
                    newExtractorLink(
                        source = "SuperFlix",
                        name = "SuperFlix (HLS)",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://superflixapi.pro/")
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            m3u8s.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolvePlayerFlix(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val html = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://www.cinevision.lat/")).text
            val m3u8s = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").findAll(html).map { it.value }.toList()
            for (m3u8 in m3u8s.distinct()) {
                callback.invoke(
                    newExtractorLink(
                        source = "PlayerFlix",
                        name = "PlayerFlix (HLS)",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://playerflixapi.com/")
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            m3u8s.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveLoadVid(
        playUrl: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(
                playUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
            ).text

            val csrf = Regex("""name="csrf-token"\s+content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            val token = Regex("""videoToken\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""videoToken\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)
            val hash = Regex("""videoHash\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""videoHash\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)

            if (!csrf.isNullOrBlank() && !token.isNullOrBlank() && !hash.isNullOrBlank()) {
                val resp = app.post(
                    "https://cdn.loadvid.com/videos/resolve-token",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to playUrl,
                        "X-CSRF-TOKEN" to csrf,
                        "Content-Type" to "application/json",
                        "Accept" to "application/vnd.apple.mpegurl,*/*",
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    json = LoadVidPayload(token, hash)
                ).text

                if (resp.contains("#EXTM3U")) {
                    val localHlsUrl = LocalHlsServer.serve(resp)
                    val sName = if (label.isNotBlank()) "LoadVid $label" else "LoadVid (HLS 1080p)"

                    callback.invoke(
                        newExtractorLink(
                            source = "LoadVid",
                            name = sName,
                            url = localHlsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://cdn.loadvid.com/"
                            )
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveStreamOrExtractor(
        streamOrEmbedUrl: String,
        serverLabel: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var success = false

        // 1. Resolução Nativa LoadVid (HLS via Servidor Local de Interoperabilidade) - Rápido (<300ms)
        if (streamOrEmbedUrl.contains("cdn.loadvid.com")) {
            if (resolveLoadVid(streamOrEmbedUrl, serverLabel, referer, callback)) {
                return true
            }
        }

        // 2. Resolução Especial: PlayEmbed / Abyss
        if (streamOrEmbedUrl.contains("playembedapi.site")) {
            val vParam = Regex("""[?&]v=([^&]+)""").find(streamOrEmbedUrl)?.groupValues?.getOrNull(1)
            if (!vParam.isNullOrBlank()) {
                val abyssUrl = "https://abyss.to/?v=$vParam"
                try {
                    if (loadExtractor(abyssUrl, referer, subtitleCallback, callback)) {
                        success = true
                    }
                } catch (_: Exception) {}
            }
        }

        // 3. Resolução Especial: VidSrc
        if (streamOrEmbedUrl.contains("vidsrcme.su")) {
            val vidsrcMe = streamOrEmbedUrl.replace("vidsrcme.su", "vidsrc.me")
            val vidsrcTo = streamOrEmbedUrl.replace("vidsrcme.su", "vidsrc.to")
            try {
                if (loadExtractor(vidsrcMe, referer, subtitleCallback, callback) ||
                    loadExtractor(vidsrcTo, referer, subtitleCallback, callback)) {
                    success = true
                }
            } catch (_: Exception) {}
        }

        // 4. Resolução Especial: Streamwish / EmbedPlay (Descarta se expirado/deletado)
        if (streamOrEmbedUrl.contains("embedplaybyse.top")) {
            val code = Regex("""/e/([a-zA-Z0-9]+)""").find(streamOrEmbedUrl)?.groupValues?.getOrNull(1)
            if (!code.isNullOrBlank()) {
                val swUrl = "https://streamwish.to/e/$code"
                try {
                    if (loadExtractor(swUrl, referer, subtitleCallback, callback)) {
                        success = true
                    }
                } catch (_: Exception) {}
            }
        }

        // 5. Tenta extratores nativos gerais do CloudStream
        try {
            if (loadExtractor(streamOrEmbedUrl, referer, subtitleCallback, callback)) {
                success = true
            }
        } catch (_: Exception) {}

        // 6. Extração genérica de stream direto (.m3u8 / .mp4) - descarta páginas HTML de erro
        if (!success) {
            try {
                val html = app.get(
                    streamOrEmbedUrl,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
                ).text

                if (!html.contains("File is no longer available", ignoreCase = true) &&
                    !html.contains("File Deleted", ignoreCase = true)) {

                    val m3u8 = Regex("""["'](https?://[^\s"']+\.m3u8[^\s"']*)["']""").find(html)?.groupValues?.getOrNull(1)
                        ?: Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(html)?.groupValues?.getOrNull(1)

                    val mp4 = Regex("""["'](https?://[^\s"']+\.mp4[^\s"']*)["']""").find(html)?.groupValues?.getOrNull(1)

                    val directUrl = m3u8 ?: mp4
                    if (!directUrl.isNullOrBlank()) {
                        val isM3u8 = directUrl.contains(".m3u8")
                        val sourceName = if (serverLabel.isNotBlank()) "CineVision - $serverLabel" else "CineVision"

                        callback.invoke(
                            newExtractorLink(
                                source = sourceName,
                                name = "$sourceName (${if (isM3u8) "HLS" else "MP4"})",
                                url = directUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to streamOrEmbedUrl)
                                this.quality = Qualities.P1080.value
                            }
                        )
                        success = true
                    }
                }
            } catch (_: Exception) {}
        }

        return success
    }

    private fun parseCard(element: Element): SearchResponse? {
        val aTag = element.selectFirst("a[href*='/filme/'], a[href*='/serie/'], .poster a, a")
            ?: (if (element.tagName() == "a") element else null)
            ?: return null

        val link = aTag.attr("href").ifBlank { return null }
        if (link.endsWith("/filmes/") || link.endsWith("/series/") || link.contains("/category/") || link.contains("/tag/")) {
            return null
        }
        val fullUrl = fixUrl(link)

        var rawTitle = ""
        val titleEl = element.selectFirst("h2, h3, .title, .entry-title, a[title]")
        if (titleEl != null) {
            rawTitle = titleEl.text().ifBlank { titleEl.attr("title") }
        }
        if (rawTitle.isBlank()) {
            rawTitle = aTag.attr("title")
        }
        if (rawTitle.isBlank()) {
            rawTitle = element.selectFirst("img")?.attr("alt").orEmpty()
        }

        val cleanTitle = cleanMediaTitle(rawTitle)
        if (cleanTitle.isBlank()) return null

        val rawPoster = element.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-original") }.ifBlank { img.attr("src") }
        }.orEmpty()
        val poster = optimizePosterUrl(rawPoster)

        val isSeries = fullUrl.contains("/serie/") || rawTitle.contains("Temporada", ignoreCase = true)
        val qualityText = element.selectFirst(".quality, span.hd, .featu")?.text().orEmpty()
        val quality = if (qualityText.contains("4K", ignoreCase = true)) SearchQuality.UHD else SearchQuality.HD

        return if (isSeries) {
            newTvSeriesSearchResponse(cleanTitle, fullUrl, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(cleanTitle, fullUrl, TvType.Movie) {
                this.posterUrl = poster
                this.quality = quality
            }
        }
    }

    private fun cleanMediaTitle(raw: String): String {
        return raw
            .replace(Regex("""(?i)\s*-\s*CineVision.*$"""), "")
            .replace(Regex("""(?i)\s*\|\s*CineVision.*$"""), "")
            .replace(Regex("""(?i)^Assistir\s+"""), "")
            .replace(Regex("""(?i)\s+Online(\s+Grátis|\s+em\s+HD)?\b"""), "")
            .replace(Regex("""(?i)\s*-\s*(Dublado|Legendado|Nacional|Dual\s*Áudio).*$"""), "")
            .replace(Regex("""(?i)\s*\((Dublado|Legendado|Nacional|Dual\s*Áudio)\)"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun optimizePosterUrl(url: String): String {
        if (url.isBlank()) return ""
        val fixed = if (url.startsWith("//")) "https:$url" else url
        return if (fixed.contains("image.tmdb.org")) {
            fixed.replace("/w92/", "/w300/").replace("/w154/", "/w300/").replace("/w185/", "/w300/")
        } else {
            fixUrl(fixed)
        }
    }

    private fun hasNextPage(document: Document, currentPage: Int, itemCount: Int): Boolean {
        if (document.select("a.next, .pagination a.next, a[rel='next'], a:contains(Próxima), a:contains(Próximo)").isNotEmpty()) return true
        val nextPage = currentPage + 1
        if (document.select("a[href*='/page/$nextPage/']").isNotEmpty()) return true
        return itemCount >= 24
    }
}
