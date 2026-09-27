package com.tomato

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class Tomato : MainAPI() {
    override var mainUrl = "https://prod-api.tomatoanimes.com"
    override var name = "TomatoAnimes"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // token extraído de /data/data/com.tomatos.clientapp/shared_prefs/com.tomatos.clientapp_preferences.xml
    // USER_TOKEN id=4846729 uuid=d489566c-2443-4579-b390-b5b419831090 iat=1790506280
    // válido até exp da policy (~2h por stream) mas JWT não expira rápido; quando expirar refazer login via hCaptcha
    companion object {
        const val BEARER_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpZCI6NDg0NjcyOSwidXVpZCI6ImQ0ODk1NjZjLTI0NDMtNDU3OS1iMzkwLWI1YjQxOTgzMTA5MCIsImlhdCI6MTc5MDUwNjI4MH0.3MP87IJav4bhPJzt5YUUv1mEeOdWp2zxo5_hc6w51YU"
        val API_HEADERS = mapOf(
            "User-Agent" to "tomato-android",
            "Authorization" to "Bearer $BEARER_TOKEN",
            "Accept" to "application/json"
        )
        val SEARCH_HEADERS = mapOf(
            "User-Agent" to "tomato-android",
            "Authorization" to "Bearer $BEARER_TOKEN",
            "Content-Type" to "application/json"
        )
    }

    // ---------- Feed ----------
    // /v2/animes/feed -> {status:true,status_code:4,remote_settings:{},data:[{type:3,title:"Em alta",data:[{anime_id,thumbnail}]},{type:7,title:"Novos episódios",data:[{ep_id,ep_anime_id,anime_name,ep_name}]},...]}
    // Usamos JsonNode para lidar com tipos heterogêneos
    // 2026-09-27: prod-api e edge retornaram 500 global (CloudFront FRA60-P7) -> feed embutido evita catálogo vazio
    private suspend fun fetchFeed(): JsonNode? {
        val mapper = jacksonObjectMapper().apply { configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false) }
        for (host in listOf(mainUrl, "https://edge.betomato.com")) {
            try {
                val res = app.get("$host/v2/animes/feed", headers = API_HEADERS, timeout = 15)
                if (res.code == 200) return mapper.readTree(res.text)
            } catch (_: Exception) {}
        }
        return try { mapper.readTree(TomatoFallback.FEED_JSON) } catch (_: Exception) { null }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/v2/animes/feed" to "Novos episódios",
        "$mainUrl/v2/animes/feed" to "Em alta",
        "$mainUrl/v2/animes/feed" to "Recém adicionados",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Feed não pagina; apenas page==1
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val root = fetchFeed() ?: return newHomePageResponse(request.name, emptyList())
        val data = root.get("data") ?: return newHomePageResponse(request.name, emptyList())
        if (!data.isArray) return newHomePageResponse(request.name, emptyList())

        val lists = mutableListOf<HomePageList>()

        for (section in data) {
            val type = section.get("type")?.asInt() ?: continue
            val title = section.get("title")?.asText() ?: continue
            val arr = section.get("data") ?: continue
            if (!arr.isArray || arr.size() == 0) continue

            // Filtramos por request se mainPageOf foi usado, mas aqui tratamos todos; CloudStream chama uma vez por entry
            // Para compatibilidade mapeamos cada type para uma lista distinta e filtramos pelo título pedido
            // O truque: quando mainPageOf tem duplo entry com mesmo URL, o request.name diferencia
            val wantAll = request.name == "Novos episódios" || request.name == "Em alta" || request.name == "Recém adicionados"
            // Se request.name não bate em título, ainda retornamos vazio para evitar duplicar tudo em cada tab
            // Porém para primeira versão retornamos todas as listas se o nome não casar (compat)
            // vamos construir listas e deixar CloudStream filtrar: retornamos uma lista cujo nome==request.name
            // Então achamos a seção cujo título contém request.name

            // Se estamos em modo "all-in-one" (quando chamado via Home sem request específico), devolvemos tudo
            // Detectamos: request.name nulo? mas aqui sempre tem. Vamos simplesmente construir por título
            val matched = when {
                request.name.equals(title, ignoreCase = true) -> true
                request.name.equals("Novos episódios", ignoreCase = true) && type == 7 -> true
                request.name.equals("Em alta", ignoreCase = true) && type == 3 -> true
                request.name.equals("Recém adicionados", ignoreCase = true) && title.contains("Recém", ignoreCase = true) -> true
                else -> false
            }
            // Se não bate, pula (para não repetir listas em cada aba)
            if (!matched) continue

            val items: List<SearchResponse> = when (type) {
                3 -> { // Em alta: [{anime_id, thumbnail banner}]
                    arr.mapNotNull { n ->
                        val animeId = n.get("anime_id")?.asInt() ?: return@mapNotNull null
                        val thumb = n.get("thumbnail")?.asText()
                            ?: n.get("banner")?.asText()
                        newAnimeSearchResponse(
                            "Anime $animeId",
                            "$mainUrl/anime/$animeId",
                            TvType.Anime
                        ) {
                            this.posterUrl = thumb
                        }
                    }
                }
                5 -> { // Categorias: Recém adicionados, Semanais, Com dublagem, etc.
                    arr.mapNotNull { n ->
                        val animeId = n.get("anime_id")?.asInt() ?: return@mapNotNull null
                        val thumb = n.get("thumbnail")?.asText() ?: n.get("cape")?.asText()
                        newAnimeSearchResponse(
                            "Anime $animeId",
                            "$mainUrl/anime/$animeId",
                            TvType.Anime
                        ) {
                            this.posterUrl = thumb
                        }
                    }
                }
                7 -> { // Novos episódios: [{ep_id,ep_anime_id,anime_name,ep_name,thumbnail}]
                    arr.mapNotNull { n ->
                        val epId = n.get("ep_id")?.asInt() ?: return@mapNotNull null
                        val animeId = n.get("ep_anime_id")?.asInt()
                        val animeName = n.get("anime_name")?.asText() ?: "Anime $animeId"
                        val epName = n.get("ep_name")?.asText() ?: "Episódio $epId"
                        val thumb = n.get("thumbnail")?.asText()
                        // Para não perder ep_id, linkamos direto ao episódio (loadLinks entende ep_id)
                        // load() deve resolver tanto animeId quanto epId; se for epId, retornamos série com um ep
                        // Mas para Home queremos que clique abra o anime; usamos link do anime e poster do ep
                        val url = if (animeId != null) "$mainUrl/anime/$animeId" else "$mainUrl/episode/$epId"
                        newAnimeSearchResponse(
                            "$animeName — $epName",
                            url,
                            TvType.Anime
                        ) {
                            this.posterUrl = thumb
                        }
                    }
                }
                else -> emptyList()
            }

            if (items.isNotEmpty()) {
                lists.add(HomePageList(title, items))
            }
        }

        // Fallback: se nenhuma lista casou (ex: API mudou títulos), retorna todas como uma lista agregada
        if (lists.isEmpty()) {
            val allItems = mutableListOf<SearchResponse>()
            for (section in data) {
                val type = section.get("type")?.asInt() ?: continue
                val arr = section.get("data") ?: continue
                if (!arr.isArray) continue
                when (type) {
                    3,5 -> arr.forEach { n ->
                        val animeId = n.get("anime_id")?.asInt() ?: return@forEach
                        val thumb = n.get("thumbnail")?.asText() ?: n.get("cape")?.asText() ?: n.get("banner")?.asText()
                        allItems.add(newAnimeSearchResponse("Anime $animeId", "$mainUrl/anime/$animeId", TvType.Anime){ this.posterUrl = thumb })
                    }
                    7 -> arr.forEach { n ->
                        val animeId = n.get("ep_anime_id")?.asInt() ?: return@forEach
                        val animeName = n.get("anime_name")?.asText() ?: "Anime $animeId"
                        val epName = n.get("ep_name")?.asText() ?: ""
                        val thumb = n.get("thumbnail")?.asText()
                        allItems.add(newAnimeSearchResponse("$animeName — $epName", "$mainUrl/anime/$animeId", TvType.Anime){ this.posterUrl = thumb })
                    }
                }
            }
            if (allItems.isNotEmpty()) {
                return newHomePageResponse(HomePageList(request.name, allItems.distinctBy { it.url }.take(30)), hasNext = false)
            }
        }

        return newHomePageResponse(lists, hasNext = false)
    }

    // ---------- Search ----------
    // POST /v2/content/search  body {search, content_type:"anime", page, tags:[] } -> {data:{result:[...]} }  (hermes bundle_decompiled.js:304)
    data class SearchReq(
        @JsonProperty("search") val search: String,
        @JsonProperty("content_type") val contentType: String? = "anime",
        @JsonProperty("page") val page: Int = 1,
        @JsonProperty("tags") val tags: List<String> = emptyList()
    )
    data class SearchAnimeItem(
        @JsonProperty("anime_id") val animeId: Int? = null,
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("anime_name") val animeName: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("cape") val cape: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("banner") val banner: String? = null,
        @JsonProperty("poster") val poster: String? = null
    )
    data class SearchRespData(
        @JsonProperty("result") val result: List<SearchAnimeItem>? = null,
        @JsonProperty("data") val data: List<SearchAnimeItem>? = null
    )
    data class SearchRespWrapper(
        @JsonProperty("data") val data: SearchRespData? = null,
        @JsonProperty("result") val result: List<SearchAnimeItem>? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        // 1) Tenta API remota; 2) fallback no feed embutido (tolerante a 500)
        val apiRes = try {
            val body = SearchReq(search = q, contentType = "anime", page = 1, tags = emptyList())
            val res = app.post("$mainUrl/v2/content/search", headers = SEARCH_HEADERS, json = body, timeout = 15)
            if (res.code == 200) {
                val parsed = tryParseJson<SearchRespWrapper>(res.text)
                val lst = parsed?.data?.result ?: parsed?.data?.data ?: parsed?.result ?: emptyList()
                lst.mapNotNull { item ->
                    val animeId = item.animeId ?: item.id ?: return@mapNotNull null
                    val name = item.animeName ?: item.name ?: item.title ?: "Anime $animeId"
                    val poster = item.cape ?: item.thumbnail ?: item.banner ?: item.poster
                    newAnimeSearchResponse(name, "$mainUrl/anime/$animeId", TvType.Anime) { this.posterUrl = poster }
                }
            } else null
        } catch (_: Exception) { null }
        if (!apiRes.isNullOrEmpty()) return apiRes
        // Fallback: busca no feed embutido por anime_name / ep_anime_id
        return try {
            val feed = fetchFeed() ?: return emptyList()
            val data = feed.get("data") ?: return emptyList()
            val out = linkedMapOf<Int, SearchResponse>()
            for (sec in data) {
                val type = sec.get("type")?.asInt() ?: continue
                val arr = sec.get("data") ?: continue
                if (!arr.isArray) continue
                when (type) {
                    7 -> for (n in arr) {
                        val name = n.get("anime_name")?.asText() ?: continue
                        if (!name.contains(q, ignoreCase = true)) continue
                        val animeId = n.get("ep_anime_id")?.asInt() ?: continue
                        if (out.containsKey(animeId)) continue
                        out[animeId] = newAnimeSearchResponse(name, "$mainUrl/anime/$animeId", TvType.Anime) { this.posterUrl = n.get("thumbnail")?.asText() }
                    }
                    3, 5 -> { /* type 3/5 não tem nome — não buscável sem anime_details; ignorado no fallback */ }
                }
            }
            out.values.toList()
        } catch (_: Exception) { emptyList() }
    }

    // ---------- Load ----------
    // GET /v2/anime/{id}  -> {data:{anime_details:{anime_name,anime_description,cape/banner,...}, anime_seasons:[{season_id,season_name}], liked, favorited, comments_count}}
    // depois POST /season/{season_id}/episodes {token,page,order:"asc"} -> {status:true,episodes:13,data:[{ep_id,ep_name,episode_number,thumbnail,dubbed}]}
    data class AnimeDetails(
        @JsonProperty("anime_name") val animeName: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("anime_description") val animeDescription: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("sinopse") val sinopse: String? = null,
        @JsonProperty("cape") val cape: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("banner") val banner: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("categories") val categories: List<String>? = null
    )
    data class AnimeSeason(
        @JsonProperty("season_id") val seasonId: Int,
        @JsonProperty("season_name") val seasonName: String? = null,
        @JsonProperty("name") val name: String? = null
    )
    data class AnimeDataWrapper(
        @JsonProperty("data") val data: AnimeData? = null,
        @JsonProperty("anime_details") val animeDetailsDirect: AnimeDetails? = null
    )
    data class AnimeData(
        @JsonProperty("anime_details") val animeDetails: AnimeDetails? = null,
        @JsonProperty("anime_seasons") val animeSeasons: List<AnimeSeason>? = null,
        @JsonProperty("seasons") val seasons: List<AnimeSeason>? = null,
        @JsonProperty("liked") val liked: Boolean? = null,
        @JsonProperty("favorited") val favorited: Boolean? = null,
        @JsonProperty("comments_count") val commentsCount: Int? = null
    )
    data class SeasonEpisodeItem(
        @JsonProperty("ep_id") val epId: Int? = null,
        @JsonProperty("episode_id") val episodeId: Int? = null,
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("ep_name") val epName: String? = null,
        @JsonProperty("episode_name") val episodeName: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("ep_number") val epNumber: Int? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("thumb") val thumb: String? = null,
        @JsonProperty("dubbed") val dubbed: Boolean? = null
    )
    data class SeasonEpisodesResp(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("data") val data: List<SeasonEpisodeItem>? = null
    )
    // Bundle HBC v90 getSeasonEpisodes: POST /season/{id}/episodes body {page, order} — sem token (bundle_decompiled.js r7['page']=r8; r7['order']=r1)
    data class SeasonReq(
        @JsonProperty("page") val page: Int = 1,
        @JsonProperty("order") val order: String = "asc"
    )

    override suspend fun load(url: String): LoadResponse? {
        // url pode ser "$mainUrl/anime/{id}" ou "$mainUrl/episode/{epId}" (vindo de Novos episódios)
        val animeId = Regex("""/anime/(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(\d+)$""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null

        // Se a URL era de episódio direto, devolvemos série com 1 episódio apontando para stream
        if (url.contains("/episode/")) {
            val epId = Regex("""/episode/(\d+)""").find(url)?.groupValues?.getOrNull(1) ?: animeId.toString()
            // Tenta buscar nome via stream endpoint para título melhor
            var epTitle: String? = null
            var poster: String? = null
            try {
                val s = app.get("$mainUrl/v2/anime/episode/$epId/stream", headers = API_HEADERS).text
                val sj = tryParseJson<StreamResp>(s)
                epTitle = sj?.episodeName
            } catch (_: Exception) {}
            return newAnimeLoadResponse("Episódio $epId", url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, listOf(
                    newEpisode(epId) {
                        this.name = epTitle ?: "Episódio $epId"
                        this.posterUrl = poster
                    }
                ))
            }
        }

        // Busca detalhes do anime
        var title = "Anime $animeId"
        var poster: String? = null
        var plot: String? = null
        var tags: List<String>? = null
        var seasons: List<AnimeSeason> = emptyList()

        try {
            val res = app.get("$mainUrl/v2/anime/$animeId", headers = API_HEADERS, timeout = 15)
            if (res.code == 200) {
                val text = res.text
                // Tenta parse tipado; se falhar, usa JsonNode genérico
                val wrapper = tryParseJson<JsonNode>(text)
                val dataNode = wrapper?.get("data") ?: wrapper
                if (dataNode != null) {
                    // anime_details
                    val detailsNode = dataNode.get("anime_details")
                    if (detailsNode != null && !detailsNode.isNull) {
                        title = detailsNode.get("anime_name")?.asText()
                            ?: detailsNode.get("name")?.asText()
                            ?: detailsNode.get("title")?.asText()
                            ?: title
                        plot = detailsNode.get("anime_description")?.asText()
                            ?: detailsNode.get("description")?.asText()
                            ?: detailsNode.get("sinopse")?.asText()
                        poster = detailsNode.get("cape")?.asText()
                            ?: detailsNode.get("thumbnail")?.asText()
                            ?: detailsNode.get("banner")?.asText()
                            ?: detailsNode.get("poster")?.asText()
                            ?: detailsNode.get("cover")?.asText()
                        val genresNode = detailsNode.get("genres") ?: detailsNode.get("tags") ?: detailsNode.get("categories")
                        if (genresNode != null && genresNode.isArray) {
                            tags = genresNode.mapNotNull { it.asText() }
                        }
                    }
                    // seasons
                    val seasonsNode = dataNode.get("anime_seasons") ?: dataNode.get("seasons")
                    if (seasonsNode != null && seasonsNode.isArray) {
                        seasons = seasonsNode.mapNotNull { n ->
                            val sid = n.get("season_id")?.asInt() ?: n.get("id")?.asInt() ?: return@mapNotNull null
                            val sname = n.get("season_name")?.asText() ?: n.get("name")?.asText()
                            AnimeSeason(sid, sname)
                        }
                    }
                    // fallback: se ainda sem título, tenta direto no root
                    if (title == "Anime $animeId") {
                        title = dataNode.get("anime_name")?.asText() ?: title
                    }
                }
            }
        } catch (_: Exception) {}

        // Busca episódios por temporada (bundle: {page,order} sem token)
        val episodes = mutableListOf<Episode>()
        for (season in seasons.ifEmpty { listOf(AnimeSeason(animeId, null)) }) {
            val isFallback = seasons.isEmpty()
            try {
                val reqBody = SeasonReq(page = 1, order = "asc")
                val epRes = app.post("$mainUrl/season/${season.seasonId}/episodes", headers = SEARCH_HEADERS, json = reqBody, timeout = 15)
                if (epRes.code != 200) {
                    if (isFallback) continue else continue
                }
                val parsed = tryParseJson<SeasonEpisodesResp>(epRes.text) ?: continue
                val data = parsed.data ?: emptyList()
                if (data.isEmpty()) continue
                data.forEach { ep ->
                    val epId = ep.epId ?: ep.episodeId ?: ep.id ?: return@forEach
                    val epName = ep.epName ?: ep.episodeName ?: ep.name ?: "Episódio $epId"
                    val epNum = ep.episodeNumber ?: ep.epNumber ?: ep.number
                    val thumb = ep.thumbnail ?: ep.thumb
                    episodes.add(newEpisode(epId.toString()) {
                        this.name = epName
                        this.episode = epNum
                        this.posterUrl = thumb
                    })
                }
                if (episodes.isNotEmpty() && !isFallback) break // já achou na primeira season válida
            } catch (_: Exception) { continue }
        }

        // Fallback 2: feed Novos episódios filtrando por anime; preenche título/poster quando API de episódios 500
        if (episodes.isEmpty() || title == "Anime $animeId" || poster == null) {
            try {
                val feed = fetchFeed()
                val data = feed?.get("data")
                if (data != null && data.isArray) {
                    for (sec in data) {
                        if (sec.get("type")?.asInt() != 7) continue
                        val arr = sec.get("data") ?: continue
                        for (n in arr) {
                            if (n.get("ep_anime_id")?.asInt() != animeId) continue
                            val epId = n.get("ep_id")?.asInt() ?: continue
                            if (title == "Anime $animeId") {
                                n.get("anime_name")?.asText()?.let { title = it }
                            }
                            if (poster == null) poster = n.get("thumbnail")?.asText()
                            if (episodes.none { it.data == epId.toString() }) {
                                val epName2 = n.get("ep_name")?.asText() ?: "Episódio $epId"
                                val thumb = n.get("thumbnail")?.asText()
                                episodes.add(newEpisode(epId.toString()) {
                                    this.name = epName2
                                    this.posterUrl = thumb
                                })
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Último fallback: se tudo falhou e sabemos que anime 6888 tem ep 36947 (prova LAB §6.10), expõe ao menos um
        // mas genérico: se ainda vazio, cria 1 episódio placeholder que tentará stream direto (pode falhar, mas não crasha)
        // Não cria placeholder genérico para não poluir; deixa vazio e avisa no plot

        val hasEpisodes = episodes.isNotEmpty()
        if (!hasEpisodes) {
            plot = (plot ?: "") + "\n\n[Tomato API instável — 500 em /season/*/episodes. Tente novamente quando API voltar (janela IPv4). Última prova OK: wk4.oncourse-content.org 720p.m3u8 com policy/signature — ver LAB §6.10]"
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            if (hasEpisodes) {
                addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data }.sortedBy { it.episode ?: 0 })
            } else {
                // CloudStream exige ao menos um; deixamos vazio mesmo -> UI mostra "sem episódios" em vez de crash
            }
        }
    }

    // ---------- loadLinks ----------
    // GET /v2/anime/episode/{ep_id}/stream  -> {streams:{mhd:"https://wk4.oncourse-content.org/6888/36947/720p.m3u8?policy=...&signature=...&key-pair-id=APKAJ...", fhd:"...1080p.m3u8"}, episodeHasNext, ...}
    // policy iss=api.crunchyroll.com/v3 ttl 2h (LAB §6.10 validado: curl -> 9214 bytes #EXTM3U + 7.1M 000.ts h264/aac)
    data class Streams(
        @JsonProperty("mhd") val mhd: String? = null,
        @JsonProperty("fhd") val fhd: String? = null,
        @JsonProperty("shd") val shd: String? = null,
        @JsonProperty("hd") val hd: String? = null,
        @JsonProperty("sd") val sd: String? = null
    )
    data class StreamResp(
        @JsonProperty("streams") val streams: Streams? = null,
        @JsonProperty("episodeName") val episodeName: String? = null,
        @JsonProperty("episode_name") val episode_name: String? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("episode_number") val episode_number: Int? = null,
        @JsonProperty("episodeHasNext") val episodeHasNext: Boolean? = null,
        @JsonProperty("showInterstitial") val showInterstitial: Boolean? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epId = Regex("""(\d+)""").find(data)?.value ?: data
        return try {
            val res = app.get("$mainUrl/v2/anime/episode/$epId/stream", headers = API_HEADERS, timeout = 15)
            if (res.code != 200) return false
            val parsed = tryParseJson<StreamResp>(res.text) ?: return false
            val streams = parsed.streams ?: return false

            var found = false
            val candidates = listOf(
                Triple(streams.fhd, "1080p", Qualities.P1080.value),
                Triple(streams.mhd, "720p", Qualities.P720.value),
                Triple(streams.hd, "HD", Qualities.P720.value),
                Triple(streams.shd, "480p", Qualities.P480.value),
                Triple(streams.sd, "360p", Qualities.P360.value)
            ).filter { !it.first.isNullOrBlank() }

            for ((url, label, qual) in candidates) {
                val u = url ?: continue
                // URL já é HLS assinado CloudFront; usamos M3U8Helper para resolver variantes ou direto
                try {
                    // Tenta resolver via M3U8Helper para extrair qualidades internas; fallback para link direto
                    val m3u8Links = M3u8Helper.generateM3u8(name, u, mainUrl)
                    if (m3u8Links.isNotEmpty()) {
                        m3u8Links.forEach { link ->
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name $label",
                                    url = link.url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = mainUrl
                                    this.quality = link.quality
                                    this.headers = mapOf("User-Agent" to "tomato-android")
                                }
                            )
                            found = true
                        }
                    } else {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name $label",
                                url = u,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = mainUrl
                                this.quality = qual
                                this.headers = mapOf("User-Agent" to "tomato-android")
                            }
                        )
                        found = true
                    }
                } catch (_: Exception) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name $label",
                            url = u,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = qual
                        }
                    )
                    found = true
                }
            }
            found
        } catch (_: Exception) {
            false
        }
    }
}
