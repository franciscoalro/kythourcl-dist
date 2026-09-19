package com.AnimesOrion

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

// AnimesOrion v1 — plugin p/ https://animesorion.cc/ (WordPress + fork DooPlay
// "animes-orion-seo-mobile-v2.0.4", RankMath, SEM WAF — HTTP puro, sem WebView).
// Cadeia de vídeo provada no lab (Uzaki-chan S2E1 + Psycho-Pass Providence):
//   ep/filme -> li.dooplay_player_option[data-post][data-nume][data-type]
//     -> POST admin-ajax.php?action=doo_player_ajax -> {embed_url: myembed}
//     -> myembed.biz/{serie|filme}/{tmdb}[/{S}/{E}] -> iframe playerflix.ink
//     -> GET playerflix.ink/inc/Ajax.php?type={tv|movie}&id={tmdb}[&season&episode]
//        (X-Requested-With) -> {data:{options:[{embed,label,lang}]}}
//     -> Blogger video.g?token= via batchexecute WcwnYd -> googlevideo
//        (itags 18/22; código copiado do AnimeFire, comprovado no app).
//     -> superflixapi.* exige verificação: pulado.
class AnimesOrion : MainAPI() {
    override var mainUrl = "https://animesorion.cc"
    override var name = "AnimesOrion"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private val BROWSER_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        private const val PLAYERFLIX = "https://playerflix.ink"
        private fun pageHeaders(referer: String) = BROWSER_HEADERS + mapOf("Referer" to referer)
    }

    data class DooPlayerAjaxResp(
        @JsonProperty("embed_url") val embedUrl: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class PfApiResp(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("data") val data: PfApiData? = null
    )

    data class PfApiData(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("bgImage") val bgImage: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("season_count") val seasonCount: Int? = null,
        @JsonProperty("options") val options: List<PfOption>? = null,
        @JsonProperty("next_episode") val nextEpisode: PfEpRef? = null
    )

    data class PfOption(
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("label") val label: String? = null
    )

    data class PfEpRef(
        @JsonProperty("season_number") val season: Int? = null,
        @JsonProperty("episode_number") val episode: Int? = null,
        @JsonProperty("title") val title: String? = null
    )

    override val mainPage = mainPageOf(
        "$mainUrl/animes/" to "Animes",
        "$mainUrl/filmes/" to "Filmes de Anime",
        "$mainUrl/episodios/" to "Novos Episódios",
        "$mainUrl/temporadas/" to "Temporadas",
        "$mainUrl/tag/animes-dublado/" to "Dublados",
        "$mainUrl/tag/ovas-e-especiais/" to "OVAs e Especiais"
    )

    // ---------- cards ----------
    private fun Element.extractCard(): SearchResponse? {
        val titleEl = this.selectFirst(".data h3 a") ?: return null
        val href = titleEl.attr("href").takeIf { it.isNotBlank() } ?: return null
        val absUrl = fixUrl(href)
        val title = titleEl.text().trim().takeIf { it.isNotBlank() } ?: return null

        val img = this.selectFirst(".poster img")
        val poster = img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { fixUrlNull(it) }

        val low = absUrl.lowercase()
        // episódio: thumb paisagem + span "S2 E1" + span.serie
        if (low.contains("/episodios/")) {
            val serie = this.selectFirst(".data span.serie")?.text()?.trim()
            val epTag = this.select(".data span").map { it.text().trim() }
                .firstOrNull { Regex("""S\d+\s*E\d+""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            val se = Regex("""S(\d+)\s*E(\d+)""", RegexOption.IGNORE_CASE).find(epTag.orEmpty())
            val s = se?.groupValues?.getOrNull(1)?.toIntOrNull()
            val e = se?.groupValues?.getOrNull(2)?.toIntOrNull()
            // episódio avulso da home/busca: abre como anime da série quando der,
            // senão entrega o episódio direto (payload carrega S/E)
            val serieLink = this.select("a[href]").map { it.attr("href") }
                .firstOrNull { it.contains("/animes/") }?.let { fixUrl(it) }
            if (serieLink != null && s != null && e != null) {
                return newTvSeriesSearchResponse(title, serieLink, TvType.Anime) {
                    this.posterUrl = poster
                }
            }
            return newAnimeSearchResponse(
                (serie?.takeIf { it.isNotBlank() }?.let { "$it — " } ?: "") + title,
                absUrl, TvType.Anime
            ) {
                this.posterUrl = poster
            }
        }
        // temporada: overlay span.a/b/c + h3 "Temporada N"
        if (low.contains("/temporadas/")) {
            val serieName = this.selectFirst(".season_m .c, .season_m span.c")?.text()?.trim()
                ?: this.selectFirst(".data span.serie")?.text()?.trim()
            val seasonNum = this.selectFirst(".season_m .b, .season_m span.b")?.text()?.trim()?.toIntOrNull()
                ?: Regex("""Temporada\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val label = if (!serieName.isNullOrBlank() && seasonNum != null) "$serieName (T$seasonNum)"
            else if (!serieName.isNullOrBlank()) "$serieName — $title"
            else title
            return newTvSeriesSearchResponse(label, absUrl, TvType.Anime) {
                this.posterUrl = poster
            }
        }
        // filme
        if (low.contains("/filmes/")) {
            val isOva = title.contains("ova", ignoreCase = true) || title.contains("especial", ignoreCase = true)
            return newAnimeSearchResponse(title, absUrl, if (isOva) TvType.OVA else TvType.AnimeMovie) {
                this.posterUrl = poster
            }
        }
        // anime (default, inclui /animes/)
        return newAnimeSearchResponse(title, absUrl, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val cards = doc.select("article.item")
        val out = cards.mapNotNull {
            try { it.extractCard() } catch (_: Exception) { null }
        }.distinctBy { it.url }
        if (out.isNotEmpty()) return out
        // busca WP (livesearch): .result-item > article (thumbnail + details)
        val results = doc.select(".result-item article, .search-results article").mapNotNull { art ->
            try {
                val titleEl = art.selectFirst(".details .title a, .title a") ?: return@mapNotNull null
                val href = titleEl.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val absUrl = fixUrl(href)
                val title = titleEl.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val poster = art.selectFirst(".image img, img")?.attr("src")
                    ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { fixUrlNull(it) }
                val low = absUrl.lowercase()
                when {
                    low.contains("/filmes/") -> newAnimeSearchResponse(title, absUrl, TvType.AnimeMovie) { this.posterUrl = poster }
                    else -> newAnimeSearchResponse(title, absUrl, TvType.Anime) { this.posterUrl = poster }
                }
            } catch (_: Exception) { null }
        }.distinctBy { it.url }
        if (results.isNotEmpty()) return results
        // fallback: âncoras de detalhe com imagem
        return doc.select("a[href]").mapNotNull { a ->
            val h = a.attr("href")
            if (h.isBlank()) return@mapNotNull null
            val low = h.lowercase()
            val looksDetail = low.contains("/animes/") || low.contains("/filmes/")
                || low.contains("/episodios/") || low.contains("/temporadas/")
            if (!looksDetail || a.selectFirst("img") == null) return@mapNotNull null
            try {
                val wrap = a.parents().select("article").firstOrNull() ?: a
                wrap.extractCard()
            } catch (_: Exception) { null }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.removeSuffix("/")
        val url = if (page <= 1) request.data
        else if (base.contains("/page/")) base.replace(Regex("""/page/\d+"""), "/page/$page") + "/"
        else "$base/page/$page/"
        return try {
            val doc = app.get(url, headers = BROWSER_HEADERS, timeout = 30).document
            val items = parseCards(doc)
            val hasNext = doc.selectFirst(".pagination a.next, a.page-numbers.next, .nav-links a.next, .pagination a[href*=page]") != null
            newHomePageResponse(request.name, items, hasNext = hasNext)
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        return try {
            val doc = app.get(
                "$mainUrl/?s=${URLEncoder.encode(q, "UTF-8")}",
                headers = BROWSER_HEADERS, timeout = 30
            ).document
            parseCards(doc)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---------- detalhe ----------
    private fun parseMeta(doc: Document): Quad {
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()
            ?: "Animes Orion"
        val poster = doc.selectFirst(".poster img, .sheader .poster img")?.attr("src")
            ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { fixUrlNull(it) }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.selectFirst(".description p, .sinopse, .wp-content p")?.text()?.trim()
        val extra = doc.select(".sheader .extra, .extra, .dt_mainmeta").text()
        val year = Regex("""\b(19|20)\d{2}\b""").find("$title $extra")?.value?.toIntOrNull()
        val genres = doc.select("a[href*='/genero/']").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
        return Quad(title, poster, plot, Triple(year, genres, extra))
    }

    private data class Quad(
        val title: String,
        val poster: String?,
        val plot: String?,
        val rest: Triple<Int?, List<String>, String>
    )

    private fun episodeFromLi(li: Element, defaultSeason: Int): Episode? {
        val a = li.selectFirst(".episodiotitle a") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        val name = a.text().trim().ifBlank { "Episódio" }
        // numerando "S - E" (ex "2 - 1"); fallback slug {serie}-{S}x{E}
        val numTxt = li.selectFirst(".numerando")?.text()?.trim().orEmpty()
        val nums = Regex("""\d+""").findAll(numTxt).mapNotNull { it.value.toIntOrNull() }.toList()
        var s = nums.getOrNull(0)
        var e = nums.getOrNull(1)
        if (s == null || e == null) {
            val m = Regex("""-(\d+)x(\d+)/?$""").find(href)
            s = s ?: m?.groupValues?.getOrNull(1)?.toIntOrNull()
            e = e ?: m?.groupValues?.getOrNull(2)?.toIntOrNull()
        }
        val thumb = li.selectFirst(".imagen img")?.attr("src")
            ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { fixUrlNull(it) }
        val date = li.selectFirst(".episodiotitle .date")?.text()?.trim()
        return newEpisode(fixUrl(href)) {
            this.name = name
            this.season = s ?: defaultSeason
            this.episode = e ?: 1
            this.posterUrl = thumb
            this.description = date
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = BROWSER_HEADERS, timeout = 30).document
        val (title, poster, plot, rest) = parseMeta(doc)
        val (year, genres, _) = rest
        val low = url.lowercase()

        // FILME: player direto na página (myembed.biz/filme/{imdb})
        if (low.contains("/filmes/")) {
            val isOva = title.contains("ova", ignoreCase = true) || title.contains("especial", ignoreCase = true)
            return newMovieLoadResponse(title, url, if (isOva) TvType.OVA else TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                if (genres.isNotEmpty()) this.tags = genres
            }
        }

        // TEMPORADA: .se-c > .se-q (Temporada N) + .se-a > ul.episodios
        val episodes = mutableListOf<Episode>()
        doc.select("#seasons .se-c").forEach { se ->
            val seasonNum = se.selectFirst(".se-q .se-t, .se-q .title")?.text()?.let {
                Regex("""\d+""").find(it)?.value?.toIntOrNull()
            } ?: Regex("""Temporada\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(se.selectFirst(".se-q")?.text().orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 1
            se.select(".se-a ul.episodios li").forEach { li ->
                try { episodeFromLi(li, seasonNum)?.let { episodes.add(it) } } catch (_: Exception) {}
            }
        }
        // ANIME (todas as temporadas, mesma estrutura, várias .se-c)
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                if (genres.isNotEmpty()) this.tags = genres
            }
        }

        // EPISÓDIO avulso (aberto direto): entrega série de 1 episódio
        if (low.contains("/episodios/")) {
            val serieName = doc.selectFirst(".pag_episodes a[title]")?.attr("title")?.trim()
                ?: title.substringBefore(":").trim()
            val m = Regex("""-(\d+)x(\d+)/?$""").find(url)
            val s = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val e = m?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1
            val ep = newEpisode(url) {
                this.name = title
                this.season = s
                this.episode = e
                this.posterUrl = poster
                this.description = plot
            }
            return newTvSeriesLoadResponse(serieName.ifBlank { title }, url, TvType.Anime, listOf(ep)) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                if (genres.isNotEmpty()) this.tags = genres
            }
        }

        // fallback: trata como anime sem episódios listados
        return newTvSeriesLoadResponse(title, url, TvType.Anime, emptyList()) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            if (genres.isNotEmpty()) this.tags = genres
        }
    }

    // ---------- loadLinks: ajax -> myembed -> playerflix API -> Blogger ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val doc = try {
            app.get(data, headers = pageHeaders(mainUrl), timeout = 30).document
        } catch (_: Exception) {
            return false
        }

        // 1. options DooPlay (DUBLADO primeiro) + iframes myembed diretos
        data class Opt(val post: String?, val nume: String?, val type: String?, val label: String, val direct: String?)
        val opts = mutableListOf<Opt>()
        doc.select("#playeroptionsul li, #playeroptions li, .dooplay_player_option").forEach { li ->
            val post = li.attr("data-post").takeIf { it.isNotBlank() }
            val nume = li.attr("data-nume").takeIf { it.isNotBlank() }
            val type = li.attr("data-type").takeIf { it.isNotBlank() }
            val embed = li.attr("data-embed").takeIf { it.isNotBlank() }
            if (post.isNullOrBlank() && embed.isNullOrBlank()) return@forEach
            val label = li.selectFirst(".title")?.text()?.trim()
                ?: li.text().trim().ifBlank { "HD" }
            opts.add(Opt(post, nume, type, label, embed))
        }
        doc.select("iframe.metaframe[src], .pframe iframe[src], iframe[src*=myembed], iframe[src*=playerflix]").forEach { fr ->
            val src = fr.attr("src")
            if (src.isNotBlank()) opts.add(Opt(null, null, null, "Player", fixUrl(src)))
        }
        val ordered = opts.sortedBy {
            when {
                it.label.contains("dub", ignoreCase = true) -> 0
                it.label.contains("leg", ignoreCase = true) -> 1
                else -> 2
            }
        }

        // 2. resolve cada option até myembed/playerflix URL
        val embedUrls = mutableListOf<Pair<String, String>>() // url + label
        for (o in ordered) {
            if (!o.direct.isNullOrBlank()) {
                embedUrls.add(fixUrl(o.direct) to o.label)
                continue
            }
            if (!o.post.isNullOrBlank()) {
                // retry p/ CF cache vazio (padrão DooPlay)
                repeat(5) {
                    try {
                        val ajax = app.post(
                            "$mainUrl/wp-admin/admin-ajax.php",
                            headers = pageHeaders(data) + mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                            ),
                            data = mapOf(
                                "action" to "doo_player_ajax",
                                "post" to o.post,
                                "nume" to (o.nume ?: "1"),
                                "type" to (o.type ?: if (data.contains("/filmes/")) "movie" else "tv")
                            ),
                            timeout = 30
                        ).parsedSafe<DooPlayerAjaxResp>()
                        val u = ajax?.embedUrl?.takeIf { it.isNotBlank() }
                        if (u != null) {
                            embedUrls.add(fixUrl(u) to o.label)
                            return@repeat
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 3. myembed -> playerflix -> API options -> Blogger/superflix
        for ((embedUrl, label) in embedUrls.distinctBy { it.first }) {
            try {
                if (resolveEmbedChain(embedUrl, data, label, subtitleCallback, callback)) found = true
            } catch (_: Exception) {}
        }
        return found
    }

    private suspend fun resolveEmbedChain(
        embedUrl: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        // direto m3u8/mp4
        if (embedUrl.contains(".m3u8") || embedUrl.contains(".mp4")) {
            emitDirect(embedUrl, referer, label, callback)
            return true
        }
        // myembed shell -> iframe playerflix
        var target = embedUrl
        if (target.contains("myembed.biz")) {
            try {
                val html = app.get(target, headers = pageHeaders(referer), timeout = 30).text
                val pf = Regex("""<iframe[^>]*src=["'](https?://playerflix\.ink/[^"']+)["']""").find(html)
                    ?.groupValues?.getOrNull(1)
                    ?: Regex("""(https?://playerflix\.ink/[a-z0-9/]+)""").find(html)?.groupValues?.getOrNull(1)
                if (!pf.isNullOrBlank()) target = pf
            } catch (_: Exception) {}
        }
        // playerflix -> API JSON (tv e movie)
        if (target.contains("playerflix.ink")) {
            try {
                // /serie/{tmdb}/{S}/{E} ou /filme/{imdb}
                val serieM = Regex("""/serie/([^/]+)/(\d+)/(\d+)""").find(target)
                val filmeM = Regex("""/filme/([^/?#]+)""").find(target)
                val apiUrl = when {
                    serieM != null -> "$PLAYERFLIX/inc/Ajax.php?type=tv&id=${serieM.groupValues[1]}&season=${serieM.groupValues[2]}&episode=${serieM.groupValues[3]}"
                    filmeM != null -> "$PLAYERFLIX/inc/Ajax.php?type=movie&id=${filmeM.groupValues[1]}"
                    else -> null
                }
                if (apiUrl != null) {
                    val api = app.get(
                        apiUrl,
                        headers = pageHeaders(target) + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json"
                        ),
                        timeout = 30
                    ).parsedSafe<PfApiResp>()
                    val options = api?.data?.options.orEmpty()
                    // Blogger primeiro (resolve certo); Premium/superflix por último (verificação)
                    val sorted = options.sortedBy {
                        when {
                            it.embed?.contains("blogger.com", ignoreCase = true) == true -> 0
                            it.embed?.contains("superflix", ignoreCase = true) == true -> 9
                            else -> 1
                        }
                    }
                    for (opt in sorted) {
                        val eu = opt.embed?.takeIf { it.isNotBlank() } ?: continue
                        val tag = listOf(label, opt.label ?: "").filter { it.isNotBlank() }.joinToString(" ")
                        try {
                            if (eu.contains("blogger.com")) {
                                val tok = Regex("""token=([A-Za-z0-9_-]+)""").find(eu)?.groupValues?.getOrNull(1)
                                if (!tok.isNullOrBlank() && extractBlogger(tok, tag, callback)) {
                                    found = true
                                    continue
                                }
                            }
                            if (eu.contains("superflix")) continue // verificação JS, pula
                            if (loadExtractor(eu, target, subtitleCallback, callback)) found = true
                        } catch (_: Exception) {}
                    }
                    if (found) return true
                }
            } catch (_: Exception) {}
            // fallback: loadExtractor genérico no playerflix
            try {
                if (loadExtractor(target, referer, subtitleCallback, callback)) return true
            } catch (_: Exception) {}
        }
        // último recurso genérico
        try {
            if (loadExtractor(target, referer, subtitleCallback, callback)) found = true
        } catch (_: Exception) {}
        return found
    }

    private suspend fun emitDirect(url: String, referer: String, label: String, callback: (ExtractorLink) -> Unit) {
        val isM3u8 = url.contains(".m3u8")
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name $label",
                url = url,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
                this.quality = Qualities.P1080.value
            }
        )
    }

    // Blogger batchexecute (cópia do AnimeFire — comprovado no app; itags 18/22
    // provados ao vivo p/ token do Orion em 19/09/2026).
    private suspend fun extractBlogger(
        token: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (token.isBlank()) return false
        var extracted = false
        try {
            val rpcUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute?rpcids=WcwnYd&source-path=%2Fvideo.g&hl=pt-BR"
            val reqPayload = """[[["WcwnYd","[\"$token\",null,0]",null,"generic"]]]"""
            val text = app.post(
                rpcUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://www.blogger.com/",
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8"
                ),
                data = mapOf("f.req" to reqPayload),
                timeout = 30
            ).text

            val jsonArrayMatch = Regex("""\[\["wrb\.fr","WcwnYd","(.*?)",null,null,null,"generic"\]\]""").find(text)
            val rawData = jsonArrayMatch?.groupValues?.getOrNull(1) ?: text
            val unescaped = rawData
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\u003d", "=")
                .replace("\\u0026", "&")

            val streamRegex = Regex("""\["(https:[^"]+googlevideo\.com[^"]+)",\s*\[(\d+)\]\]""")
            for (m in streamRegex.findAll(unescaped)) {
                var streamUrl = m.groupValues[1]
                val itag = m.groupValues[2].toIntOrNull() ?: 22
                if (streamUrl.contains("\\u")) {
                    streamUrl = streamUrl.replace("\\u003d", "=").replace("\\u0026", "&")
                }
                val (qualityInt, qlabel) = when (itag) {
                    37 -> Qualities.P1080.value to "1080p (FHD)"
                    22 -> Qualities.P720.value to "720p (HD)"
                    18 -> Qualities.P360.value to "360p (SD)"
                    else -> Qualities.P720.value to "HD"
                }
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Blogger ($label $qlabel)",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "*/*")
                        this.quality = qualityInt
                    }
                )
                extracted = true
            }
        } catch (_: Exception) {}
        return extracted
    }
}
