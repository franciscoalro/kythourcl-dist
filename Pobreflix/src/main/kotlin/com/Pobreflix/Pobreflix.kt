package com.Pobreflix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Pobreflix : MainAPI() {
    override var mainUrl = "https://www.pobreflixtv.futbol"
    override var name = "Pobreflix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val BROWSER_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/filmes/" to "Filmes",
        "$mainUrl/series/" to "Séries",
        "$mainUrl/filmes/page/1/" to "Lançamentos de Filmes",
        "$mainUrl/series/page/1/" to "Lançamentos de Séries"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a.block, a") ?: (if (this.tagName() == "a") this else return null)
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        
        val rawTitle = link.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst(".title, h3, h2, .name")?.text()?.trim()
            ?: link.text().trim()
            
        if (rawTitle.isBlank()) return null
        
        val cleanTitle = rawTitle
            .replace(Regex("""^Assistir\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Online.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+em HD.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Dublado.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Legendado.*""", RegexOption.IGNORE_CASE), "")
            .trim()

        val img = this.selectFirst("img") ?: link.selectFirst("img")
        val poster = img?.let {
            it.attr("src").takeIf { s -> s.isNotBlank() }
                ?: it.attr("data-src").takeIf { s -> s.isNotBlank() }
        }?.let { fixUrlNull(it) }

        val isTv = href.contains("/series/") || href.contains("/episodios/")
        val tvType = if (isTv) TvType.TvSeries else TvType.Movie

        return if (isTv) {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), tvType) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(cleanTitle, fixUrl(href), tvType) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            val base = request.data.removeSuffix("/")
            if (base.contains("/page/")) {
                base.replace(Regex("""/page/\d+"""), "/page/$page") + "/"
            } else {
                "$base/page/$page/"
            }
        }

        return try {
            val doc = app.get(url, headers = BROWSER_HEADERS).document
            val items = doc.select("a.block, .swiper-slide.vbTabSliderItem a, .vbTabSliderItem a, .similarMovies a.block")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    data class BuscarResponse(
        @JsonProperty("html") val html: String? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        return try {
            val searchUrl = "$mainUrl/index.php?app=videobox&module=video&controller=index&do=buscarContent&q=${URLEncoder.encode(cleanQuery, "UTF-8")}&type=todos"
            val response = app.get(
                searchUrl,
                headers = BROWSER_HEADERS + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/buscar"
                )
            ).parsedSafe<BuscarResponse>()

            val html = response?.html
            if (!html.isNullOrBlank()) {
                val doc = Jsoup.parse(html, mainUrl)
                doc.select("a.block, .similarMovies a.block, .block")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    data class EpisodeItem(
        @JsonProperty("number") val number: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("thumb") val thumb: String? = null,
        @JsonProperty("duration") val duration: String? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class EpisodesListResponse(
        @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null
    )

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = BROWSER_HEADERS).document

        val rawTitle = doc.selectFirst("h1.type, h1, .dataplus h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Pobreflix"

        val title = rawTitle
            .replace(Regex("""^Assistir\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Online.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+em HD.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Dublado.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Legendado.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*Pobreflix.*""", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = doc.selectFirst(".vbItemImage img, .poster img, img[src*='image.tmdb.org']")?.let {
            it.attr("src").takeIf { s -> s.isNotBlank() } ?: it.attr("data-src")
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst(".sinopse, .description, .tagline, p")?.text()?.trim()

        val year = doc.selectFirst(".year, .date, span.year")?.text()
            ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }
            ?: Regex("""\b(19|20)\d{2}\b""").find(rawTitle)?.value?.toIntOrNull()

        val genres = doc.select(".genres a, a[href*='/genero/'], .cat").map { it.text().trim() }.filter { it.isNotBlank() }

        val videoId = doc.selectFirst("#view")?.attr("data-video-id")
            ?: Regex("""-(\d+)/?$""").find(url)?.groupValues?.getOrNull(1)
            ?: ""

        val isTv = url.contains("/series/") || doc.selectFirst(".vbEpisodes, #seasons") != null

        if (isTv && videoId.isNotBlank()) {
            val episodes = mutableListOf<Episode>()
            val maxSeasons = doc.select(".vbSeasonSelect__option, [data-season]").mapNotNull {
                it.attr("data-season").toIntOrNull()
            }.maxOrNull() ?: 1

            for (s in 1..maxSeasons) {
                try {
                    val epsUrl = "$mainUrl/index.php?app=videobox&module=video&controller=view&do=episodesList&id=$videoId&season=$s&audio=Dublado"
                    val res = app.get(
                        epsUrl,
                        headers = BROWSER_HEADERS + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to url
                        )
                    ).parsedSafe<EpisodesListResponse>()

                    res?.episodes?.forEach { epItem ->
                        val epNumber = epItem.number?.toIntOrNull() ?: 1
                        val epName = epItem.title?.takeIf { it.isNotBlank() } ?: "Episódio $epNumber"
                        val epHref = epItem.url?.takeIf { it.isNotBlank() } ?: url

                        episodes.add(
                            newEpisode(fixUrl(epHref)) {
                                this.name = epName
                                this.season = s
                                this.episode = epNumber
                                this.posterUrl = epItem.thumb?.let { fixUrlNull(it) }
                                this.description = epItem.synopsis
                            }
                        )
                    }
                } catch (_: Exception) {}
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        }
    }

    data class PlayerItem(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("downloadUrl") val downloadUrl: String? = null
    )

    data class PlayerDataResponse(
        @JsonProperty("players") val players: List<PlayerItem>? = null,
        @JsonProperty("servers_dub") val serversDub: String? = null,
        @JsonProperty("servers_leg") val serversLeg: String? = null
    )

    private fun parseQueryPairs(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        val clean = raw.replace("&amp;", "&")
        return clean.split("&").mapNotNull { pair ->
            val idx = pair.indexOf("=")
            if (idx > 0 && idx < pair.length - 1) {
                val key = pair.substring(0, idx).trim().lowercase()
                val value = pair.substring(idx + 1).trim()
                key to value
            } else null
        }
    }

    // v150: Byse SPA ("Byse Frontend" shell vazio) — sem API pública; WebView
    // carrega o embed real e intercepta o .m3u8 montado pelo player Vite.
    private suspend fun resolveByseViaWebView(
        byseUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val intercept = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            val resp = app.get(
                byseUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer),
                interceptor = WebViewResolver(intercept)
            )
            val html = try { resp.text } catch (_: Throwable) { "" }
            val found = mutableSetOf<String>()
            intercept.findAll(html).forEach { found.add(it.value) }
            intercept.find(resp.url)?.let { found.add(it.value) }
            for (m3u8 in found) {
                if (m3u8.contains("googlesyndication") || m3u8.contains("morphify")) continue
                callback.invoke(
                    newExtractorLink(
                        source = "Byse",
                        name = "Byse (HLS)",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = byseUrl
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to byseUrl)
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            found.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var videoId = Regex("""-(\d+)/?$""").find(data)?.groupValues?.getOrNull(1)

        if (videoId.isNullOrBlank()) {
            try {
                val doc = app.get(data, headers = BROWSER_HEADERS).document
                videoId = doc.selectFirst("#view")?.attr("data-video-id")
            } catch (_: Exception) {}
        }

        if (videoId.isNullOrBlank()) return false

        // v150: retry — playerData responde 200 porém vazio em ~1/6 (CF cache)
        var response: PlayerDataResponse? = null
        repeat(6) {
            try {
                val r = app.get(
                    "$mainUrl/index.php?app=videobox&module=video&controller=view&do=playerData&id=$videoId",
                    headers = BROWSER_HEADERS + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to data
                    )
                ).parsedSafe<PlayerDataResponse>()
                if (!r?.players.isNullOrEmpty()) {
                    response = r
                    return@repeat
                }
            } catch (_: Exception) {}
        }
        val resp = response ?: return false

        val playersMap = response.players?.associate { 
            (it.label?.lowercase() ?: "") to (it.url ?: "")
        } ?: emptyMap()

        val serverList = mutableListOf<Pair<String, String>>()
        response.serversDub?.let { raw ->
            parseQueryPairs(raw).forEach { (srv, token) ->
                serverList.add("Dublado ($srv)" to resolveEmbedUrl(srv, token, playersMap))
            }
        }
        response.serversLeg?.let { raw ->
            parseQueryPairs(raw).forEach { (srv, token) ->
                serverList.add("Legendado ($srv)" to resolveEmbedUrl(srv, token, playersMap))
            }
        }

        var loaded = false
        for ((_, embedUrl) in serverList) {
            if (embedUrl.isBlank()) continue
            try {
                // v150: Byse shell vazio via HTTP — WebView primeiro
                if (embedUrl.contains("bysebuho.com") || embedUrl.contains("byse")) {
                    if (resolveByseViaWebView(embedUrl, data, callback)) {
                        loaded = true
                        continue
                    }
                }
                if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                    loaded = true
                }
            } catch (_: Exception) {}
        }

        return loaded
    }

    private fun resolveEmbedUrl(server: String, token: String, playersMap: Map<String, String>): String {
        val base = playersMap[server.lowercase()] ?: playersMap[server] ?: when (server.lowercase()) {
            "mixdrop" -> "https://mixdrop.top/e/"
            "streamtape" -> "https://streamtape.com/e/"
            "doodstream" -> "https://playmogo.com/e/"
            "byse" -> "https://bysebuho.com/e/"
            else -> "https://$server.com/e/"
        }
        return base + token
    }
}
