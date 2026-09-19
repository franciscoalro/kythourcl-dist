package com.Pobreflix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

// Pobreflix v150 — reescrita total p/ o NOVO site (profilmecloud.net,
// WordPress + tema DooPlay). O domínio antigo (pobreflixtv.futbol, VideoBox
// com API index.php?app=videobox) morreu; o novo é scraping HTML puro:
//   home/catálogo: article.item(.movies|.tvshows) c/ .poster img + .data h3
//   busca WP nativa: /?s={termo} (SearchAction Yoast)
//   filme: /filme/{slug}/ ou /filmes/{slug}/ — player #playeroptions li
//   série: /serie/{slug}/ ou /series/{slug}/ — #seasons + episódios
//   player: iframe[data-src|src] + li[data-embed] + data-post/data-nume
//           via POST admin-ajax.php?action=doo_player_ajax (+retry 6x p/ CF)
//   embeds: direto m3u8/mp4 > Filemoon/Voe/Dood/Byse-Streamwish (loadExtractor)
//           > VidSrc gate HTTP-puro (ChaCha20+wasm cf. EmbedPlay v9/CineVision)
//           > Abyss real (WebView 25s, ÚNICO WebView, último recurso)
// WAF: CF Managed Challenge + Turnstile invisível; lab datacenter (Hetzner)
// toma 403/hard-block — no aparelho do usuário (IP residencial) o challenge
// passa; isBlockedResponse detecta e evita cachear challenge como vazio.
class Pobreflix : MainAPI() {
    override var mainUrl = "https://profilmecloud.net"
    override var name = "Pobreflix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private val BROWSER_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        private fun pageHeaders(referer: String) = BROWSER_HEADERS + mapOf("Referer" to referer)

        fun isBlockedResponse(code: Int, body: String): Boolean {
            if (code == 403 || code == 503) return true
            return body.contains("Just a moment", true) ||
                body.contains("Attention Required", true) ||
                body.contains("challenge-platform", true) ||
                body.contains("cf-error-details", true)
        }
    }

    data class DooPlayerAjaxResp(
        @JsonProperty("embed_url") val embedUrl: String? = null,
        @JsonProperty("embed") val embed: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("type") val type: String? = null
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

    // v150: rotas confirmáveis em IP residencial; singular/plural p/ robustez
    override val mainPage = mainPageOf(
        "$mainUrl/filmes/" to "Filmes",
        "$mainUrl/series/" to "Séries",
        "$mainUrl/animes/" to "Animes",
        "$mainUrl/lancamentos/" to "Lançamentos"
    )

    // ---------- cards (12 campos de metadados) ----------
    private data class CardMeta(
        val url: String,
        val title: String,
        val poster: String?,
        val year: Int?,
        val rating: String?,
        val quality: String?,
        val audio: String?,
        val genres: List<String>,
        val isTv: Boolean
    )

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""^Assistir\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Online.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+em HD.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Dublado.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Legendado.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*Pobreflix.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(\d{4}\).*"""), "")
            .trim()
    }

    private fun Element.extractCard(): CardMeta? {
        // âncora principal (fora genre/genero/page/author/tag)
        val link = this.select("a[href]").firstOrNull {
            val h = it.attr("href")
            h.isNotBlank() && !h.contains("/genre/") && !h.contains("/genero/")
                && !h.contains("/page/") && !h.contains("/author/") && !h.contains("/tag/")
                && !h.startsWith("#") && !h.startsWith("javascript:")
        } ?: (if (this.tagName() == "a") this else return null)
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val absUrl = fixUrl(href)

        val img = this.selectFirst(".poster img, .aa-img img, img[src*='uploads'], img")
            ?: link.selectFirst("img")
        val poster = img?.let {
            it.attr("data-src").takeIf { s -> s.isNotBlank() }
                ?: it.attr("data-original").takeIf { s -> s.isNotBlank() }
                ?: it.attr("data-lazy-src").takeIf { s -> s.isNotBlank() }
                ?: it.attr("src").takeIf { s -> s.isNotBlank() && !s.startsWith("data:") }
        }?.let { fixUrlNull(it) }

        val rawTitle = this.selectFirst(".data h3, .data h3 a, .entry-title, h3, h2, .name")?.text()?.trim()
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: link.text().trim()
        if (rawTitle.isBlank()) return null
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null

        val metaText = this.select(".data span, .metadata, .meta, .poster .rating, .voteaverage").eachText().joinToString(" ")
        val year = Regex("""\b(19|20)\d{2}\b""").find("$rawTitle $metaText")?.value?.toIntOrNull()
        val rating = Regex("""(\d[.,]\d|\d\s?/\s?10)""").find(metaText)?.value
            ?: this.selectFirst(".poster .rating, .rating, .imdb")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val quality = this.selectFirst(".poster .quality, .mepo, .quality, .aa-quality")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
        val audioBadge = listOf("dublado", "legendado").firstOrNull {
            "$rawTitle $metaText ${quality.orEmpty()}".contains(it, ignoreCase = true)
        }?.let { if (it.startsWith("dub")) "Dublado" else "Legendado" }
        val genres = this.select(".data .genres a, a[href*='/genre/'], a[href*='/genero/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }

        val low = absUrl.lowercase()
        val isTv = low.contains("/serie") || low.contains("/tvshow") || low.contains("/episodio")
            || low.contains("/episode") || low.contains("/anime")
            || this.hasClass("tvshows")
            || this.classNames().any { it.contains("tvshow", ignoreCase = true) }

        return CardMeta(absUrl, title, poster, year, rating, quality, audioBadge, genres, isTv)
    }

    private fun CardMeta.toResponse(): SearchResponse {
        return if (isTv) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = this@toResponse.poster
                this.year = this@toResponse.year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = this@toResponse.poster
                this.year = this@toResponse.year
            }
        }
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val cards = doc.select("article.item, article.movies, article.tvshows, .items article, #archive-content article, .aa-tb article")
        val fromCards = cards.mapNotNull {
            try { it.extractCard() } catch (_: Exception) { null }
        }
        if (fromCards.isNotEmpty()) return fromCards.map { it.toResponse() }.distinctBy { it.url }
        // fallback genérico: âncoras de detalhe com imagem
        return doc.select("a[href]").mapNotNull { a ->
            val h = a.attr("href")
            if (h.isBlank() || h.contains("/genre/") || h.contains("/genero/") || h.contains("/page/")
                || h.contains("/author/") || h.contains("/tag/") || h.startsWith("#")) return@mapNotNull null
            val low = h.lowercase()
            val looksDetail = low.contains("/filme") || low.contains("/serie") || low.contains("/movie")
                || low.contains("/tvshow") || low.contains("/episodio") || low.contains("/anime")
            if (!looksDetail || a.selectFirst("img") == null) return@mapNotNull null
            try { a.extractCard() } catch (_: Exception) { null }
        }.distinctBy { it.url }.map { it.toResponse() }.distinctBy { it.url }
    }

    // GET com retry p/ CF cache vazio (padrão legado: 200 vazio ~1/6) + detector WAF
    private suspend fun fetchDoc(url: String, referer: String = "$mainUrl/", maxTries: Int = 6): Document? {
        repeat(maxTries) {
            try {
                val res = app.get(url, headers = pageHeaders(referer), timeout = 30)
                val body = try { res.text } catch (_: Throwable) { "" }
                if (isBlockedResponse(res.code, body)) return null
                if (body.length > 8000) return res.document
            } catch (_: Exception) {}
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.removeSuffix("/")
        // v150: singular/plural — tenta a rota pedida, depois a variante
        val candidates = mutableListOf<String>()
        if (page <= 1) {
            candidates.add(request.data)
        } else if (base.contains("/page/")) {
            candidates.add(base.replace(Regex("""/page/\d+"""), "/page/$page") + "/")
        } else {
            candidates.add("$base/page/$page/")
            candidates.add(request.data + (if (request.data.contains("?")) "&" else "?") + "paged=$page")
        }
        if (base.endsWith("/filmes")) candidates.add(base.replace("/filmes", "/filme") + if (page > 1) "/page/$page/" else "/")
        if (base.endsWith("/filme")) candidates.add(base.replace("/filme", "/filmes") + if (page > 1) "/page/$page/" else "/")
        if (base.endsWith("/series")) candidates.add(base.replace("/series", "/serie") + if (page > 1) "/page/$page/" else "/")
        if (base.endsWith("/animes")) candidates.add(base.replace("/animes", "/anime") + if (page > 1) "/page/$page/" else "/")

        for (u in candidates.distinct()) {
            try {
                val doc = fetchDoc(u) ?: continue
                val items = parseCards(doc)
                if (items.isEmpty()) continue
                val hasNext = doc.selectFirst(".pagination a.next, a.page-numbers.next, a.next.page-numbers, .nav-links a.next") != null
                return newHomePageResponse(request.name, items, hasNext = hasNext)
            } catch (_: Exception) { continue }
        }
        return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    // ---------- busca WP nativa /?s= ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val enc = URLEncoder.encode(q, "UTF-8")
        val tries = listOf(
            "$mainUrl/?s=$enc",
            "$mainUrl/?s=$enc&post_type=post"
        )
        for (u in tries) {
            try {
                val doc = fetchDoc(u) ?: continue
                val items = parseCards(doc)
                if (items.isNotEmpty()) return items
            } catch (_: Exception) { continue }
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ---------- detalhe (12+ metadados) ----------
    private data class DetailMeta(
        val title: String,
        val poster: String?,
        val backdrop: String?,
        val plot: String?,
        val year: Int?,
        val genres: List<String>,
        val rating: String?,
        val durationMin: Int?,
        val country: String?,
        val cast: List<String>,
        val quality: String?,
        val audios: List<String>
    )

    private fun parseDetail(doc: Document): DetailMeta {
        val rawTitle = doc.selectFirst(".sheader .data h1, .sheader h1, h1.entry-title, h1")
            ?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Pobreflix"
        val title = cleanTitle(rawTitle)
        val poster = doc.selectFirst(".sheader .poster img, .poster img")?.let {
            it.attr("data-src").takeIf { s -> s.isNotBlank() }
                ?: it.attr("data-original").takeIf { s -> s.isNotBlank() }
                ?: it.attr("src").takeIf { s -> s.isNotBlank() && !s.startsWith("data:") }
        }?.let { fixUrlNull(it) }
        val backdrop = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }
            ?: doc.selectFirst(".backdrop img, .aa-backdrop img")?.attr("src")?.let { fixUrlNull(it) }
        val plot = doc.selectFirst(".sheader .description p, .sheader .description, .wp-content .description p, .description p, .sinopse p, .sinopse")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.selectFirst(".wp-content p")?.text()?.trim()
        val extra = doc.select(".sheader .data .extra, .sheader .extra, .extra").text()
        val year = Regex("""\b(19|20)\d{2}\b""").find("$rawTitle $extra")?.value?.toIntOrNull()
        val genres = doc.select(".sgeneros a, .sheader .data .sgeneros a, a[href*='/genre/'], a[href*='/genero/'], .genres a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val rating = doc.selectFirst(".starstruck-rating .dt_rating_vgs, .imdb, .voteaverage, .poster .rating, .srating, .rating")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val durationMin = Regex("""(\d+)\s?min""", RegexOption.IGNORE_CASE).find(extra)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: doc.selectFirst(".extra span.runtime, .duracao, .runtime")?.text()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
        val country = doc.selectFirst(".extra span.country, .pais, .country")?.text()?.trim()
        val cast = doc.select(".cast a, .elenco a, a[href*='/actor/'], a[href*='/elenco/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct().take(12)
        val quality = doc.selectFirst(".poster .quality, .mepo, .quality")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val audios = mutableListOf<String>()
        val playerText = doc.select("#playeroptions, .player_nav, .dooplay_player").text()
        if (playerText.contains("dub", ignoreCase = true)) audios.add("Dublado")
        if (playerText.contains("leg", ignoreCase = true)) audios.add("Legendado")
        return DetailMeta(title, poster, backdrop, plot, year, genres, rating, durationMin, country, cast, quality, audios)
    }

    private fun isSeriesPage(doc: Document, url: String): Boolean {
        val low = url.lowercase()
        return low.contains("/serie") || low.contains("/tvshow") || low.contains("/anime")
            || doc.selectFirst("#seasons, .vbEpisodes, .aa-tbs, select#season, ul.smenu") != null
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDoc(url, mainUrl) ?: throw ErrorLoadingException("Pobreflix bloqueado/offline (WAF)")
        val m = parseDetail(doc)
        val tags = mutableListOf<String>()
        tags.addAll(m.genres)
        m.quality?.let { tags.add(it) }
        tags.addAll(m.audios)
        m.country?.let { tags.add(it) }
        m.rating?.let { tags.add("★ $it") }

        if (isSeriesPage(doc, url)) {
            val episodes = parseEpisodes(doc, url, m)
            return newTvSeriesLoadResponse(m.title, url, TvType.TvSeries, episodes) {
                this.posterUrl = m.poster ?: m.backdrop
                this.backgroundPosterUrl = m.backdrop
                this.plot = m.plot
                this.year = m.year
                if (tags.isNotEmpty()) this.tags = tags
                if (m.cast.isNotEmpty()) this.actors = m.cast.map { ActorData(Actor(it)) }
                m.durationMin?.let { this.duration = it }
            }
        }
        return newMovieLoadResponse(m.title, url, TvType.Movie, url) {
            this.posterUrl = m.poster ?: m.backdrop
            this.backgroundPosterUrl = m.backdrop
            this.plot = m.plot
            this.year = m.year
            if (tags.isNotEmpty()) this.tags = tags
            if (m.cast.isNotEmpty()) this.actors = m.cast.map { ActorData(Actor(it)) }
            m.durationMin?.let { this.duration = it }
        }
    }

    // ---------- episódios (3 vias: HTML links, data-embed, AJAX doo_episodes) ----------
    private suspend fun parseEpisodes(doc: Document, url: String, meta: DetailMeta): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // via A: links próprios de episódio no HTML
        val seasonCtx = mutableMapOf<String, Int>()
        doc.select("#seasons [data-season], .aa-tbs [data-season]").forEach { tab ->
            tab.attr("data-season").toIntOrNull()?.let { s ->
                tab.parents().select("li, div").forEach { p -> seasonCtx[p.hashCode().toString()] = s }
            }
        }
        doc.select("#seasons a[href], .aa-tbs a[href], #episodes a[href], ul.episodes-list a[href], ul.episodios a[href]").forEach { a ->
            val h = a.attr("href")
            if (h.isBlank()) return@forEach
            val low = h.lowercase()
            if (!(low.contains("/episodio") || low.contains("/episode"))) return@forEach
            val container = a.parents().select("li, article").firstOrNull()
            val numTxt = container?.selectFirst(".num-epi, .e-num, .episode-num, .numerando, .epst")?.text()
                ?: a.text()
            val epNum = Regex("""\d+""").findAll(numTxt).mapNotNull { it.value.toIntOrNull() }.lastOrNull() ?: 0
            val epName = container?.selectFirst(".entry-title, h2, h3, .ep-title, .episodiotitle")?.text()?.trim()
                ?: a.attr("title").takeIf { it.isNotBlank() }
                ?: a.text().trim().ifBlank { "Episódio $epNum" }
            val thumb = container?.selectFirst("img, .imagen img")?.let {
                it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src")
            }?.let { fixUrlNull(it) }
            val desc = container?.selectFirst("p.overview, .overview")?.text()?.trim()
            val seasonGuess = Regex("""(\d+)\s?[xX×-]\s?\d+""").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""/s(\d+)e\d+""", RegexOption.IGNORE_CASE).find(h)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: 1
            episodes.add(
                newEpisode(fixUrl(h)) {
                    this.name = epName
                    this.season = seasonGuess
                    this.episode = epNum
                    this.posterUrl = thumb ?: meta.poster
                    this.description = desc
                }
            )
        }

        // via B: temporadas em tabs + AJAX doo_episodes
        if (episodes.isEmpty()) {
            val seasons = doc.select("[data-season]").mapNotNull { it.attr("data-season").toIntOrNull() }.distinct()
                .ifEmpty {
                    doc.select("select#season option, ul.smenu li a").mapNotNull {
                        it.attr("value").ifBlank { it.attr("data-season") }.toIntOrNull()
                            ?: Regex("""\d+""").find(it.text())?.value?.toIntOrNull()
                    }.distinct()
                }
            val postId = Regex("""["']?(?:post_?id)["']?\s*[:=]\s*["']?(\d+)""").find(doc.html())?.groupValues?.getOrNull(1)
                ?: doc.selectFirst("[data-post]")?.attr("data-post")
            if (seasons.isNotEmpty() && !postId.isNullOrBlank()) {
                val nonce = Regex("""["']?nonce["']?\s*[:=]\s*["']([a-zA-Z0-9]+)["']""").find(doc.html())?.groupValues?.getOrNull(1).orEmpty()
                for (s in seasons.take(30)) {
                    try {
                        val ajaxHtml = dooAjaxHtml(
                            mapOf("action" to "doo_episodes", "post" to postId, "season" to s.toString(), "nonce" to nonce), url
                        )
                        if (ajaxHtml.isNullOrBlank()) continue
                        val frag = Jsoup.parse(ajaxHtml, mainUrl)
                        frag.select("a[href]").forEachIndexed { idx, a ->
                            val h = a.attr("href")
                            if (h.isBlank()) return@forEachIndexed
                            val epName = a.attr("title").takeIf { it.isNotBlank() }
                                ?: a.selectFirst(".entry-title")?.text()?.trim()
                                ?: a.text().trim().ifBlank { "Episódio ${idx + 1}" }
                            val thumb = a.selectFirst("img")?.let {
                                it.attr("data-src").takeIf { s2 -> s2.isNotBlank() } ?: it.attr("src")
                            }?.let { fixUrlNull(it) }
                            episodes.add(
                                newEpisode(fixUrl(h)) {
                                    this.name = epName
                                    this.season = s
                                    this.episode = idx + 1
                                    this.posterUrl = thumb ?: meta.poster
                                }
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // via C: data-embed direto nos itens (payload inline resolvido no loadLinks)
        if (episodes.isEmpty()) {
            doc.select("#playeroptions li[data-post][data-nume], li[data-embed], a[data-embed]").forEachIndexed { idx, el ->
                val embed = el.attr("data-embed").takeIf { it.isNotBlank() }.orEmpty()
                val post = el.attr("data-post").takeIf { it.isNotBlank() }.orEmpty()
                val nume = el.attr("data-nume").toIntOrNull() ?: (idx + 1)
                val label = el.text().trim().ifBlank { "Episódio $nume" }
                val payload = "pbembed:$embed|post:$post|nume:$nume|referer:$url"
                episodes.add(
                    newEpisode(payload) {
                        this.name = label
                        this.season = 1
                        this.episode = nume
                        this.posterUrl = meta.poster
                    }
                )
            }
        }
        return episodes.distinctBy { it.data }
    }

    private suspend fun dooAjaxHtml(data: Map<String, String>, referer: String): String? {
        repeat(4) {
            try {
                val resp = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    headers = pageHeaders(referer) + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                    ),
                    data = data,
                    timeout = 30
                ).text
                if (isBlockedResponse(200, resp)) return null
                tryParseJson<DooPlayerAjaxResp>(resp)?.let { j ->
                    val u = j.embedUrl ?: j.embed ?: j.url
                    if (!u.isNullOrBlank()) return u
                }
                if (resp.contains("<a") || resp.contains("<li") || resp.contains("embed")) return resp
                if (resp.length > 200) return@repeat
            } catch (_: Exception) {}
        }
        return null
    }

    // ---------- loadLinks: DUB+LEG, data-embed + iframe + ajax ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // payload inline de episódio (via C)
        if (data.startsWith("pbembed:")) {
            val parts = data.removePrefix("pbembed:").split("|").associate {
                val i = it.indexOf(":")
                if (i > 0) it.substring(0, i) to it.substring(i + 1) else it to ""
            }
            val direct = parts["embed"].orEmpty()
            val referer = parts["referer"]?.takeIf { it.isNotBlank() } ?: mainUrl
            if (direct.isNotBlank()) {
                if (resolveEmbed(direct, referer, "Pobreflix", subtitleCallback, callback)) return true
            }
            val post = parts["post"].orEmpty()
            if (post.isNotBlank()) {
                val ajax = dooAjaxHtml(
                    mapOf("action" to "doo_player_ajax", "post" to post, "nume" to (parts["nume"] ?: "1")),
                    referer
                )
                if (!ajax.isNullOrBlank() && ajax.startsWith("http")) {
                    if (resolveEmbed(ajax, referer, "Pobreflix", subtitleCallback, callback)) return true
                }
            }
        }

        val pageUrl = if (data.startsWith("http") || data.startsWith("pbembed:")) {
            if (data.startsWith("pbembed:")) return false
            data
        } else "$mainUrl$data"
        val doc = try {
            fetchDoc(pageUrl, mainUrl) ?: return false
        } catch (_: Exception) {
            return false
        }

        data class Source(val embed: String?, val post: String?, val nume: String?, val type: String?, val label: String, val lang: String)
        val sources = mutableListOf<Source>()
        // todas as abas Dublado/Legendado
        doc.select("#playeroptionsul li, #playeroptions li, .dooplay_player_option, li.dooplay_player_option").forEach { li ->
            val embed = li.attr("data-embed").takeIf { it.isNotBlank() }
                ?: li.attr("data-source").takeIf { it.isNotBlank() }
                ?: li.attr("data-url").takeIf { it.isNotBlank() }
            val post = li.attr("data-post").takeIf { it.isNotBlank() }
            val nume = li.attr("data-nume").takeIf { it.isNotBlank() }
            val type = li.attr("data-type").takeIf { it.isNotBlank() }
            if (embed.isNullOrBlank() && post.isNullOrBlank()) return@forEach
            val label = li.text().trim().ifBlank { type ?: "Player" }
            val lang = when {
                label.contains("dub", ignoreCase = true) -> "Dublado"
                label.contains("leg", ignoreCase = true) -> "Legendado"
                else -> ""
            }
            sources.add(Source(embed, post, nume, type, label, lang))
        }
        // iframes (lazy data-src + src real) + JS embutido
        doc.select(".dooplay_player iframe, #dooplay_player_response iframe, #player iframe, iframe.metaframe, iframe[data-src], article iframe, iframe[src]").forEach { fr ->
            val src = fr.attr("data-src").takeIf { it.isNotBlank() }
                ?: fr.attr("src").takeIf { it.isNotBlank() && !it.startsWith("about:") }
            if (src.isNullOrBlank() || src.contains("googlesyndication")) return@forEach
            sources.add(Source(fixUrl(src), null, null, "iframe", "Player", ""))
        }
        Regex("""(?:file|source|embed_?url)\s*[:=]\s*["'](https?://[^"']+)["']""").findAll(doc.html()).forEach {
            sources.add(Source(it.groupValues[1], null, null, "js", "Player", ""))
        }
        // blogger token (padrão TopAnimes/DooPlay BR)
        Regex("""blogger\.com/video\.g\?token=([A-Za-z0-9_-]+)""").findAll(doc.html()).forEach {
            sources.add(Source("https://www.blogger.com/video.g?token=${it.groupValues[1]}", null, null, "blogger", "Blogger", ""))
        }

        // resolve data-post via admin-ajax (retry p/ CF cache vazio)
        val nonce = Regex("""["']?nonce["']?\s*[:=]\s*["']([a-zA-Z0-9]+)["']""").find(doc.html())?.groupValues?.getOrNull(1).orEmpty()
        val resolved = mutableListOf<Triple<String, String, String>>()
        for (s in sources) {
            if (!s.embed.isNullOrBlank()) resolved.add(Triple(s.embed, s.label, s.lang))
            else if (!s.post.isNullOrBlank()) {
                val ajax = dooAjaxHtml(
                    mapOf(
                        "action" to "doo_player_ajax",
                        "post" to s.post,
                        "nume" to (s.nume ?: "1"),
                        "type" to (s.type ?: "movie"),
                        "nonce" to nonce
                    ), pageUrl
                )
                if (!ajax.isNullOrBlank()) resolved.add(Triple(ajax, s.label, s.lang))
            }
        }

        // ordem: Dublado primeiro, HTTP-puro antes de Abyss/WebView
        val ordered = resolved.distinctBy { it.first }.sortedWith(
            compareBy<Triple<String, String, String>> {
                when {
                    it.third.equals("Dublado", true) -> 0
                    it.third.equals("Legendado", true) -> 1
                    it.second.contains("dub", ignoreCase = true) -> 0
                    it.second.contains("leg", ignoreCase = true) -> 1
                    else -> 2
                }
            }.thenBy {
                when {
                    it.first.contains("abyss", ignoreCase = true) -> 9
                    it.first.contains("vidsrc", ignoreCase = true) -> 5
                    else -> 1
                }
            }
        )

        var foundAny = false
        for ((embedUrl, label, lang) in ordered) {
            try {
                val tag = listOf(lang, label).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Pobreflix" }
                if (resolveEmbed(embedUrl, pageUrl, tag, subtitleCallback, callback)) foundAny = true
            } catch (_: Exception) {}
        }
        return foundAny
    }

    // ---------- resolveEmbed: direto > VidSrc gate > Byse/Streamwish > loadExtractor > Abyss WebView ----------
    private suspend fun resolveEmbed(
        embedUrl: String,
        referer: String,
        tag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var url = embedUrl.replace("&amp;", "&").trim()
        if (url.isBlank() || url.startsWith("about:") || url.contains("googlesyndication")) return false
        // iframe embrulhado em HTML do ajax
        if (url.contains("<iframe") || url.contains("<a")) {
            val frag = try { Jsoup.parse(url) } catch (_: Exception) { return false }
            url = frag.selectFirst("iframe[src], iframe[data-src], a[href]")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("href") }
            } ?: return false
            if (url.isBlank()) return false
        }
        if (!url.startsWith("http")) url = fixUrl(url)

        // 1. direto .m3u8/.mp4
        if (url.contains(".m3u8") || url.contains(".mp4")) {
            val isM3u8 = url.contains(".m3u8")
            callback.invoke(
                newExtractorLink(
                    source = "Pobreflix",
                    name = "Pobreflix $tag",
                    url = url,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        // 2. VidSrc gate HTTP-puro (ChaCha20+wasm, SEM WebView — cf. EmbedPlay v9)
        if (url.contains("vidsrc", ignoreCase = true)) {
            try {
                if (resolveVidSrcGate(url, referer, callback)) return true
            } catch (_: Exception) {}
        }

        // 3. Byse -> espelho Streamwish (extrator nativo)
        if (url.contains("byse", ignoreCase = true)) {
            val code = Regex("""/e/([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.getOrNull(1)
            if (!code.isNullOrBlank()) {
                try {
                    if (loadExtractor("https://streamwish.to/e/$code", referer, subtitleCallback, callback)) return true
                } catch (_: Exception) {}
            }
        }

        // 4. extratores nativos (Filemoon/Voe/Dood/Mixdrop/Streamtape/Streamwish/Blogger)
        try {
            if (loadExtractor(url, referer, subtitleCallback, callback)) return true
        } catch (_: Exception) {}

        // 5. Abyss real via WebView (ÚNICO WebView do fluxo, 25s)
        if (url.contains("abyss", ignoreCase = true)) {
            try {
                val real = resolveAbyssShell(url) ?: url
                if (resolveViaWebView(real, referer, tag, callback)) return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun resolveAbyssShell(playerUrl: String): String? {
        return try {
            if (playerUrl.contains("abysscdn.com") || playerUrl.contains("abyssplayer.com")) return playerUrl
            val slug = Regex("""[?&]v=([a-zA-Z0-9_-]+)""").find(playerUrl)?.groupValues?.getOrNull(1)
                ?: Regex("""/([a-zA-Z0-9_-]{6,})(?:[/?#]|$)""").find(playerUrl)?.groupValues?.getOrNull(1)
                ?: return null
            "https://player.abyssplayer.com/$slug"
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveViaWebView(
        playerUrl: String,
        referer: String,
        tag: String,
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
                        source = "Pobreflix",
                        name = "Pobreflix $tag (HLS)",
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

    // ---------- VidSrc gate (cópia do fluxo EmbedPlay v9/CineVision v155) ----------
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
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to startUrl, "Accept" to "application/json"),
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
                    "https://data.vidsrc.sh/api.php?type=movie&imdb=$imdb&stream_urls"
                } else return false

            val streamJson = app.get(
                streamApi,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to playerUrl, "Accept" to "application/json"),
                timeout = 30
            ).text
            val resp = tryParseJson<VsStreamResp>(streamJson)
            val rawUrls = resp?.data?.streamUrls
            val urls: List<String> = when (rawUrls) {
                is List<*> -> rawUrls.filterIsInstance<String>()
                    .flatMap { Regex(""""?(https?://[^"\s,]+\.m3u8[^"\s,]*)""").findAll(it).map { m -> m.groupValues[1] } }
                    .ifEmpty { rawUrls.filterIsInstance<String>() }
                    .distinct()
                is String -> decryptVsStreamUrls(rawUrls, resp.vs, playerUrl)
                else -> {
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
                        source = "Pobreflix",
                        name = "Pobreflix VidSrc (HLS)",
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

    // decrypt ChaCha20 RFC 8439 do stream_urls — chave = seg[0] XOR seg[3200] do wasm
    private suspend fun decryptVsStreamUrls(encB64: String, vs: VsDecryptor?, referer: String): List<String> {
        return try {
            val wasmBytes: ByteArray = if (!vs?.wasm.isNullOrBlank()) {
                android.util.Base64.decode(vs!!.wasm, android.util.Base64.DEFAULT)
            } else {
                val wasmUrl = vs?.wasmUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
                app.get(wasmUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer), timeout = 30).body.bytes()
            }
            val key = vsWasmKey(wasmBytes) ?: return emptyList()
            val enc = try {
                android.util.Base64.decode(encB64.trim(), android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                return emptyList()
            }
            if (enc.size <= 12) return emptyList()
            val pt = chacha20(key, enc.copyOfRange(0, 12), enc.copyOfRange(12, enc.size)) ?: return emptyList()
            pt.toString(Charsets.UTF_8).split("\n").map { it.trim() }.filter { it.contains(".m3u8") }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

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
                        q++
                        var off = 0
                        var so = 0
                        q++
                        while (true) {
                            val m = wasm[q++].toInt() and 0xFF
                            off = off or ((m and 0x7F) shl so)
                            so += 7
                            if (m and 0x80 == 0) break
                        }
                        q++
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
}
