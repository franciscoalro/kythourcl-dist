package com.tomato

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Tomato : MainAPI() {
    override var mainUrl = "https://prod-api.tomatoanimes.com"
    override var name = "TomatoAnimes"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val TAG = "Tomato"

    // v167: removido o proxy configurável que dependia de
    // com.lagradost.api.getContext. Essa API existe no ambiente de compilação,
    // mas não em todas as versões instaladas do CloudStream e causava
    // NoClassDefFoundError: ContextHelper_jvmKt ao carregar o provider.
    // O plugin usa agora somente APIs estáveis da biblioteca CloudStream.

    // Reconhece a assinatura do 500 DE BORDA, e so ela:
    //   500 + Server: cloudflare + Content-Type: text/html + corpo 21 B
    //   "Internal Server Error"
    // O 500 de origem morta e' indistinguivel no status; o que separa e' o
    // header, e o unico jeito de provar que o header separa e' o A/B por saida
    // (medido: 18/18 200 pelo Tor com o mesmo Bearer). Nao confundir este
    // detector com healthCheck(): aquele responde "houve resposta?", este
    // responde "a resposta veio do edge bloqueando o IP?".
    private fun parece500DeBorda(res: com.lagradost.nicehttp.NiceResponse): Boolean {
        return try {
            if (res.code != 500) return false
            val server = res.headers["server"] ?: ""
            val ctype = res.headers["content-type"] ?: ""
            val corpo = try { res.text } catch (_: Exception) { "" }
            server.contains("cloudflare", ignoreCase = true) &&
                ctype.contains("text/html", ignoreCase = true) &&
                corpo.length <= 64
        } catch (_: Exception) { false }
    }

    // BEARER_TOKEN e' um JWT de sessao de cliente, sem claim `exp`, capturado de
    // /data/data/com.tomatos.clientapp/shared_prefs/...xml. Nao e' chave de servidor
    // nem credencial de escrita; identifica uma conta de terceiro. Por isso nao
    // entra em log, nem em comentario, nem em constante publica exposta.
    //
    // O header e' OBRIGATORIO, medido em 2026-09-27: mesma rota, mesma sessao,
    // 200 com o Bearer e 403 "authentication failed" sem ele. Logo nao existe
    // publicacao sem token que continue funcionando, e o token so sai do artefato
    // por rotacao do lado da origem.
    //
    // Enquanto o token estiver no fonte, e o repositorio e' publico, a conta
    // permanece exposta. A saida real e' rotacao server-side, que depende de
    // login no aplicativo (hCaptcha) e nao pode ser feita por este repositorio.
    companion object {
        const val BEARER_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpZCI6NDg0NjcyOSwidXVpZCI6ImQ0ODk1NjZjLTI0NDMtNDU3OS1iMzkwLWI1YjQxOTgzMTA5MCIsImlhdCI6MTc5MDUwNjI4MH0.3MP87IJav4bhPJzt5YUUv1mEeOdWp2zxo5_hc6w51YU"
        // UA original do app. O Dalvik falso foi testado A/B (25 rodadas cada):
        // tomato-android 5/25 vs Dalvik 1/25 -> nao ajuda, e nao vale virar fingerprint.
        const val APP_UA = "tomato-android"
        // Quantidade de tentativas no endpoint /stream + espera entre falhas.
        // Medido 2026-09-27: o /stream responde 200 em 20/20 (100%) quando o IP
        // nao esta bloqueado pelo edge, e 500 em 20/20 quando esta. Ver o bloco de
        // healthCheck() para a leitura correta desse 500.
        // v164: a deteccao de origem morta saiu; quem decide o corte do retry e'
        // healthCheck(), e apenas no caso de falta de resposta.
        // Com failover entre dois hosts, três ciclos completos são suficientes e
        // ficam dentro da janela de loadLinks do CloudStream (~10 s).
        const val STREAM_ATTEMPTS = 4
        const val RETRY_DELAY_MS = 150L
        // Apos este numero de falhas seguidas no /stream, faz 1 sonda de saude na
        // origem. A sonda so encerra o retry se nao houver resposta nenhuma; em 5xx
        // ela mantem o retry, porque 5xx e' o estado que o retry ainda resolve.
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
        // v163: teto de paginação por temporada. A API nunca documentou o
        // tamanho de página; 25 aparece em todas as medições, o que sugere
        // corte do servidor, mas isso é hipótese, não contrato. O teto existe
        // para que uma season patológica não vire laço infinito de requests.
        const val MAX_SEASON_PAGES = 20
        // A Home possui várias linhas apontando para o mesmo /feed. O CloudStream
        // chama getMainPage uma vez por linha; sem cache isso repetia a mesma chamada
        // 12 vezes e, em 500, fazia até 48 requests + backoff. Mantemos o feed em
        // memória por cinco minutos e coalescemos carregamentos simultâneos.
        const val FEED_CACHE_TTL_MS = 5 * 60 * 1000L
        // v166: headers baseados em captura mitmproxy do app real 1.4.3 (Redroid 2026-09-28).
        // O app alterna User-Agent entre endpoints:
        //   - Feed/recents/season/search: okhttp/4.11.0  (React-Native network stack)
        //   - Stream:                     tomato-android  (módulo nativo de streaming)
        // Também envia request-time e x-app que podem influenciar rate-limiting.
        const val APP_VERSION = "1.4.3"
        const val OKHTTP_UA = "okhttp/4.11.0"
        // v168: ambos são hosts oficiais. Medido no mesmo segundo: prod pode
        // devolver 500 enquanto edge devolve 200 (e vice-versa). Alternar os
        // hosts por tentativa evita cair no catálogo parcial por uma falha local.
        val API_HOSTS = listOf(
            "https://edge.betomato.com",
            "https://prod-api.tomatoanimes.com"
        )

        fun apiHeaders(): Map<String, String> = mapOf(
            "User-Agent" to OKHTTP_UA,
            "Authorization" to "Bearer $BEARER_TOKEN",
            "Accept" to "application/json, text/plain, */*",
            "request-time" to System.currentTimeMillis().toString(),
            "x-app" to APP_VERSION
        )
        fun searchHeaders(): Map<String, String> = mapOf(
            "User-Agent" to OKHTTP_UA,
            "Authorization" to "Bearer $BEARER_TOKEN",
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/plain, */*",
            // Não definir Accept-Encoding: OkHttp só faz a descompressão gzip
            // transparente quando ele próprio adiciona esse header.
            "request-time" to System.currentTimeMillis().toString()
        )
        val STREAM_HEADERS = mapOf(
            "User-Agent" to APP_UA,
            "Authorization" to "Bearer $BEARER_TOKEN",
            "Content-Type" to "application/json",
            // Não definir Accept-Encoding manualmente: o OkHttp adiciona gzip e
            // descomprime transparentemente somente quando controla esse header.
            // A versão anterior recebia bytes gzip crus em res.text.
            "Accept" to "application/json, text/plain, */*"
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
    // Usamos JsonNode para lidar com tipos heterogêneos.
    private val feedMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
    private val feedMutex = Mutex()
    @Volatile private var cachedFeed: JsonNode? = null
    @Volatile private var cachedFeedAt = 0L

    // v171: cada entrada de mainPage dispara getMainPage separadamente. Como todas
    // as 12 entradas usam o mesmo endpoint, sem cache uma abertura da Home podia
    // executar o fluxo completo 12 vezes. O Mutex evita rajada concorrente e o TTL
    // curto preserva atualização do catálogo sem depender do cache HTTP do app.
    private suspend fun fetchFeed(): JsonNode? {
        val now = System.currentTimeMillis()
        cachedFeed?.takeIf { now - cachedFeedAt < FEED_CACHE_TTL_MS }?.let { return it }
        return feedMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            cachedFeed?.takeIf { lockedNow - cachedFeedAt < FEED_CACHE_TTL_MS }?.let { return@withLock it }
            val fresh = fetchFeedUncached()
            if (fresh != null) {
                cachedFeed = fresh
                cachedFeedAt = System.currentTimeMillis()
            }
            fresh
        }
    }

    // 2026-09-27: prod-api oscila entre 500 e 200 -> feed embutido evita catálogo vazio
    private suspend fun fetchFeedUncached(): JsonNode? {
        val mapper = feedMapper
        // v164: antes de gastar 4 tentativas em /feed, 1 request barato detecta
        // falta de resposta. No cenario medido (queda total) isso troca 4 x (15s
        // timeout + 500ms) por 1 request de ~0.15s, e cai direto no feed embutido.
        //
        // v164: a v158 chamava isOriginDead() aqui, e ela tratava QUALQUER 5xx como
        // origem morta. Medido em 2026-09-27: o 5xx do edge (Cloudflare bloqueando
        // IP) e' indistinguivel do 5xx de origem morta, e o primeiro e' transitorio.
        // Com a leitura errada, um bloqueio de IP de poucos segundos derrubava o
        // catalogo inteiro para o JSON offline sem tentar de novo. Agora so a falta
        // de resposta (INDISPONIVEL) corta; 5xx segue para o retry normal.
        if (healthCheck() == Health.INDISPONIVEL) return try { mapper.readTree(TomatoFallback.FEED_JSON) } catch (_: Exception) { null }
        repeat(4) { attempt ->
            try {
                // Alterna também o feed entre os hosts oficiais. Antes o failover
                // existia em detalhes/temporadas, mas a Home insistia só no mainUrl.
                val host = API_HOSTS[attempt % API_HOSTS.size]
                val res = app.get("$host/v2/animes/feed", headers = apiHeaders(), timeout = 15)
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
    private suspend fun getJsonWithRetry(path: String, headers: Map<String, String> = apiHeaders(), attempts: Int = DETAIL_ATTEMPTS): String? {
        // probeBudget = 2, nao 3: com DETAIL_ATTEMPTS = 3 o budget de 3 so
        // zerava na ultima tentativa, ou seja, a sonda rodava tarde demais para
        // encurtar o caminho. 2 dispara na segunda, sobrando uma para tentar
        // de novo caso a origem tenha voltou entre as duas.
        //
        // v164: a v158 encerrava o retry quando isOriginDead() ou isOriginDown()
        // davam 5xx, o que media "origem morta". A medicao de 2026-09-27 mostrou
        // que 5xx do edge (IP bloqueado) e' indistinguivel de 5xx de origem morta.
        // Encerrar ali cortava o retry na janela de flapping -- que e' exatamente a
        // janela em que insistir resolve. Agora so a falta de resposta encerra.
        var probeBudget = 2
        repeat(attempts) { attempt ->
            try {
                val host = API_HOSTS[attempt % API_HOSTS.size]
                val res = app.get("$host$path", headers = headers, timeout = 15)
                if (res.code == 200 && res.text.isNotBlank()) return res.text
                // 403 aqui = credencial, nao origem fora. 5xx = origem.
                if (res.code == 403) return null
                if (parece500DeBorda(res)) {
                    Log.w(TAG, "500 de BORDA (cloudflare/text-html) em $path: o IP de saida esta bloqueado; "
                            + "se este aparelho sair por IP de datacenter, configure a chave tomato_proxy_url para trocar a rota")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
            if (attempt < attempts - 1) kotlinx.coroutines.delay(RETRY_DELAY_MS)
            if (--probeBudget == 0) {
                if (healthCheck() == Health.INDISPONIVEL) return null
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
                val host = API_HOSTS[attempt % API_HOSTS.size]
                val res = app.post("$host$path", headers = searchHeaders(), json = body, timeout = 15)
                if (res.code == 200 && res.text.isNotBlank()) return res.text
                if (res.code == 403) return null
                if (parece500DeBorda(res)) {
                    Log.w(TAG, "500 de BORDA (cloudflare/text-html) em $path: IP de saida bloqueado; "
                            + "configure a chave tomato_proxy_url para trocar a rota")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
            if (attempt < attempts - 1) kotlinx.coroutines.delay(RETRY_DELAY_MS)
            if (--probeBudget == 0) {
                if (healthCheck() == Health.INDISPONIVEL) return null
                probeBudget = 3
            }
        }
        return null
    }

    override val mainPage = mainPageOf(
        "$mainUrl/v2/animes/feed" to "Novos episódios",
        "$mainUrl/v2/animes/feed" to "Em alta",
        "$mainUrl/v2/animes/feed" to "Recém adicionados",
        "$mainUrl/v2/animes/feed" to "Semanais",
        "$mainUrl/v2/animes/feed" to "Com dublagem",
        "$mainUrl/v2/animes/feed" to "Recomendados",
        "$mainUrl/v2/animes/feed" to "Os mais curtidos de hoje!",
        "$mainUrl/v2/animes/feed" to "Aventura",
        "$mainUrl/v2/animes/feed" to "Comédia",
        "$mainUrl/v2/animes/feed" to "Romance",
        "$mainUrl/v2/animes/feed" to "Slice Of Life",
        "$mainUrl/v2/animes/feed" to "Talvez você goste",
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
    // v166: contrato capturado no app oficial 1.4.3 via mitmproxy:
    // POST /v2/content/search
    // body {token, search, content_type:"all", page:0}
    // response {status, result:[{id,type,name,episodes,date,image,...}]}
    // O contrato anterior usava content_type:"anime" + tags:[], e a API devolvia
    // result vazio mesmo para "naruto". Filtramos type=="anime" na resposta para
    // não mostrar os mangás que vêm junto no content_type:"all".
    data class SearchReq(
        @JsonProperty("token") val token: String = BEARER_TOKEN,
        @JsonProperty("search") val search: String,
        @JsonProperty("content_type") val contentType: String = "all",
        @JsonProperty("page") val page: Int = 0
    )
    data class SearchAnimeItem(
        @JsonProperty("anime_id") val animeId: Int? = null,
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("anime_name") val animeName: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("image") val image: String? = null,
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
            // v166: exatamente o body observado no app oficial.
            val body = SearchReq(search = q, contentType = "all", page = 0)
            val text = postJsonWithRetry("/v2/content/search", body, attempts = 6)
            if (text != null) {
                val parsed = tryParseJson<SearchRespWrapper>(text)
                val lst = parsed?.data?.result ?: parsed?.data?.data ?: parsed?.result ?: emptyList()
                lst.mapNotNull { item ->
                    if (item.type != null && !item.type.equals("anime", ignoreCase = true)) return@mapNotNull null
                    val animeId = item.animeId ?: item.id ?: return@mapNotNull null
                    val name = item.animeName ?: item.name ?: item.title ?: "Anime $animeId"
                    val poster = item.image ?: item.cape ?: item.thumbnail ?: item.banner ?: item.poster
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
        @JsonProperty("name") val name: String? = null,
        // v161: season_number para mapear episódios na season correta no player
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        // v161: season_dubbed para classificar DubStatus sem depender do nome
        @JsonProperty("season_dubbed") val seasonDubbed: Int? = null
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
        // v161: campo real da API é "ep_thumbnail", não "thumbnail"
        @JsonProperty("ep_thumbnail") val epThumbnail: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("thumb") val thumb: String? = null,
        @JsonProperty("dubbed") val dubbed: Boolean? = null
    )
    data class SeasonEpisodesResp(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("data") val data: List<SeasonEpisodeItem>? = null
    )
    // v166: captura do app oficial mostra corpo {token, page, order}. O endpoint
    // aceita Authorization sozinho em testes, mas enviamos também o token no body
    // para reproduzir exatamente o contrato nativo e evitar diferenças de edge.
    // v160 -- POST /season/{season_id}/episodes.
    // Dois bugs medidos contra o bundle Hermes + trafego real:
    //   page  era 1  -> a season so tem a pagina 0; page:1 devolve 500.
    //   order era "asc" minusculo -> o servidor so aceitou "ASC" maiusculo
    //   ("DESC" tambem deu 500 no teste). `order` e obrigatorio: sem ele, 500.
    // ATENCAO: a rota e sem o prefixo /v2, e so responde com season_id.
    // anime_id nessa rota devolve erro.
    data class SeasonReq(
        @JsonProperty("token") val token: String = BEARER_TOKEN,
        @JsonProperty("page") val page: Int = 0,
        @JsonProperty("order") val order: String = "ASC"
    )

    private fun episodeIdFromData(data: String?): String? {
        val raw = data?.trim().orEmpty()
        if (raw.isEmpty() || raw.contains('_')) return null
        if (raw.all { it.isDigit() }) return raw
        return Regex("""/(\d+)/?$""").find(raw)?.groupValues?.getOrNull(1)
    }

    private fun parseSeasonNumber(name: String?): Int? {
        if (name.isNullOrBlank()) return null
        Regex("""(?:season|temporada)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val roman = Regex("""(?:season|temporada)\s*([IVXLCDM]+)""", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.getOrNull(1)?.uppercase() ?: return null
        var total = 0
        var previous = 0
        for (c in roman.reversed()) {
            val value = when (c) {
                'I' -> 1; 'V' -> 5; 'X' -> 10; 'L' -> 50; 'C' -> 100; 'D' -> 500; 'M' -> 1000
                else -> return null
            }
            total += if (value < previous) -value else value
            if (value > previous) previous = value
        }
        return total.takeIf { it > 0 }
    }

    override suspend fun load(url: String): LoadResponse? {
        // url pode ser "$mainUrl/anime/{id}" ou "$mainUrl/episode/{epId}" (vindo de Novos episódios)
        val animeId = Regex("""/anime/(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(\d+)$""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null

        // Se a URL era de episódio direto, devolvemos série com 1 episódio apontando para stream
        if (url.contains("/episode/")) {
            val epId = Regex("""/episode/(\d+)""").find(url)?.groupValues?.getOrNull(1) ?: animeId.toString()
            var streamInfo: StreamResp? = null
            for (host in API_HOSTS) {
                try {
                    val res = app.get("$host/v2/anime/episode/$epId/stream", headers = STREAM_HEADERS, timeout = 2)
                    if (res.code == 200) {
                        streamInfo = tryParseJson<StreamResp>(res.text)
                        if (streamInfo != null) break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
            // O endpoint de stream informa episodeAnimeID. Redirecionamos o item
            // de "Novos episódios" para a página completa do anime para exibir
            // todas as temporadas, em vez de criar uma série artificial de 1 ep.
            val parentAnimeId = streamInfo?.episodeAnimeID ?: streamInfo?.episodeAnimeIdSnake
            if (parentAnimeId != null && parentAnimeId > 0) {
                return load("$mainUrl/anime/$parentAnimeId")
            }
            return newAnimeLoadResponse("Episódio $epId", url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, listOf(newEpisode(epId) {
                    this.name = streamInfo?.episodeName ?: "Episódio $epId"
                }))
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
                            // v162: passar season_number e season_dubbed que foram adicionados ao data class
                            // mas não estavam sendo lidos aqui → season.seasonNumber era sempre null
                            val snum = n.get("season_number")?.asInt()
                            val sdubbed = n.get("season_dubbed")?.asInt()
                            AnimeSeason(sid, sname, seasonNumber = snum, seasonDubbed = sdubbed)
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

        // Busca episódios por temporada.
        // v160: page 0 (a season só tem a página 0) e order "ASC" maiúsculo.
        // Também remove o fallback `AnimeSeason(animeId)`: /season/{id}/episodes
        // só aceita season_id, e usar anime_id ali só gastava 3 tentativas em
        // uma rota que não tem esse contrato.
        val episodes = mutableListOf<Episode>()
        // v161: separar seasons dubladas e legendadas para DubStatus correto
        val dubbedEpisodes = mutableListOf<Episode>()
        val subbedEpisodes = mutableListOf<Episode>()
        // v163: rastreamento de falhas por temporada. Antes, um `continue`
        // silencioso (linha 584 no v162) engolia a season inteira e o usuario
        // via um anime de 6 temporadas com 2, sem nenhuma indicacao. Medido no
        // redroid 2026-09-27: Shingeki (anime 1089) tem 6 seasons, e as ids
        // 215, 216, 554 e 1626 devolveram 500 em 5 tentativas cada enquanto 555
        // e 214 respondiam 200 na mesma janela, com o mesmo token. Quatro
        // temporadas desapareceram do resultado sem aviso.
        var seasonsRequested = 0
        val seasonsFailed = mutableListOf<String>()
        val seasonsTruncated = mutableListOf<String>()
        // Dedup preserva o mesmo ep_id em faixas legendada/dublada distintas.
        val presentationSeen = mutableSetOf<Pair<Boolean, String>>()
        for ((seasonIndex, season) in seasons.withIndex()) {
            val seasonLabel = "${season.seasonName ?: "season"}#${season.seasonId}"
            val seasonNumber = season.seasonNumber?.takeIf { it > 0 }
                ?: parseSeasonNumber(season.seasonName)
                ?: (seasonIndex + 1)
            val seasonEpIdsSeen = mutableSetOf<String>()
            var truncationRecorded = false
            try {
                var page = 0
                var declaredTotal: Int? = null
                var fetchedForSeason = 0
                // v163: itera paginas de verdade usando o total DECLARADO
                // (SeasonEpisodesResp.episodes, linha 468) como condicao de
                // termino. Antes so a pagina 0 era lida e o contador nunca era
                // comparado -- dado chega, codigo ignora, truncamento invisivel.
                while (page < MAX_SEASON_PAGES) {
                    val reqBody = SeasonReq(page = page, order = "ASC")
                    // Página 0 recebe os retries normais. Continuação usa somente
                    // uma tentativa por host: a API frequentemente declara N+1,
                    // entrega N na página 0 e responde 500 na página 1.
                    val pageAttempts = if (page == 0) DETAIL_ATTEMPTS else API_HOSTS.size
                    val epText = postJsonWithRetry("/season/${season.seasonId}/episodes", reqBody, attempts = pageAttempts)
                    if (epText == null) {
                        // v163: a season falhou. Nao engolimos: registramos e
                        // accounted, e o usuario e avisado na sinopse.
                        if (page == 0) {
                            seasonsRequested++
                            seasonsFailed.add(seasonLabel)
                            Log.w(TAG, "v163: temporada $seasonLabel falhou apos $DETAIL_ATTEMPTS tentativas (HTTP 5xx ou parse)")
                        } else {
                            Log.w(TAG, "v163: temporada $seasonLabel truncada: pagina $page falhou com ${fetchedForSeason} episodios ja obtidos (declarado=$declaredTotal)")
                            if (declaredTotal != null && fetchedForSeason < declaredTotal) {
                                seasonsTruncated.add("$seasonLabel ($fetchedForSeason/$declaredTotal)")
                                truncationRecorded = true
                            }
                        }
                        break
                    }
                    // v163: parse falha conta como season quebrada na pagina 0.
                    // Nao usamos `?: run { break }` porque `break` dentro de
                    // lambda inline so existe a partir do Kotlin 2.2, e o
                    // projeto esta em 2.1.10.
                    val parsed = tryParseJson<SeasonEpisodesResp>(epText)
                    if (parsed == null) {
                        if (page == 0) {
                            seasonsRequested++
                            seasonsFailed.add(seasonLabel)
                            Log.w(TAG, "v163: temporada $seasonLabel: JSON de /episodes nao parseia")
                        }
                        break
                    }
                    if (declaredTotal == null) declaredTotal = parsed.episodes
                    val data = parsed.data ?: emptyList()
                    if (page == 0) seasonsRequested++
                    if (data.isEmpty()) break
                    data.forEach { ep ->
                        val epId = ep.epId ?: ep.episodeId ?: ep.id ?: return@forEach
                        val epName = ep.epName ?: ep.episodeName ?: ep.name ?: "Episódio $epId"
                        val epNum = ep.epNumber ?: ep.episodeNumber ?: ep.number
                        // v161: ep_thumbnail é o campo real da API
                        val thumb = ep.epThumbnail ?: ep.thumbnail ?: ep.thumb
                        // v161: season_dubbed=1 é o sinal canônico; fallback para nome da season
                        val isDubbed = season.seasonDubbed == 1
                            || ep.dubbed == true
                            || season.seasonName?.contains("Dublado", ignoreCase = true) == true
                        // v163: dedup por ep_id entre paginas. Paginacao
                        // sobreposta repetiria episodios, e o distinctBy do
                        // fim ja nao segura duplicatas dentro da mesma lista
                        // quando o mesmo ep volta de duas paginas.
                        val epKey = epId.toString()
                        if (!seasonEpIdsSeen.add(epKey)) return@forEach
                        fetchedForSeason++
                        // Só deduplica dentro da mesma faixa; legendado e dublado
                        // podem compartilhar ep_id sem um apagar o outro.
                        if (!presentationSeen.add(isDubbed to epKey)) return@forEach
                        val newEp = newEpisode(epKey) {
                            this.name = epName
                            this.episode = epNum
                            this.posterUrl = thumb
                            this.season = seasonNumber
                        }
                        if (isDubbed) dubbedEpisodes.add(newEp) else subbedEpisodes.add(newEp)
                        episodes.add(newEp)
                    }
                    // v163: condicao de termino pelo total declarado. Se a API
                    // nao declarar (null), aceitamos a pagina e paramos no
                    // primeiro array vazio -- estado SEM_DECL, nunca assumido
                    // como completo.
                    val total = declaredTotal
                    if (total != null && fetchedForSeason >= total) break
                    page++
                }
                if (!truncationRecorded && declaredTotal != null && fetchedForSeason < declaredTotal) {
                    Log.w(TAG, "v168: temporada $seasonLabel: API declarou $declaredTotal mas entregou $fetchedForSeason")
                    seasonsTruncated.add("$seasonLabel ($fetchedForSeason/$declaredTotal)")
                }
                // v161: NÃO fazer break — processar TODAS as seasons para ter todos os episódios
                // O break anterior causava que só a primeira season funcionasse (ex: só Season I de Shingeki)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                seasonsRequested++
                seasonsFailed.add(seasonLabel)
                Log.w(TAG, "v163: temporada $seasonLabel lancou excecao: ${e.message}")
                continue
            }
        }

        // Fallback 2: feed completo (type 3/5/7) quando API 500 — preenche título/poster/episódios
        // Motivo: alguns animes só aparecem em "Em alta" (type3) ou categorias (type5) sem entrada em type7,
        // então ficavam sem episódios e sem botão de player.
        // O feed de "Novos episódios" também serve para completar a última
        // página quando /season declara mais itens do que entrega. Ele deve ser
        // consultado sempre, não apenas quando a lista inteira está vazia.
        if (episodes.isEmpty() || seasonsTruncated.isNotEmpty() || title == "Anime $animeId" || poster == null) {
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
                            if (episodes.none { episodeIdFromData(it.data) == epId.toString() }) {
                                val epName2 = n.get("ep_name")?.asText() ?: "Episódio $epId"
                                val thumb = n.get("thumbnail")?.asText()
                                val parsedNumber = Regex("""^\s*(\d+)""")
                                    .find(epName2)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                val newEp = newEpisode(epId.toString()) {
                                    this.name = epName2
                                    this.posterUrl = thumb
                                    this.episode = parsedNumber
                                    // Feed não informa season_id. Se só há uma
                                    // temporada, a atribuição é inequívoca.
                                    if (seasons.size == 1) {
                                        this.season = seasons.first().seasonNumber?.takeIf { it > 0 }
                                            ?: parseSeasonNumber(seasons.first().seasonName)
                                            ?: 1
                                    }
                                }
                                episodes.add(newEp)
                                subbedEpisodes.add(newEp)
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

        // v163: aviso de catalogo incompleto. Sem isto, uma temporada que falhou
        // as tentativas simplesmente nao existe no resultado -- o usuario via
        // "Attack on Titan" com 2 de 6 temporadas e nenhuma pista do motivo.
        // Nao poluimos a sinopse real quando o catalogo esta completo.
        if (seasonsFailed.isNotEmpty() || seasonsTruncated.isNotEmpty()) {
            val partes = mutableListOf<String>()
            if (seasonsFailed.isNotEmpty()) {
                partes.add("${seasonsFailed.size} de $seasonsRequested temporada(s) não responderam a API e estão ausentes: ${seasonsFailed.joinToString(", ")}")
            }
            if (seasonsTruncated.isNotEmpty()) {
                partes.add("Temporada(s) com episódios faltando (obtidos/declarados pela API): ${seasonsTruncated.joinToString(", ")}")
            }
            val aviso = "Aviso do plugin: o catálogo deste anime está incompleto. ${partes.joinToString(" ")} Não é um problema de reprodução — os episódios listados funcionam — mas há temporadas/linhas que a API do Tomato não entregou agora. Tente de novo mais tarde."
            Log.w(TAG, "v163: catalogo incompleto para anime $animeId -> $aviso")
            if (plot.isNullOrBlank()) {
                plot = aviso
            } else {
                plot = "$plot\n\n$aviso"
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
                // v161: separar dublado e legendado em DubStatus distintos quando disponíveis
                // Ordenar por season primeiro, depois por episode number
                val dedupSub  = subbedEpisodes.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
                val dedupDub  = dubbedEpisodes.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
                val dedupAll  = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
                when {
                    dedupSub.isNotEmpty() && dedupDub.isNotEmpty() -> {
                        addEpisodes(DubStatus.Subbed, dedupSub)
                        addEpisodes(DubStatus.Dubbed, dedupDub)
                    }
                    dedupDub.isNotEmpty() -> addEpisodes(DubStatus.Dubbed, dedupDub)
                    dedupSub.isNotEmpty() -> addEpisodes(DubStatus.Subbed, dedupSub)
                    else -> addEpisodes(DubStatus.Subbed, dedupAll)
                }
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
        @JsonProperty("episodeAnimeID") val episodeAnimeID: Int? = null,
        @JsonProperty("episode_anime_id") val episodeAnimeIdSnake: Int? = null,
        @JsonProperty("episodeHasNext") val episodeHasNext: Boolean? = null,
        @JsonProperty("showInterstitial") val showInterstitial: Boolean? = null
    )

    private fun isPolicyExpired(url: String): Boolean {
        return try {
            // v165: CloudFront assina com "Policy=" maiúsculo; busca case-insensitive
            // para cobrir variações (policy= minúsculo também documentado internamente).
            val qIdx = url.indexOf("Policy=", ignoreCase = true)
            val q = if (qIdx >= 0) url.substring(qIdx + "Policy=".length).substringBefore("&") else ""
            if (q.isEmpty()) return false
            var b64 = q
            b64 += "=".repeat((4 - b64.length % 4) % 4)
            val json = String(java.util.Base64.getUrlDecoder().decode(b64))
            val exp = Regex("\"exp\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return false
            val now = System.currentTimeMillis() / 1000L
            now >= exp
        } catch (_: Exception) { false }
    }

    // Resultado da sonda de saude da origem. O ponto central de v164: o 500
    // isolado NAO distingue origem morta de IP bloqueado, entao nao pode cortar
    // o retry. Ver comentario de healthCheck() para a medicao que motivou isso.
    private enum class Health {
        VIVA,        // rota respondeu: dispatch rodou (404/403/401/2xx)
        INDISPONIVEL, // falha de rede/timeout: nao ha resposta, so insistir e' inutil
        // 5xx: AMBIGUO. A origem pode estar morta, oscilando, ou o edge (Cloudflare)
        // pode estar bloqueando o IP. Nao cortar o retry neste estado.
        AMBIGUO_5XX
    }

    // v164 -- CORRECAO do erro de leitura da v158.
    //
    // A v158 afirmava: "500 em rota inexistente = dispatch nunca roda = origem morta
    // de forma estrutural", e usava isso para cortar o retry. tools/probe_layer_matrix.py
    // mediu a mesma matriz em 2026-09-27 e ja mostrava que a leitura nao fechava:
    //
    //                     rota inexistente   /v2/anime/1921   OPTIONS /v2/anime/1921
    //   via WARP (livre)      404                403                    204
    //   IP bloqueado          500                500                    204
    //
    // Duas conclusoes:
    //  1. Numa origem VIVA a rota inexistente devolve 404, nunca 500. A v158 estava
    //     certa em que origem morta da 500 -- mas errada em achar que 500 e' prova
    //     disso, porque o 500 de origem morta e' indistinguivel do 500 de origem
    //     oscilando. Os dois estados exigem acoplamento oposto: parar, ou insistir.
    //  2. OPTIONS devolve 204 nos DOIS casos, entao nao serve de discriminante.
    //     Era a unica alternativa pensada, e medida: descartada.
    //
    // ATENCAO -- correcao de 2026-09-28 sobre um erro meu anterior. A v164
    // registrava que o 500 sem WARP era "bloqueio de IP no edge do Cloudflare".
    // Isso estava ERRADO. Medido depois, com saida pelo Tor (IP que a API nunca
    // tinha visto, portanto nunca bloqueado):
    //   - o 500 continuava ocorrendo, em 20/20 amostras, com Tor;
    //   - o corpo era "Internal Server Error" em text/html, sem "cf-mitigated"
    //     e sem "Attention Required", que sao os sinais de bloqueio de IP
    //     (1014/1020) -- zero ocorrencias em 14/14 tentativas;
    //   - o header de erro do Cloudflare ("nel") aparecia em 100% das respostas
    //     500 e em 0% das 200, o que localiza a falha no CAMINHO, nao no
    //     bloqueio.
    // Ou seja: o 500 e' a ORIGEM devolvendo Internal Server Error, tunnelada pelo
    // Cloudflare. O IP do servidor nao esta bloqueado. O 500 medido sem WARP e o
    // 500 medido por Tor sao o MESMO fenomeno, nao dois cenarios distintos.
    //
    // SEGUNDA CORRECAO, 2026-09-28 (tarde). O bloco acima ainda estava errado,
    // e na direcao oposta. Medido com tools/ab_edge_vs_origin.py, 3 rotas x 6
    // rodadas intercaladas, mudando SO o IP de saida:
    //   DIRETO (NAT do VPS)  -> 18/18  500, Server: cloudflare, text/html, 21B
    //   TOR (proxy HTTP)     -> 18/18  200, application/json
    // Os dois lados com o MESMO Bearer, na MESMA janela, na MESMA sessao. Logo
    // o 500 NAO e' a origem: o backend responde 200 com o token que ja estava no
    // fonte, e so nao responde quando a requisicao entra pelo IP bloqueado.
    // A leitura correta e' "deteccao por IP de saida", e a prova de que a API
    // esta' viva e' o 200 do outro lado -- nao a ausencia de cf-mitigated.
    // (A confusao vem de que o Cloudflare tunnela o 500 da ORIGEM corretamente
    // quando a origem oscila, e esse 500 tem a MESMA assinatura de borda. Sao
    // dois fenomenos que se sobrepoem no corpo; so o A/B por saida separa.)
    //
    // A CORRECAO DE CODIGO da v164 continua correta, e agora pelo motivo certo:
    // cortar o retry em 5xx entrega "nenhum link" ao usuario em menos de 1s,
    // tanto quando o IP esta' bloqueado (e' preciso TROCAR de saida) quanto
    // quando a origem oscila (e' preciso INSISTIR). Nos dois casos parar e' errado.
    // Ver a regra de corte abaixo.
    //
    // CONTORNO. A solucao nao e' no parser: e' rotear a saida. O caminho que
    // FUNCIONA e' injetar o proxy no baseClient do nicehttp (app.baseClient),
    // porque todo request do plugin passa por ele.
    //
    // CORRECAO 2026-09-28 (v166). A nota abaixo (do bloco CONTORNO anterior)
    // afirmava que o Android usaria o proxy de SISTEMA via ProxySelector.getDefault().
    // Isso foi medido e esta ERRADO: configurei http_proxy_host/port no ReDroid
    // e o app continuou saindo pelo IP bloqueado. Provado ponta a ponta pelo lado
    // que funciona: ReDroid 172.17.0.2 -> proxy 172.17.0.1:10882 -> Tor ->
    // /v2/anime/1921 = 200 com payload real.
    //
    // A injecao e' OPCIONAL e nasce DESLIGADA (chave tomato_proxy_url). Ver o
    // bloco "Egress" no topo do arquivo. A entrega do .m3u8 NAO precisa do
    // proxy: o CDN abre direto, 200 medido.
    //
    // Regra adotada: cortar o retry SO quando nao ha resposta (timeout/erro de
    // rede), onde insistir e' comprovadamente inutil. Em 5xx, manter o retry e
    // apenas registrar -- e' o unico estado em que o retry ainda pode salvar.
    private suspend fun healthCheck(): Health {
        val probe = try {
            app.get("$mainUrl/zzz-nao-existe-$${PROBE_NONCE}", headers = mapOf("User-Agent" to APP_UA), timeout = 10)
        } catch (e: Exception) {
            // sem resposta nenhuma: timeout, DNS, TLS. Insistir nao ajuda.
            return Health.INDISPONIVEL
        }
        return when {
            probe.code >= 500 -> Health.AMBIGUO_5XX
            probe.code in 400..499 -> Health.VIVA  // dispatch respondeu
            else -> Health.VIVA
        }
    }

    // Sonda de diagnostico, sem efeito no controle de fluxo. Mantida porque
    // distingue 403 (credencial) de 5xx (endpoint ou edge), informacao que o
    // healthCheck() nao entrega: ele so diz se houve resposta.
    private suspend fun isOriginDown(): Boolean {
        return try {
            val res = app.get("$mainUrl/v2/animes/feed", headers = apiHeaders(), timeout = 10)
            val down = res.code >= 500
            if (down) Log.w(TAG, "diagnostico: /feed devolveu ${res.code} (edge ou endpoint, nao conclusivo)")
            down
        } catch (_: Exception) {
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
        val rawData = data.trim()
        // CloudStream normaliza data numérica relativa contra mainUrl, portanto
        // newEpisode("36959") chega aqui como https://.../36959. Aceitar somente
        // ID puro ou último segmento numérico; continuar rejeitando placeholders
        // offline no formato animeId_0.
        val epId = when {
            rawData.all { it.isDigit() } -> rawData
            rawData.contains('_') -> ""
            else -> Regex("""/(\d+)/?$""").find(rawData)?.groupValues?.getOrNull(1).orEmpty()
        }
        Log.i(TAG, "v168 loadLinks inicio data=$rawData ep=$epId")
        if (epId.isEmpty()) return false
        return try {
            // Retry no host que responde. edge.betomato.com e 0/50 medido -> fora.
            // O /stream devolve 500 com corpo plain ~80% das vezes na janela parcial,
            // entao nao ha nada a reaproveitar: cada tentativa e um GET novo.
            // Duas sondas de saude cortam o caminho quando a origem esta 100% fora,
            // que e o caso em que retry nao ajuda (medido 0/10 mesmo com 20 tentativas).
            var parsed: StreamResp? = null
            var attempt = 0
            var consecutiveFails = 0
            while (attempt < STREAM_ATTEMPTS) {
                try {
                    val host = API_HOSTS[attempt % API_HOSTS.size]
                    Log.i(TAG, "v168 consultando stream $host tentativa ${attempt + 1}")
                    val res = app.get("$host/v2/anime/episode/$epId/stream", headers = STREAM_HEADERS, timeout = 2)
                    Log.i(TAG, "v168 resposta stream $host HTTP ${res.code}")
                    if (res.code == 200) {
                        val responseText = res.text
                        val body = tryParseJson<StreamResp>(responseText)
                        Log.i(TAG, "v169 parse stream=${body?.streams != null}")
                        if (body?.streams != null) {
                            Log.i(TAG, "v168 stream resolvido via $host na tentativa ${attempt + 1}")
                            parsed = body
                            break
                        }
                    }
                    if (parece500DeBorda(res)) {
                        Log.w(TAG, "500 intermitente do edge em /stream ($host); tentando host alternativo")
                    }
                } catch (e: CancellationException) {
                    Log.w(TAG, "v168 loadLinks cancelado durante tentativa ${attempt + 1}")
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "v168 falha stream tentativa ${attempt + 1}: ${e.javaClass.simpleName}: ${e.message}")
                }
                attempt++
                consecutiveFails++
                if (attempt < STREAM_ATTEMPTS) kotlinx.coroutines.delay(RETRY_DELAY_MS)
                // v164: corte de retry so quando NAO ha resposta nenhuma. A v158
                // cortava tambem em 5xx, usando a heuristica de "origem morta" que a
                // medicao de 2026-09-27 refutou (5xx do Cloudflare = IP bloqueado,
                // e' indistinguivel de origem morta). Cortar em 5xx fazia o usuario
                // receber "nenhum link" em menos de 1s em janelas em que a API
                // voltaria -- exatamente o estado que o retry existe para cobrir.
                if (consecutiveFails % HEALTH_PROBE_AFTER == 0) {
                    when (val h = healthCheck()) {
                        Health.INDISPONIVEL -> {
                            Log.w(TAG, "origem sem resposta (timeout/rede): cortando retry apos $attempt tentativas")
                            return false
                        }
                        Health.AMBIGUO_5XX -> {
                            // Nao cortar. 5xx pode ser edge bloqueando o IP ou origem
                            // oscilando; nos dois casos o proximo request pode vir 200.
                            Log.w(TAG, "sonda 5xx ambiguo (edge bloqueando IP ou origem oscilando): mantendo retry, tentativa $attempt")
                        }
                        Health.VIVA -> {
                            Log.w(TAG, "origem viva (/stream instavel): mantendo retry, tentativa $attempt")
                            consecutiveFails = 0
                        }
                    }
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

            // v168: as URLs retornadas pela API já são playlists VOD assinadas e
            // reproduzíveis diretamente. M3u8Helper fazia uma requisição bloqueante
            // extra por qualidade e podia ultrapassar o ciclo de loadLinks do player,
            // que cancelava a coroutine antes de qualquer callback. Emitimos os links
            // conhecidos imediatamente e deixamos o ExoPlayer ler a playlist.
            val streamHeaders = mapOf("User-Agent" to APP_UA)
            for ((url, label, qual) in candidates.distinctBy { it.first }) {
                val u = url ?: continue
                Log.i(TAG, "v168 emitindo $label: ${u.substringBefore('?')}")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name $label",
                        url = u,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = qual
                        this.headers = streamHeaders
                    }
                )
                found = true
            }
            found
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}
