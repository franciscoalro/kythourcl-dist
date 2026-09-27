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
        // UA original do app. O Dalvik falso foi testado A/B (25 rodadas cada):
        // tomato-android 5/25 vs Dalvik 1/25 -> nao ajuda, e nao vale virar fingerprint.
        const val APP_UA = "tomato-android"
        // Quantidade de tentativas no endpoint /stream + espera entre falhas.
        // Medido 2026-09-27: taxa de sucesso oscila 12-20% (500 em rajadas) na janela
        // parcial, e cai a 0% quando a origem inteira cai. Ver bloco de health check_origina.
        // v158: a deteccao de origem morta passa a ser feita por catch-all 500 (ver
        // isOriginDead()), que e conclusivo em 1 request e nao depende de /feed.
        const val STREAM_ATTEMPTS = 20
        const val RETRY_DELAY_MS = 500L
        // Apos este numero de falhas seguidas no /stream, faz 1 sonda de saude na
        // origem. Medido: com a origem NO AR e sem token a API devolve 403; com a
        // origem FORA devolve 500. A sonda distingue os dois estados em 1 request.
        const val HEALTH_PROBE_AFTER = 6
        // v158: nonce no caminho da sonda de origem morta, para que o edge/qualquer
        // cache intermediario nunca devolva uma resposta guardada de uma chamada
        // anterior -- a sonda precisa refletir o estado atual da origem.
        val PROBE_NONCE = System.currentTimeMillis().toString()
        // Tentativas em detalhes e temporada. Medido no redroid com a v156: 8
        // tentativas deixavam load() em 11.1s, porque 8 x (timeout + 500ms) de
        // backoff domina o tempo antes do loadLinks. 3 tentativas cortam para
        // ~4s sem perder a tolerancia a flapping (janela parcial e de 12-20%
        // de sucesso, mas em rajadas -- 3 tentativas ja pegam uma rajada).
        // A sonda isOriginDown() ainda encurta o caminho quando a origem cai.
        const val DETAIL_ATTEMPTS = 3
        val API_HEADERS = mapOf(
            "User-Agent" to APP_UA,
            "Authorization" to "Bearer $BEARER_TOKEN",
            "Accept" to "application/json",
            "Accept-Encoding" to "gzip",
            "Connection" to "Keep-Alive"
        )
        val SEARCH_HEADERS = mapOf(
            "User-Agent" to APP_UA,
            "Authorization" to "Bearer $BEARER_TOKEN",
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Accept-Encoding" to "gzip",
            "Connection" to "Keep-Alive"
        )
    }

    // Títulos offline para ids que só aparecem em type3/5 sem anime_name (API 500)
    // 8 de "Em alta" via OCR/Cape + 13 de type7; cobre catálogo embutido quando /v2/anime/* está 500
    private val fallbackTitleById: Map<Int, String> = mapOf(
        1089 to "Shingeki no Kyojin",
        1279 to "Bleach",
        1179 to "Mushoku Tensei",
        1100 to "Tensei shitara Slime Datta Ken",
        1649 to "Kaiju No. 8",
        6881 to "Super no Ura de Yani Suu Futari",
        6852 to "Yani Neko",
        1049 to "JoJo's Bizarre Adventure",
        6861 to "Let\u2019s go KAIKIGUMI",
        1921 to "You and I Are Polar Opposites",
        6888 to "Hanaori-san wa Tensei shitemo Kenka ga Shitai",
        6887 to "Grow Up Show: Himawari no Circus-dan",
        6860 to "Tenmaku no Jaadugar",
        6831 to "Daemons of the Shadow Realm",
        1117 to "Welcome to Demon School! Iruma-kun",
        1213 to "Ascendance of a Bookworm",
        1320 to "Link Click",
        1681 to "Nige Jouzu no Wakagimi",
        6885 to "Kore Kaite Shine"
    )

    // ---------- Feed ----------
    // /v2/animes/feed -> {status:true,status_code:4,remote_settings:{},data:[{type:3,title:"Em alta",data:[{anime_id,thumbnail}]},{type:7,title:"Novos episódios",data:[{ep_id,ep_anime_id,anime_name,ep_name}]},...]}
    // Usamos JsonNode para lidar com tipos heterogêneos
    // 2026-09-27: prod-api oscila entre 500 e 200 -> feed embutido evita catálogo vazio
    private suspend fun fetchFeed(): JsonNode? {
        val mapper = jacksonObjectMapper().apply { configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false) }
        // v158: antes de gastar 4 tentativas em /feed, 1 request barato detecta
        // origem morta. No cenario medido (queda total) isso troca 4 x (15s timeout
        // + 500ms) por 1 request de ~0.15s, e cai direto no feed embutido.
        if (isOriginDead()) return try { mapper.readTree(TomatoFallback.FEED_JSON) } catch (_: Exception) { null }
        repeat(4) { attempt ->
            try {
                val res = app.get("$mainUrl/v2/animes/feed", headers = API_HEADERS, timeout = 15)
                if (res.code == 200) {
                    val node = mapper.readTree(res.text)
                    if (node.get("data") != null) return node
                }
            } catch (_: Exception) {}
            if (attempt < 3) kotlinx.coroutines.delay(RETRY_DELAY_MS)
        }
        return try {
            mapper.readTree(TomatoFallback.FEED_JSON)
        } catch (_: Exception) {
            null
        }
    }

    // Busca JSON com retry. Usado por detalhes e temporada, que oscilam igual ao stream.
    // A sonda de saude encurta o caminho quando a origem esta FORA: medido no aparelho,
    // sem ela o load() levava 13.8s (8 tentativas de detalhe + 8 de temporada) para
    // so entao chamar o loadLinks e descobrir que nao havia link.
    // v157: o default caiu de 8 para DETAIL_ATTEMPTS (3), medido em 11.1s na v156.
    private suspend fun getJsonWithRetry(path: String, headers: Map<String, String> = API_HEADERS, attempts: Int = DETAIL_ATTEMPTS): String? {
        // probeBudget = 2, nao 3: com DETAIL_ATTEMPTS = 3 o budget de 3 so
        // zerava na ultima tentativa, ou seja, a sonda rodava tarde demais para
        // encurtar o caminho. 2 dispara na segunda, sobrando uma para tentar
        // de novo caso a origem tenha voltou entre as duas.
        //
        // v158: a sonda e isOriginDead() (catch-all 500 em rota inexistente), que
        // confirma origem morta estrutural em 1 request. Se ela nao for conclusiva
        // (a origem respondeu de verdade, ex 404/403), cai para isOriginDown(), que
        // ainda distingue "endpoint /feed ruim" de "origem fora" pelo par 403/500.
        // A ordem importa: primeiro a evidencia mais forte, depois a mais fraca.
        var probeBudget = 2
        repeat(attempts) { attempt ->
            try {
                val res = app.get("$mainUrl$path", headers = headers, timeout = 15)
                if (res.code == 200 && res.text.isNotBlank()) return res.text
                // 403 aqui = credencial, nao origem fora. 5xx = origem.
                if (res.code == 403) return null
            } catch (_: Exception) {}
            if (attempt < attempts - 1) kotlinx.coroutines.delay(RETRY_DELAY_MS)
            if (--probeBudget == 0) {
                if (isOriginDead()) return null
                if (isOriginDown()) return null
                probeBudget = 3
            }
        }
        return null
    }

    // POST JSON com retry (temporada usa body {page, order}).
    private suspend fun postJsonWithRetry(path: String, body: Any, attempts: Int = DETAIL_ATTEMPTS): String? {
        // probeBudget = 2 pelo mesmo motivo do getJsonWithRetry: com 3
        // tentativas, budget 3 so dispara a sonda na ultima, tarde demais.
        var probeBudget = 2
        repeat(attempts) { attempt ->
            try {
                val res = app.post("$mainUrl$path", headers = SEARCH_HEADERS, json = body, timeout = 15)
                if (res.code == 200 && res.text.isNotBlank()) return res.text
                if (res.code == 403) return null
            } catch (_: Exception) {}
            if (attempt < attempts - 1) kotlinx.coroutines.delay(RETRY_DELAY_MS)
            if (--probeBudget == 0) {
                if (isOriginDead()) return null
                if (isOriginDown()) return null
                probeBudget = 3
            }
        }
        return null
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
                3 -> { // Em alta: [{anime_id, thumbnail banner}] -> offline titles when API 500
                    arr.mapNotNull { n ->
                        val animeId = n.get("anime_id")?.asInt() ?: return@mapNotNull null
                        val thumb = n.get("thumbnail")?.asText()
                            ?: n.get("banner")?.asText()
                        val t = fallbackTitleById[animeId] ?: "Anime $animeId"
                        newAnimeSearchResponse(
                            t,
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
                        val t = fallbackTitleById[animeId] ?: "Anime $animeId"
                        newAnimeSearchResponse(
                            t,
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
                        val t = fallbackTitleById[animeId] ?: "Anime $animeId"
                        allItems.add(newAnimeSearchResponse(t, "$mainUrl/anime/$animeId", TvType.Anime){ this.posterUrl = thumb })
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
            val text = postJsonWithRetry("/v2/content/search", body, attempts = 6)
            if (text != null) {
                val parsed = tryParseJson<SearchRespWrapper>(text)
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
        // Fallback: busca no feed embutido por anime_name / ep_anime_id (e nos títulos offline type3/5)
        return try {
            val feed = fetchFeed() ?: return emptyList()
            val data = feed.get("data") ?: return emptyList()
            val out = linkedMapOf<Int, SearchResponse>()
            // type7 names
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
                }
            }
            // type3/5 offline titles (busca por substring no nome offline)
            for ((id, title) in fallbackTitleById) {
                if (out.containsKey(id)) continue
                if (!title.contains(q, ignoreCase = true)) continue
                // verifica se id existe em alguma seção type3/5 do feed
                var exists = false
                for (sec in data) {
                    val arr = sec.get("data") ?: continue
                    if (!arr.isArray) continue
                    for (n in arr) {
                        if (n.get("anime_id")?.asInt() == id) { exists = true; break }
                    }
                    if (exists) break
                }
                if (!exists) continue
                // poster do feed se houver
                var poster: String? = null
                for (sec in data) {
                    val arr = sec.get("data") ?: continue
                    if (!arr.isArray) continue
                    for (n in arr) {
                        if (n.get("anime_id")?.asInt() == id) {
                            poster = n.get("thumbnail")?.asText() ?: n.get("cape")?.asText(); if (poster!=null) break
                        }
                    }
                    if (poster!=null) break
                }
                out[id] = newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) { this.posterUrl = poster }
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
        var title = fallbackTitleById[animeId] ?: "Anime $animeId"
        var poster: String? = null
        var plot: String? = null
        var tags: List<String>? = null
        var seasons: List<AnimeSeason> = emptyList()

        try {
            var resText: String? = null
            resText = getJsonWithRetry("/v2/anime/$animeId", attempts = DETAIL_ATTEMPTS)
            if (resText != null) {
                val text = resText
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
                    // Se ainda é "Anime $id" mas temos offline, usa offline
                    if (title == "Anime $animeId") {
                        fallbackTitleById[animeId]?.let { title = it }
                    }
                }
            }
        } catch (_: Exception) {}

        // Busca episódios por temporada (bundle: {page,order} sem token)
        val episodes = mutableListOf<Episode>()
        for (season in seasons.ifEmpty { listOf(AnimeSeason(animeId, null)) }) {
            try {
                val reqBody = SeasonReq(page = 1, order = "asc")
                val epText = postJsonWithRetry("/season/${season.seasonId}/episodes", reqBody, attempts = DETAIL_ATTEMPTS)
                if (epText == null) continue
                val parsed = tryParseJson<SeasonEpisodesResp>(epText) ?: continue
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
                if (episodes.isNotEmpty()) break // já achou na primeira season válida
            } catch (_: Exception) { continue }
        }

        // Fallback 2: feed completo (type 3/5/7) quando API 500 — preenche título/poster/episódios
        // Motivo: alguns animes só aparecem em "Em alta" (type3) ou categorias (type5) sem entrada em type7,
        // então ficavam sem episódios e sem botão de player.
        if (episodes.isEmpty() || title == "Anime $animeId" || poster == null) {
            // offline map corrige título antes mesmo do feed (type3/5 não tem anime_name)
            if (title == "Anime $animeId") {
                fallbackTitleById[animeId]?.let { title = it }
            }
            try {
                val feed = fetchFeed()
                val data = feed?.get("data")
                if (data != null && data.isArray) {
                    // Primeiro coleta poster/título de qualquer seção que tenha anime_id == animeId
                    for (sec in data) {
                        val arr = sec.get("data") ?: continue
                        if (!arr.isArray) continue
                        for (n in arr) {
                            val aId = n.get("anime_id")?.asInt() ?: n.get("ep_anime_id")?.asInt() ?: continue
                            if (aId != animeId) continue
                            if (title == "Anime $animeId") {
                                n.get("anime_name")?.asText()?.let { title = it }
                                // se ainda "Anime $id", tenta offline
                                if (title == "Anime $animeId") fallbackTitleById[animeId]?.let { title = it }
                            }
                            if (poster == null) {
                                poster = n.get("thumbnail")?.asText()
                                    ?: n.get("cape")?.asText()
                                    ?: n.get("banner")?.asText()
                            }
                        }
                    }
                    // Depois coleta episódios apenas de type 7 (único que tem ep_id)
                    for (sec in data) {
                        if (sec.get("type")?.asInt() != 7) continue
                        val arr = sec.get("data") ?: continue
                        for (n in arr) {
                            if (n.get("ep_anime_id")?.asInt() != animeId) continue
                            val epId = n.get("ep_id")?.asInt() ?: continue
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
            // garante que offline nunca deixe "Anime $id" passar
            if (title == "Anime $animeId") {
                fallbackTitleById[animeId]?.let { title = it }
            }
        }

        // Fallback 3: sintético para garantir botão de player quando API 500 e feed não tinha ep em type7
        // Ex: 1089, 1279, 1179, 6881 (só em "Em alta") ficam sem episódios -> CloudStream não mostra player
        //
        // v159 -- o "Fallback 3" e MOITO. A entrada sintetica tinha data
        // "${animeId}_0", que o loadLinks transformava num ID de episodio inventado
        // (o ID do anime) e resultava em "Nenhum link encontrado" apos 5s. O
        // loadLinks agora rejeita o placeholder, mas ainda assim um botao de play
        // que so pode falhar e pior que nenhum: gasta o tempo do usuario para
        // entregar "nada". O catalogo offline continua mostrando titulo, capa,
        // sinopse e tags, mas o aviso no `plot` diz o que esta acontecendo.
        //
        // A entrada e mantida para o catalogo nao ficar vazio (o CloudStream usa
        // a lista de episodios para montar a tela e o botao do player), mas a
        // sinopse explica que reproducao depende da API voltar.
        var hasEpisodes = episodes.isNotEmpty()
        var apiOffline = false
        if (!hasEpisodes) {
            apiOffline = true
            // Sinopse vazia = catálogo offline; não poluímos sinopse real quando já existe
            if (plot.isNullOrBlank()) {
                plot = "Catálogo offline (feed embutido). A origem do Tomato está fora do ar no momento — títulos e metadados abaixo vêm do cache local, mas a reprodução precisa que a API volte. Se o botão de tocar não responder, é por isso."
            }
            // Cria 1 episódio sintético apenas para a tela não ficar vazia.
            // data = "${animeId}_0" e o marcador sintetico que o loadLinks
            // reconhece e rejeita em vez de inventar um episode_id (ver loadLinks v159).
            episodes.add(newEpisode("${animeId}_0") {
                this.name = "Episódio 1 — indisponível (API fora do ar)"
                this.posterUrl = poster
                this.episode = 1
            })
            hasEpisodes = true
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            if (hasEpisodes) {
                addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data }.sortedBy { it.episode ?: 0 })
            }
        }
    }

    // ---------- loadLinks ----------
    // GET /v2/anime/episode/{ep_id}/stream  -> {streams:{mhd:"https://wk4.oncourse-content.org/6888/36947/720p.m3u8?policy=...&signature=...&key-pair-id=APKAJ...", fhd:"...1080p.m3u8"}, episodeHasNext, ...}
    // policy iss=api.crunchyroll.com/v3 ttl 2h (LAB §6.10 validado: curl -> 9214 bytes #EXTM3U + 7.1M 000.ts h264/aac)
    // 2026-09-27 17:00 UTC: medido novamente. /stream oscila entre 500 e 200 a 12-20%,
    // edge.betomato.com e 0/50 (morto), e os 500 tem corpo "Internal Server Error" puro
    // (nenhum JSON recuperavel atras do status). Logo: retry com backoff no host vivo,
    // parando no primeiro 200 com streams. Ver comentarios no companion object.
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

    private fun isPolicyExpired(url: String): Boolean {
        return try {
            val q = url.substringAfter("policy=", "").substringBefore("&")
            if (q.isEmpty()) return false
            var b64 = q
            b64 += "=".repeat((4 - b64.length % 4) % 4)
            val json = String(java.util.Base64.getUrlDecoder().decode(b64))
            val exp = Regex("\"exp\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return false
            val now = System.currentTimeMillis() / 1000L
            now >= exp
        } catch (_: Exception) { false }
    }

    // Sonda de saude da origem. Distingue "API fora do ar" de "endpoint oscilando"
    // em UMA requisicao, sem token de proposito:
    //   origem NO AR  -> 403 {"status":false,"message":"authentication failed"}
    //   origem FORA   -> 500 "Internal Server Error"
    // Sem isso, uma origem 100% fora faz o usuario esperar as 20 tentativas
    // (medido: 17s media, 27.7s pior caso) para receber o mesmo "nenhum link" que
    // a versao antiga devolvia em menos de 1s.
    //
    // v158 -- deteccao de origem morta por catch-all 500.
    // A sonda acima usa /feed, mas /feed e um endpoint de CONTEUDO: quando ele falha
    // por outro motivo (cache frio, rota especifica quebrada, 5xx parcial) o 500 nao
    // diz que a origem inteira morreu, e a sonda erra ao cortar o retry cedo demais.
    // O sinal definitivo de origem morta e diferente: um 500 em um caminho que
    // NAO EXISTE. Medido em 2026-09-27 na queda real:
    //   GET /zzz-nao-existe-12345      -> 500
    //   GET /v2/anime/99999999         -> 500
    //   GET /  (raiz)                 -> 500
    //   OPTIONS /v2/animes/feed       -> 204   (edge/TLS/CORS OK, so a app caiu)
    // Um backend com rotas vivas devolveria 404 nesse caminho aleatorio. 500 em
    // caminho inexistente = o dispatch nunca roda = origem morta de forma estrutural.
    // Isso e 1 request sem token, sem peso de payload, e conclusivo.
    private suspend fun isOriginDead(): Boolean {
        return try {
            val res = app.get("$mainUrl/zzz-nao-existe-$${PROBE_NONCE}", headers = mapOf("User-Agent" to APP_UA), timeout = 10)
            when (res.code) {
                404, 410 -> false          // rota respondeu 404 de verdade: origem VIVA
                in 500..599 -> true        // 500 em rota inexistente: origem MORTA
                401, 403 -> false           // autenticacao falhou, mas o dispatch rodou
                else -> false
            }
        } catch (_: Exception) {
            // timeout/erro de rede tambem contam como origem indisponivel
            true
        }
    }

    // Sonda de saude legada: usada quando a deteccao estrutural nao e conclusiva
    // (origem viva mas /feed ruim). Mantida porque distingue 403 (credencial) de
    // 500 (endpoint), info que o catch-all nao entrega.
    private suspend fun isOriginDown(): Boolean {
        return try {
            // rota barata e sempre presente; sem Authorization de proposito
            val res = app.get("$mainUrl/v2/animes/feed", headers = mapOf("User-Agent" to APP_UA), timeout = 10)
            res.code >= 500
        } catch (_: Exception) {
            // timeout/erro de rede tambem contam como origem indisponivel
            true
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // v159 -- correcao do bug de ID sintetico.
        // Antes: Regex("""(\d+)""").find(data) pegava o PRIMEIRO bloco de digitos de
        // `data`. Para episodio real `data` e um "36937" limpo e funciona. Mas o
        // catalogo offline cria data = "${animeId}_0" (ex: "7115_0") e a regex
        // devolvia 7115 -- o ID do ANIME, nao de um episodio. Resultado: o plugin
        // pedia /v2/anime/episode/7115/stream, um episodio inexistente, e o
        // CloudStream exibia "Nenhum link encontrado" depois de 5s de retry.
        // Prova no logcat: "CS3ExoPlayer: newInstance = {kitsu=7115}".
        //
        // Nao extrai digitos de string composta. `data` de episodio real e sempre
        // um ID numerico puro; qualquer coisa fora desse formato e o placeholder
        // offline, que nao tem /stream para resolver.
        val epId = data.trim()
        if (epId.isEmpty() || epId.any { !it.isDigit() }) {
            // Placeholder offline (data contem '_' ou nao-numerico). Nao ha
            // episodio real para resolver: falhar agora em vez de gastar
            // STREAM_ATTEMPTS tentativas num ID inventado.
            return false
        }
        return try {
            // Retry no host que responde. edge.betomato.com e 0/50 medido -> fora.
            // O /stream devolve 500 com corpo plain ~80% das vezes na janela parcial,
            // entao nao ha nada a reaproveitar: cada tentativa e um GET novo.
            //
            // Duas sondas de saude cortam o caminho quando a origem esta 100% fora,
            // que e o caso em que retry nao ajuda (medido 0/10 mesmo com 20 tentativas).
            var parsed: StreamResp? = null
            var attempt = 0
            var consecutiveFails = 0
            while (attempt < STREAM_ATTEMPTS) {
                try {
                    val res = app.get("$mainUrl/v2/anime/episode/$epId/stream", headers = API_HEADERS, timeout = 15)
                    if (res.code == 200) {
                        val body = tryParseJson<StreamResp>(res.text)
                        if (body?.streams != null) {
                            parsed = body
                            break
                        }
                    }
                } catch (_: Exception) {}
                attempt++
                consecutiveFails++
                if (attempt < STREAM_ATTEMPTS) kotlinx.coroutines.delay(RETRY_DELAY_MS)
                // Origem fora do ar: insistir so piora a espera do usuario.
                if (consecutiveFails == HEALTH_PROBE_AFTER) {
                    // v158: primeiro a evidencia estrutural (catch-all 500 em rota
                    // inexistente). Antes o unico sinal era isOriginDown(), que
                    // pergunta ao /feed e pode dar falso positivo quando o /feed
                    // sozinho falha com a origem de pe.
                    if (isOriginDead() || isOriginDown()) return false
                    // origem viva, so o /stream que esta ruim -> segue tentando
                    consecutiveFails = 0
                }
            }
            if (parsed == null) return false
            val streams = parsed.streams ?: return false
            // Se policy já expirou, não envia link inválido (daria 405/Invalid signature no player)
            val candidatesPre = listOf(streams.fhd, streams.mhd, streams.hd, streams.shd, streams.sd).filterNotNull().filter { it.isNotBlank() }
            if (candidatesPre.isNotEmpty() && candidatesPre.all { isPolicyExpired(it) }) return false

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
                                    this.headers = mapOf("User-Agent" to APP_UA)
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
                                this.headers = mapOf("User-Agent" to APP_UA)
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
                            this.headers = mapOf("User-Agent" to APP_UA)
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
