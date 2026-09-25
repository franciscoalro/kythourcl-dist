package com.VeloPlayTV

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class VeloPlayTV : MainAPI() {
    override var name = "Velo Play TV"
    override var mainUrl = "https://fastcdn.bond/velo/tv"
    override var lang = "pt"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
        TvType.Live
    )

    private val userAgent = "Velo Play/1.2.0 (Linux; Android 11; redroid11_x86_64) com.global.veloplaytv/10200"
    private val posterHeadersMap = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "https://fastcdn.bond/"
    )

    private var authToken: String? = null

    private fun parseSeasonNumber(title: String, fallback: Int): Int {
        val match = Regex("""(?i)(?:temp(?:orada)?|season|s)\.?\s*(\d+)""").find(title)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: fallback
    }

    private fun cleanSeriesTitle(title: String): String {
        val cleaned = title.replace(Regex("""(?i)\s+(?:temp(?:orada)?|season|s)\.?\s*\d+.*$"""), "").trim()
        return if (cleaned.isNotBlank()) cleaned else title
    }

    private fun isLaterSeason(title: String): Boolean {
        val match = Regex("""(?i)(?:temp(?:orada)?|season|s)\.?\s*(\d+)""").find(title)
        val seasonNum = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return false
        return seasonNum > 1
    }

    private suspend fun getAuthHeaders(): Map<String, String> {
        var token = authToken
        if (token.isNullOrBlank()) {
            token = performLogin()
        }
        return mapOf(
            "User-Agent" to userAgent,
            "Accept-Language" to "pt-BR",
            "Authorization" to (if (!token.isNullOrBlank()) "Bearer $token" else "")
        )
    }

    private suspend fun performLogin(): String? {
        try {
            val payload = JSONObject().apply {
                put("canal_code", "75qC")
                put("device_id", "ee612a7d-a900-4860-91ad-5a31468cc312")
                put("password", "pbkdf2_sha256\$1200000\$TiUO0Gu2Ttcb45XR4xPvmC\$Ry1LAe8SayrzAhKgyp0PIMSp9HDnjXbQ2JRC2OFpL+0=")
                put("username", "sZJ3Z9pEyN")
            }.toString()

            val resp = app.post(
                "$mainUrl/user/v1/login",
                requestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaType()),
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Content-Type" to "application/json; charset=UTF-8",
                    "Accept-Language" to "pt-BR"
                ),
                timeout = 15
            )
            if (resp.isSuccessful) {
                val json = JSONObject(resp.text)
                val access = json.optJSONObject("token")?.optString("access")
                if (!access.isNullOrBlank()) {
                    authToken = access
                    return access
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private suspend fun safeGet(url: String): String? {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl${if (url.startsWith("/")) "" else "/"}$url"
        return try {
            val headers = getAuthHeaders()
            var resp = app.get(fullUrl, headers = headers, timeout = 15)
            if (resp.code == 401) {
                performLogin()
                val newHeaders = getAuthHeaders()
                resp = app.get(fullUrl, headers = newHeaders, timeout = 15)
            }
            if (resp.isSuccessful) resp.text else null
        } catch (_: Exception) {
            null
        }
    }

    // Queries de catálogo para cada seção expandida com paginação infinita
    private val sectionQueries = mapOf(
        "animes" to listOf(
            "dragon", "naruto", "piece", "titan", "jujutsu", "demon", "bleach", "death",
            "hunter", "hero", "sword", "ghoul", "pokemon", "leveling", "chainsaw", "spy",
            "clover", "fairy", "alchemist", "punch", "boruto", "haikyu", "avatar", "evangelion",
            "overlord", "rezero", "shield", "baki", "dungeon", "fate", "danganronpa", "inuyasha"
        ),
        "movies_action" to listOf(
            "vingadores", "batman", "aranha", "velozes", "wick", "missao", "avatar", "matrix",
            "gladiador", "rambo", "exterminador", "transformers", "top gun", "jurassic", "mad max",
            "godzilla", "kong", "homem de ferro", "thor", "capitao america", "pantera negra"
        ),
        "movies_horror" to listOf(
            "invocacao", "sobrenatural", "panico", "jogos mortais", "halloween", "it",
            "freira", "anabelle", "exorcista", "hereditario", "evil dead", "chucky",
            "silencio dos inocentes", "sexta feira 13", "pesadelo", "massacre", "sorria"
        ),
        "movies_comedy" to listOf(
            "gente grande", "se beber", "branquelas", "ted", "deadpool", "todo mundo em panico",
            "shrek", "vizinhos", "superbad", "click", "mentiroso", "loucademia", "american pie"
        ),
        "movies_scifi" to listOf(
            "interestelar", "senhor dos aneis", "harry potter", "star wars", "duna",
            "matrix", "inception", "origem", "blade runner", "hobbit", "avatar", "alien"
        ),
        "series_populares" to listOf(
            "stranger", "game of thrones", "breaking bad", "walking dead", "the boys",
            "dragon", "vikings", "prison break", "supernatural", "last of us", "wandinha",
            "peaky", "fallout", "dexter", "greys", "friends", "office", "dark", "euphoria", "yellowstone"
        ),
        "kids" to listOf(
            "disney", "pixar", "patrulha canina", "peppa", "galinha pintadinha", "toy story",
            "shrek", "divertida mente", "kung fu panda", "meu malvado", "minions", "frozen",
            "carros", "bob esponja", "masha", "scooby", "trolls", "madagascar", "hotel transilvania"
        )
    )

    private val channelFilters = mapOf(
        "live_abertos" to listOf("globo", "sbt", "record", "band", "cultura", "redetv", "gazeta", "vida", "aparecida", "evangelizar", "cancoes", "futura", "tv brasil"),
        "live_esportes" to listOf("sportv", "espn", "premiere", "fox sports", "bandsports", "conmebol", "combate", "ufc", "futebol", "nosso futebol", "dazn", "f1", "formula"),
        "live_filmes" to listOf("telecine", "hbo", "max", "paramount", "universal", "warner", "sony", "axn", "tnt", "space", "cinemax", "megapix", "studio universal", "tcm", "a&e", "amc", "eurochannel", "usa"),
        "live_infantil" to listOf("cartoon", "disney", "gloob", "discovery kids", "nickelodeon", "nick", "tooncrown", "boomerang", "animax", "baby tv", "nat geo kids", "tv rá tim bum"),
        "live_variedades" to listOf("discovery", "history", "national geographic", "nat geo", "animal planet", "h&h", "tlc", "food", "home & health", "hgtv", "multishow", "gnt", "viva", "e!", "comedy central", "curta"),
        "live_noticias" to listOf("globonews", "cnn", "jovem pan", "bandnews", "record news", "bloomberg", "bbc", "al jazeera", "dw", "euronews"),
        "live_4k" to listOf("4k", "uhd")
    )

    override val mainPage = mainPageOf(
        "rec:T2xE" to "⭐ Destaques & Recomendações",
        "cat:animes" to "⛩️ Animes (Completo)",
        "cat:movies_action" to "💥 Filmes: Ação & Aventura",
        "cat:series_populares" to "📺 Séries Populares",
        "cat:movies_horror" to "👻 Filmes: Terror & Suspense",
        "cat:movies_comedy" to "😂 Filmes: Comédia",
        "cat:movies_scifi" to "🚀 Filmes: Ficção & Fantasia",
        "cat:kids" to "🧸 Infantil & Desenhos",
        "live:all" to "📡 Todos os Canais Ao Vivo (480+)",
        "live:live_esportes" to "⚽ Canais: Esportes & Futebol",
        "live:live_filmes" to "🎬 Canais: Filmes & Séries",
        "live:live_abertos" to "📺 Canais: TV Aberta",
        "live:live_infantil" to "🎈 Canais: Infantis",
        "live:live_variedades" to "🌍 Canais: Documentários & Variedades",
        "live:live_noticias" to "📰 Canais: Notícias",
        "live:live_4k" to "🌟 Canais: 4K & UHD"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val itemsList = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()

        // 1. Canais Ao Vivo (com suporte a filtro por gênero e exibição completa)
        if (path.startsWith("live:")) {
            val filterKey = path.removePrefix("live:")
            val raw = safeGet("/stella/v1/channels")
            if (!raw.isNullOrBlank()) {
                try {
                    val json = JSONObject(raw)
                    val channels = json.optJSONArray("channels") ?: JSONArray()
                    val filterWords = channelFilters[filterKey]

                    for (i in 0 until channels.length()) {
                        val ch = channels.optJSONObject(i) ?: continue
                        val id = ch.optString("_id")
                        if (id.isBlank() || seenIds.contains(id)) continue

                        val name = ch.optString("display_name", "Canal Ao Vivo")
                        if (filterWords != null && filterWords.none { name.contains(it, ignoreCase = true) }) {
                            continue
                        }

                        seenIds.add(id)
                        val logo = ch.optString("logo").ifBlank { ch.optString("poster") }
                        
                        var streamLink = "$mainUrl/stella/v1/channel/$id/play"
                        val streams = ch.optJSONArray("streams")
                        if (streams != null && streams.length() > 0) {
                            val firstStream = streams.optJSONObject(0)
                            val directUrl = firstStream?.optString("url")
                            if (!directUrl.isNullOrBlank()) {
                                streamLink = directUrl
                            }
                        }

                        itemsList.add(
                            newLiveSearchResponse(name, streamLink, TvType.Live) {
                                this.posterUrl = logo
                                this.posterHeaders = posterHeadersMap
                            }
                        )
                    }
                } catch (_: Exception) {}
            }

            return newHomePageResponse(
                request.name,
                itemsList,
                hasNext = false
            )
        }

        // 2. Destaques da API Oficial
        if (path.startsWith("rec:")) {
            val catId = path.removePrefix("rec:")
            val raw = safeGet("/mar/v1/category/$catId/recommendations")
            if (!raw.isNullOrBlank()) {
                try {
                    val json = JSONObject(raw)
                    val slots = json.optJSONArray("slots") ?: JSONArray()
                    for (s in 0 until slots.length()) {
                        val slot = slots.optJSONObject(s) ?: continue
                        val items = slot.optJSONArray("items") ?: JSONArray()
                        for (it in 0 until items.length()) {
                            val item = items.optJSONObject(it) ?: continue
                            val id = item.optString("refer")
                            val title = item.optString("title")
                            val pic = item.optString("pic")
                            if (id.isBlank() || title.isBlank() || seenIds.contains(id)) continue

                            seenIds.add(id)
                            val isSeries = title.contains("Temp.", ignoreCase = true)
                            val type = if (isSeries) TvType.TvSeries else TvType.Movie

                            if (type == TvType.TvSeries) {
                                itemsList.add(
                                    newTvSeriesSearchResponse(title, "$mainUrl/mar/v1/asset/$id/detail", TvType.TvSeries) {
                                        this.posterUrl = pic
                                        this.posterHeaders = posterHeadersMap
                                    }
                                )
                            } else {
                                itemsList.add(
                                    newMovieSearchResponse(title, "$mainUrl/mar/v1/asset/$id/detail", TvType.Movie) {
                                        this.posterUrl = pic
                                        this.posterHeaders = posterHeadersMap
                                    }
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            return newHomePageResponse(
                request.name,
                itemsList,
                hasNext = false
            )
        }

        // 3. Categorias Expandidas com Paginação Infinita via Scroll
        if (path.startsWith("cat:")) {
            val catKey = path.removePrefix("cat:")
            val queries = sectionQueries[catKey] ?: emptyList()
            
            // Cada página busca 4 termos diferentes da lista
            val pageSize = 4
            val startIndex = (page - 1) * pageSize
            val hasNext = startIndex + pageSize < queries.size

            val currentQueries = if (startIndex < queries.size) {
                queries.subList(startIndex, minOf(startIndex + pageSize, queries.size))
            } else {
                emptyList()
            }

            for (q in currentQueries) {
                val encodedQ = try { URLEncoder.encode(q, "UTF-8") } catch (_: Exception) { q }
                val raw = safeGet("/mar/v1/asset/search?q=$encodedQ&page=1&page_size=25") ?: continue
                try {
                    val json = JSONObject(raw)
                    val assets = json.optJSONArray("assets") ?: JSONArray()
                    for (i in 0 until assets.length()) {
                        val item = assets.optJSONObject(i) ?: continue
                        val id = item.optString("_id")
                        val title = item.optString("title")
                        if (id.isBlank() || title.isBlank() || seenIds.contains(id)) continue

                        seenIds.add(id)
                        val itemType = item.optString("_type")
                        val seriesStatus = item.optString("series_status")
                        val isSeries = catKey.contains("series") || itemType.equals("SEASON", ignoreCase = true) || seriesStatus.isNotBlank() || title.contains("Temp.", ignoreCase = true)

                        // Evita poluir a Home com temporadas repetidas (Temp.2, Temp.3...)
                        if (isSeries && isLaterSeason(title)) continue

                        var poster: String? = null
                        val postersArr = item.optJSONArray("posters")
                        if (postersArr != null && postersArr.length() > 0) {
                            poster = postersArr.optString(0)
                        }

                        val tvType = when {
                            catKey == "animes" -> TvType.Anime
                            catKey == "kids" -> TvType.Cartoon
                            isSeries -> TvType.TvSeries
                            else -> TvType.Movie
                        }

                        if (isSeries) {
                            val cleanTitle = cleanSeriesTitle(title)
                            itemsList.add(
                                newTvSeriesSearchResponse(cleanTitle, "$mainUrl/mar/v1/asset/$id/detail", tvType) {
                                    this.posterUrl = poster
                                    this.posterHeaders = posterHeadersMap
                                }
                            )
                        } else {
                            itemsList.add(
                                newMovieSearchResponse(title, "$mainUrl/mar/v1/asset/$id/detail", tvType) {
                                    this.posterUrl = poster
                                    this.posterHeaders = posterHeadersMap
                                }
                            )
                        }
                    }
                } catch (_: Exception) {}
            }

            return newHomePageResponse(
                request.name,
                itemsList,
                hasNext = hasNext
            )
        }

        return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val searchItems = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()

        val encodedQuery = try {
            URLEncoder.encode(trimmed, "UTF-8")
        } catch (_: Exception) {
            trimmed
        }

        val path = "/mar/v1/asset/search?q=$encodedQuery&page=1&page_size=40"
        val raw = safeGet(path) ?: return emptyList()

        try {
            val json = JSONObject(raw)
            val assets = json.optJSONArray("assets") ?: JSONArray()

            for (i in 0 until assets.length()) {
                val item = assets.optJSONObject(i) ?: continue
                val id = item.optString("_id")
                val title = item.optString("title")
                if (id.isBlank() || title.isBlank() || seenIds.contains(id)) continue

                seenIds.add(id)
                val itemType = item.optString("_type")
                val seriesStatus = item.optString("series_status")
                val isSeries = itemType.equals("SEASON", ignoreCase = true) || seriesStatus.isNotBlank() || title.contains("Temp.", ignoreCase = true)

                // Evita duplicatas de temporadas posteriores nos resultados de busca
                if (isSeries && isLaterSeason(title)) continue

                var poster: String? = null
                val postersArr = item.optJSONArray("posters")
                if (postersArr != null && postersArr.length() > 0) {
                    poster = postersArr.optString(0)
                }

                if (isSeries) {
                    val cleanTitle = cleanSeriesTitle(title)
                    searchItems.add(
                        newTvSeriesSearchResponse(cleanTitle, "$mainUrl/mar/v1/asset/$id/detail", TvType.TvSeries) {
                            this.posterUrl = poster
                            this.posterHeaders = posterHeadersMap
                        }
                    )
                } else {
                    searchItems.add(
                        newMovieSearchResponse(title, "$mainUrl/mar/v1/asset/$id/detail", TvType.Movie) {
                            this.posterUrl = poster
                            this.posterHeaders = posterHeadersMap
                        }
                    )
                }
            }
        } catch (_: Exception) {}

        return searchItems
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("stella") || url.contains("channel") || url.contains(".m3u8")) {
            return newLiveStreamLoadResponse("Canal Ao Vivo", url, url)
        }

        val raw = safeGet(url) ?: throw ErrorLoadingException("Não foi possível carregar detalhes do item")
        val json = JSONObject(raw)
        val asset = json.optJSONObject("asset") ?: throw ErrorLoadingException("Formato de resposta inválido")

        val id = asset.optString("_id")
        val title = asset.optString("title", "Sem Título")
        val plot = asset.optString("description")
        val releaseAt = asset.optString("release_at")
        val year = releaseAt.take(4).toIntOrNull()
        val assetType = asset.optString("_type")

        val tags = mutableListOf<String>()
        val tagsArr = asset.optJSONArray("tags")
        if (tagsArr != null) {
            for (i in 0 until tagsArr.length()) {
                val t = tagsArr.optString(i)
                if (t.isNotBlank()) tags.add(t)
            }
        }

        var posterUrl: String? = null
        val imagesArr = asset.optJSONArray("images")
        if (imagesArr != null) {
            for (i in 0 until imagesArr.length()) {
                val img = imagesArr.optJSONObject(i) ?: continue
                if (img.optString("type") == "icon") {
                    posterUrl = img.optString("url")
                    break
                }
            }
            if (posterUrl.isNullOrBlank()) {
                for (i in 0 until imagesArr.length()) {
                    val img = imagesArr.optJSONObject(i) ?: continue
                    if (img.optString("type") == "poster") {
                        posterUrl = img.optString("url")
                        break
                    }
                }
            }
        }

        val scoreVal = asset.optDouble("score", 0.0)
        val isSeries = assetType.equals("SEASON", ignoreCase = true) || title.contains("Temp.", ignoreCase = true)

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val brothersArr = asset.optJSONArray("brothers")

            if (brothersArr != null && brothersArr.length() > 0) {
                // Múltiplas temporadas vinculadas
                for (b in 0 until brothersArr.length()) {
                    val brother = brothersArr.optJSONObject(b) ?: continue
                    val bId = brother.optString("_id")
                    val bTitle = brother.optString("title", "")
                    if (bId.isBlank()) continue

                    val seasonNum = parseSeasonNumber(bTitle, b + 1)
                    val childrenRaw = safeGet("/mar/v1/asset/$bId/children")
                    if (!childrenRaw.isNullOrBlank()) {
                        try {
                            val childrenJson = JSONObject(childrenRaw)
                            val childrenArr = childrenJson.optJSONArray("items") ?: childrenJson.optJSONArray("children") ?: JSONArray()
                            for (c in 0 until childrenArr.length()) {
                                val child = childrenArr.optJSONObject(c) ?: continue
                                val epId = child.optString("_id")
                                val epSeq = child.optInt("seq", child.optInt("num", c + 1))
                                val rawTitle = child.optString("title")
                                val epTitle = if (rawTitle.isBlank() || rawTitle.equals("null", ignoreCase = true)) {
                                    "Episódio $epSeq"
                                } else {
                                    rawTitle
                                }

                                episodes.add(
                                    newEpisode("$mainUrl/mar/v1/asset/$epId/playinfo") {
                                        this.name = epTitle
                                        this.season = seasonNum
                                        this.episode = epSeq
                                        this.posterUrl = posterUrl
                                    }
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }
            } else {
                // Temporada única
                val seasonNum = parseSeasonNumber(title, 1)
                val childrenRaw = safeGet("/mar/v1/asset/$id/children")
                if (!childrenRaw.isNullOrBlank()) {
                    try {
                        val childrenJson = JSONObject(childrenRaw)
                        val childrenArr = childrenJson.optJSONArray("items") ?: childrenJson.optJSONArray("children") ?: JSONArray()
                        for (c in 0 until childrenArr.length()) {
                            val child = childrenArr.optJSONObject(c) ?: continue
                            val epId = child.optString("_id")
                            val epSeq = child.optInt("seq", child.optInt("num", c + 1))
                            val rawTitle = child.optString("title")
                            val epTitle = if (rawTitle.isBlank() || rawTitle.equals("null", ignoreCase = true)) {
                                "Episódio $epSeq"
                            } else {
                                rawTitle
                            }

                            episodes.add(
                                newEpisode("$mainUrl/mar/v1/asset/$epId/playinfo") {
                                    this.name = epTitle
                                    this.season = seasonNum
                                    this.episode = epSeq
                                    this.posterUrl = posterUrl
                                }
                            )
                        }
                    } catch (_: Exception) {}
                }
            }

            val seriesTitle = cleanSeriesTitle(title)
            return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterHeadersMap
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = Score.from10(if (scoreVal > 0) scoreVal else null)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/mar/v1/asset/$id/playinfo") {
            this.posterUrl = posterUrl
            this.posterHeaders = posterHeadersMap
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = Score.from10(if (scoreVal > 0) scoreVal else null)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains(".m3u8")) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "Velo Play Ao Vivo",
                    url = data,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://fastcdn.bond/"
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "https://fastcdn.bond/"
                    )
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        val raw = safeGet(data) ?: return false
        val json = JSONObject(raw)
        val infoObj = json.optJSONObject("info") ?: json

        val assetIdRegex = Regex("asset/([^/]+)/playinfo")
        val assetId = assetIdRegex.find(data)?.groupValues?.getOrNull(1)

        val subtitlesArr = infoObj.optJSONArray("subtitles") ?: json.optJSONArray("subtitles")
        if (subtitlesArr != null && subtitlesArr.length() > 0) {
            for (s in 0 until subtitlesArr.length()) {
                val sub = subtitlesArr.optJSONObject(s) ?: continue
                val lang = sub.optString("lang").ifBlank { sub.optString("language", "Português") }
                val subUrl = sub.optString("url")
                if (subUrl.isNotBlank()) {
                    subtitleCallback(SubtitleFile(lang, subUrl))
                }
            }
        } else if (!assetId.isNullOrEmpty()) {
            subtitleCallback(
                SubtitleFile("Português", "https://vidbox.shop/subtitle/$assetId/pt.srt")
            )
            subtitleCallback(
                SubtitleFile("Inglês", "https://vidbox.shop/subtitle/$assetId/en.srt")
            )
        }

        var foundLinks = false
        val streamsArr = infoObj.optJSONArray("play") ?: infoObj.optJSONArray("streams") ?: json.optJSONArray("play") ?: json.optJSONArray("streams")
        if (streamsArr != null && streamsArr.length() > 0) {
            for (st in 0 until streamsArr.length()) {
                val streamObj = streamsArr.optJSONObject(st) ?: continue
                val streamUrl = streamObj.optString("url")
                if (streamUrl.isBlank()) continue

                val resName = streamObj.optString("resolution", "1080p")
                val audioLangs = mutableListOf<String>()
                val audiosArr = streamObj.optJSONArray("audio_langs")
                if (audiosArr != null) {
                    for (a in 0 until audiosArr.length()) {
                        audioLangs.add(audiosArr.optString(a))
                    }
                }
                val audioSuffix = if (audioLangs.isNotEmpty()) " (${audioLangs.joinToString("/")})" else ""

                val qualityVal = when {
                    resName.contains("4k", ignoreCase = true) || resName.contains("2160", ignoreCase = true) -> Qualities.P2160.value
                    resName.contains("1080", ignoreCase = true) -> Qualities.P1080.value
                    resName.contains("720", ignoreCase = true) -> Qualities.P720.value
                    resName.contains("480", ignoreCase = true) -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }

                callback(
                    newExtractorLink(
                        source = name,
                        name = "Velo Play $resName$audioSuffix",
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://fastcdn.bond/"
                        this.headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to "https://fastcdn.bond/"
                        )
                        this.quality = qualityVal
                    }
                )
                foundLinks = true
            }
        }

        if (!foundLinks) {
            val directPlay = infoObj.optString("play_url").ifBlank { infoObj.optString("url").ifBlank { json.optString("play_url").ifBlank { json.optString("url") } } }
            if (directPlay.isNotBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Velo Play Principal",
                        url = directPlay,
                        type = if (directPlay.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://fastcdn.bond/"
                        this.headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to "https://fastcdn.bond/"
                        )
                        this.quality = Qualities.P1080.value
                    }
                )
                foundLinks = true
            }
        }

        return foundLinks
    }
}
