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
        val poster = (card.selectFirst(".poster-img") ?: element.selectFirst(".poster-img"))?.attr("data-bg-multi")
            ?.let { Regex("""url\((https?://[^)]+)\)""").find(it)?.groupValues?.getOrNull(1) }
            ?.let { fixUrlNull(it) }
        val url = "$mainUrl/view/$tmdb"
        return if (isSeriesSection) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
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
                    ?.let { fixUrlNull(it) }
                newMovieSearchResponse(title, "$mainUrl/view/$tmdb", TvType.Movie) {
                    this.posterUrl = poster
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
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, payload) {
                this.posterUrl = poster
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
            // 1. embed .top -> data-movie-id + server data-id reais
            val embedPath = if (isSeries) "/embed/$tmdbId/$season/$episode" else "/embed/$tmdbId"
            val embedHtml = app.get(
                "$mainUrl$embedPath",
                headers = BROWSER_HEADERS + mapOf("Referer" to "$mainUrl/"),
                timeout = 30
            ).text
            val movieCode = Regex("""data-movie-id="([^"]+)"""").find(embedHtml)?.groupValues?.getOrNull(1)
            val serverId = Regex("""class="server[^"]*"[^>]*data-id="([a-zA-Z0-9]+)"""").find(embedHtml)
                ?.groupValues?.getOrNull(1)
            if (movieCode.isNullOrBlank() || serverId.isNullOrBlank()) return false

            // 2. ajax/get_stream_link -> link embedplay.one + VidSrc direto (data-url)
            val linkResp = app.get(
                "$mainUrl/ajax/get_stream_link?id=$serverId&movie=$movieCode&is_init=false",
                headers = BROWSER_HEADERS + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl$embedPath"
                ),
                timeout = 30
            ).parsedSafe<StreamLinkResp>()
            val oneLink = linkResp?.data?.link?.takeIf { it.isNotBlank() }

            // 2b. VidSrc direto do embed .one (opcional data-url) — tenta sempre
            if (!oneLink.isNullOrBlank()) {
                try {
                    val oneHtml = app.get(
                        oneLink,
                        headers = BROWSER_HEADERS + mapOf("Referer" to "$mainUrl$embedPath"),
                        timeout = 30
                    ).text
                    val vidsrcUrl = Regex("""data-url="([^"]+)"""").find(oneHtml)?.groupValues?.getOrNull(1)
                        ?.replace("&amp;", "&")
                    if (!vidsrcUrl.isNullOrBlank()) {
                        if (loadExtractor(vidsrcUrl, oneLink, subtitleCallback, callback)) {
                            foundAny = true
                        }
                    }
                    // 3. api getPlayer por data-id -> ABYS / BYSE / UPN
                    val playerIds = Regex("""data-id="(\d+)"""").findAll(oneHtml)
                        .map { it.groupValues[1] }.distinct().toList()
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
                            } else if (resolveViaWebView(videoUrl, oneLink, callback)) {
                                foundAny = true
                            } else if (loadExtractor(videoUrl, oneLink, subtitleCallback, callback)) {
                                foundAny = true
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
            foundAny
        } catch (_: Exception) {
            foundAny
        }
    }

    // Abyss (embedplayabyss.top) / Byse / UPN são SPAs com JS — WebView do app
    // carrega o player e intercepta o .m3u8 montado.
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
                interceptor = WebViewResolver(intercept),
                timeout = 60
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
