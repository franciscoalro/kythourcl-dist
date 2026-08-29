import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suíte de Testes Kotlin — Estrutura das Capas (Posters) da Página Inicial.
 *
 * Replica fielmente a lógica ATUAL de extração de capas do provider
 * (RedeCanaisAF.kt v130+):
 *   - extractPosterFromCardElement(): itera TODAS as imgs, retorna
 *     a primeira URL válida via extractPosterFromSingleImg()
 *   - extractPosterFromSingleImg(): prioridade:
 *       1. data-cs-poster (capa WebView embutida)
 *       2. data-echo
 *       3. data-src / data-original
 *       4. data-srcset (parseSrcset)
 *       5. src
 *       6. srcset (parseSrcset)
 *   - isPlaceholderImage(): data URIs de imagem são legítimas (exceto SVG < 200 chars)
 *   - parseSrcset(): parser completo (descritores w/x, seleciona maior resolução)
 *   - optimizePosterUrl() / fixUrl(): URL absoluta + %20
 */
class TestRedeCanaisAFPosters {

    private val MAIN_URL = "https://redecanais.af"

    private val PLACEHOLDER_PATTERNS = listOf(
        "echo-lzld",
        "blank.gif",
        "pixel.gif",
        "no-thumbnail",
        "default-thumbnail",
        "lazy.png",
        "1x1",
        "data:image/gif;base64,R0lGOD"
    )

    // === Replicação exata da lógica do provider ===

    private fun isPlaceholderImage(url: String): Boolean {
        if (url.isBlank()) return true
        // v110: data URIs de imagem (capas embutidas pelo WebView via data-cs-poster) são
        // legítimas — aplicar padrões de texto ao base64 aleatório descarta capas reais
        // (ex: "1x1" aparece com frequência em base64 de JPEG). Só rejeita SVG pequeno (1px).
        if (url.startsWith("data:image/", ignoreCase = true)) {
            return url.startsWith("data:image/svg+xml", ignoreCase = true) && url.length < 200
        }
        if (url.startsWith("data:image/svg+xml", ignoreCase = true) && url.length < 200) return true
        return PLACEHOLDER_PATTERNS.any { url.contains(it, ignoreCase = true) }
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return "$MAIN_URL$url"
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return "$MAIN_URL/$url"
        }
        return url
    }

    private fun optimizePosterUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank() || isPlaceholderImage(trimmed)) return ""
        if (trimmed.startsWith("data:image/", ignoreCase = true)) return trimmed
        val absoluteUrl = fixUrl(trimmed)
        return absoluteUrl.replace(" ", "%20")
    }

    /**
     * Parser de srcset (HTML spec).
     * Formato: "url descriptor, url descriptor, ..."
     *   - descriptor pode ser "300w" (largura), "2x" (densidade) ou ausente (=1x)
     * Retorna a URL do candidato de MAIOR resolução, normalizada, ou "" se nenhum válido.
     */
    private fun parseSrcset(srcset: String): String {
        if (srcset.isBlank()) return ""
        val candidates = srcset.split(",").mapNotNull { raw ->
            val entry = raw.trim()
            if (entry.isEmpty()) return@mapNotNull null
            // Descritor é o ULTIMO token whitespace-separated se parecer "300w"/"2x"/"1.5x".
            // URLs com espaço NÃO podem ser truncadas no primeiro espaço — por isso o split
            // é feito pelo fim: pega-se tudo antes do descritor como URL.
            val descriptorMatch = Regex("^(.*?)[\\s]+(\\d+(?:\\.\\d+)?[wx]|\\d+(?:\\.\\d+)?)$", RegexOption.IGNORE_CASE)
                .matchEntire(entry)
            val urlRaw: String
            val descriptor: String
            if (descriptorMatch != null) {
                urlRaw = descriptorMatch.groupValues[1].trim()
                descriptor = descriptorMatch.groupValues[2]
            } else {
                urlRaw = entry
                descriptor = ""
            }
            if (urlRaw.isEmpty()) return@mapNotNull null
            // desnormalizar: srcset costuma ter %20 já, mas garantimos espaços p/ seleção
            val urlFixed = urlRaw.replace("%20", " ")
            // parse do descriptor -> (largura, densidade)
            val w = Regex("^(\\d+)w$", RegexOption.IGNORE_CASE).matchEntire(descriptor)?.groupValues?.get(1)?.toIntOrNull()
            val d = if (w == null) {
                Regex("^([\\d.]+)x$", RegexOption.IGNORE_CASE).matchEntire(descriptor)?.groupValues?.get(1)?.toFloatOrNull() ?: 1.0f
            } else 1.0f
            val normalized = optimizePosterUrl(urlFixed)
            if (normalized.isBlank() || isPlaceholderImage(normalized)) return@mapNotNull null
            Triple(normalized, w ?: -1, d)
        }
        // seleciona o de maior largura; em empate, maior densidade; senão o primeiro
        val best = candidates.maxWithOrNull(
            compareBy<Triple<String, Int, Float>> { it.second }
                .thenBy { it.third }
                .thenBy { -it.first.length }
        )
        return best?.first ?: ""
    }

    /** Replica extractPosterFromSingleImg(img) do provider (v130+). */
    private fun extractPosterFromSingleImg(img: Element): String {
        // v109: data-cs-poster (capa embutida pelo WebView) tem prioridade máxima
        val csPoster = img.attr("data-cs-poster").trim()
        if (csPoster.isNotBlank() && !isPlaceholderImage(csPoster) && csPoster != img.attr("src")) {
            return csPoster
        }

        val dataEcho = img.attr("data-echo").trim()
        if (dataEcho.isNotBlank() && !isPlaceholderImage(dataEcho)) {
            return dataEcho
        }

        val dataSrc = img.attr("data-src").ifBlank { img.attr("data-original") }.trim()
        if (dataSrc.isNotBlank() && !isPlaceholderImage(dataSrc)) {
            return dataSrc
        }

        // data-srcset (lazy srcset): verificar antes de src
        val dataSrcset = img.attr("data-srcset").trim()
        if (dataSrcset.isNotBlank()) {
            val fromSrcset = parseSrcset(dataSrcset)
            if (fromSrcset.isNotBlank()) return fromSrcset
        }

        val src = img.attr("src").trim()
        if (src.isNotBlank() && !isPlaceholderImage(src) && !src.startsWith("data:", true)) {
            return src
        }

        // srcset direto: só usado se src for placeholder
        val srcset = img.attr("srcset").trim()
        if (srcset.isNotBlank()) {
            val fromSrcset = parseSrcset(srcset)
            if (fromSrcset.isNotBlank()) return fromSrcset
        }

        return ""
    }

    /** Replica extractPosterFromCardElement(element) do provider (v130+) — itera TODAS as imgs. */
    private fun extractPosterFromCardElement(element: Element): String {
        val imgs = element.select("img")
        if (imgs.isEmpty()) return ""

        // Percorre TODAS as imgs do card: se a 1a for placeholder,
        // tenta as seguintes antes de desistir.
        for (img in imgs) {
            val found = extractPosterFromSingleImg(img)
            if (found.isNotBlank()) return found
        }
        return ""
    }

    /** Replica a parte de capa do parseCard(element) do provider. */
    private fun posterOfCard(card: Element): String {
        val rawPoster = extractPosterFromCardElement(card)
        return optimizePosterUrl(rawPoster)
    }

    // === Helpers HTML ===

    /** Configuração de uma única tag <img> dentro do card. */
    private data class ImgConfig(
        val dataCsPoster: String = "",
        val dataEcho: String = "",
        val dataSrc: String = "",
        val dataSrcset: String = "",
        val src: String = "",
        val srcset: String = ""
    ) {
        /** Gera o HTML da tag <img> com os atributos configurados. */
        fun toHtml(alt: String): String {
            val attrs = buildString {
                if (dataCsPoster.isNotBlank()) append(""" data-cs-poster="$dataCsPoster"""")
                if (dataEcho.isNotBlank()) append(""" data-echo="$dataEcho"""")
                if (dataSrc.isNotBlank()) append(""" data-src="$dataSrc"""")
                if (dataSrcset.isNotBlank()) append(""" data-srcset="$dataSrcset"""")
                append(""" src="$src"""")
                if (srcset.isNotBlank()) append(""" srcset="$srcset"""")
            }
            return "<img$attrs alt=\"$alt\">"
        }
    }

    /** Monta um card .pm-video-thumb igual ao da home do PHP Melody. */
    private fun card(
        title: String,
        href: String,
        imgs: List<ImgConfig> = listOf(ImgConfig())
    ): Element {
        val imgsHtml = imgs.joinToString("\n                        ") { it.toHtml(title) }
        val html = """
            <li class="pm-video-thumb">
                <a href="$href">
                    <div class="pm-video-thumb-img">
                        $imgsHtml
                    </div>
                    <h3 class="title">$title</h3>
                </a>
            </li>
        """.trimIndent()
        return Jsoup.parseBodyFragment(html).selectFirst(".pm-video-thumb")!!
    }

    // === Testes ===

    @Test
    fun testPosterDataEchoPriority() {
        println("=== TESTE 1: data-echo tem prioridade sobre src placeholder ===")
        val c = card(
            title = "George Washington (Dublado)",
            href = "https://redecanais.af/george-washington-dublado_abc123.html",
            imgs = listOf(ImgConfig(
                dataEcho = "https://redecanais.af/imgs-videos/Filmes/George%20Washington.jpg",
                src = "https://redecanais.af/imgs-videos/echo-lzld.png"
            ))
        )
        val poster = posterOfCard(c)
        println("  URL da imagem retornada: $poster")
        assertEquals("https://redecanais.af/imgs-videos/Filmes/George%20Washington.jpg", poster)
        assertFalse(poster.startsWith("data:"))
        assertFalse(isPlaceholderImage(poster))
    }

    @Test
    fun testPosterSrcFallbackWhenEchoPlaceholder() {
        println("=== TESTE 2: src real usado quando data-echo é placeholder ===")
        val c = card(
            title = "Futurama",
            href = "https://redecanais.af/futurama-dublado_xyz789.html",
            imgs = listOf(ImgConfig(
                dataEcho = "https://redecanais.af/imgs-videos/echo-lzld.png",
                src = "https://redecanais.af/imgs-videos/Series/Futurama%20-%20Capa.jpg"
            ))
        )
        val poster = posterOfCard(c)
        println("  URL da imagem retornada: $poster")
        assertEquals("https://redecanais.af/imgs-videos/Series/Futurama%20-%20Capa.jpg", poster)
        assertFalse(isPlaceholderImage(poster))
    }

    @Test
    fun testPosterDataSrcFallback() {
        println("=== TESTE 3: data-src usado quando não há data-echo ===")
        val c = card(
            title = "Avatar",
            href = "https://redecanais.af/avatar-dublado_123abc.html",
            imgs = listOf(ImgConfig(
                dataSrc = "https://redecanais.af/imgs-videos/Filmes/Avatar%202022.jpg",
                src = "data:image/gif;base64,R0lGODlhAQABAAAAACw="
            ))
        )
        val poster = posterOfCard(c)
        println("  URL da imagem retornada: $poster")
        assertEquals("https://redecanais.af/imgs-videos/Filmes/Avatar%202022.jpg", poster)
        assertFalse(poster.startsWith("data:"))
    }

    @Test
    fun testPosterRejectsAllPlaceholders() {
        println("=== TESTE 4: card só com placeholders -> vazio ===")
        val c = card(
            title = "Sem Capa",
            href = "https://redecanais.af/sem-capa_000.html",
            imgs = listOf(ImgConfig(
                dataEcho = "https://redecanais.af/imgs-videos/echo-lzld.png",
                src = "https://redecanais.af/imgs-videos/blank.gif"
            ))
        )
        val poster = posterOfCard(c)
        println("  URL da imagem retornada (esperado vazio): '$poster'")
        assertEquals("", poster)
    }

    @Test
    fun testPosterRelativeUrlNormalization() {
        println("=== TESTE 5: URL relativa vira absoluta + espaço vira %20 ===")
        val c = card(
            title = "Top Gun",
            href = "https://redecanais.af/top-gun-dublado_456def.html",
            imgs = listOf(ImgConfig(
                dataEcho = "/imgs-videos/Filmes/Top Gun Maverick.jpg"
            ))
        )
        val poster = posterOfCard(c)
        println("  URL da imagem retornada: $poster")
        assertEquals("https://redecanais.af/imgs-videos/Filmes/Top%20Gun%20Maverick.jpg", poster)
    }

    @Test
    fun testPosterDataCsPosterHasTopPriority() {
        println("=== TESTE 6: data-cs-poster tem prioridade MÁXIMA (acima de data-echo) ===")
        // data-cs-poster é definido pelo WebView (CloudflareSolver) e contém a capa real
        // embutida como base64 ou URL. O parser atual lê data-cs-poster primeiro.
        val c = card(
            title = "Filme com Cloudflare",
            href = "https://redecanais.af/filme-cf_123.html",
            imgs = listOf(ImgConfig(
                dataCsPoster = "https://redecanais.af/imgs-videos/Filmes/Capa%20Real%20CF.jpg",
                dataEcho = "https://redecanais.af/imgs-videos/echo-lzld.png",
                src = "https://redecanais.af/imgs-videos/blank.gif"
            ))
        )
        val poster = posterOfCard(c)
        println("  URL da imagem retornada: $poster")
        assertEquals("https://redecanais.af/imgs-videos/Filmes/Capa%20Real%20CF.jpg", poster)
        assertFalse(isPlaceholderImage(poster))
    }

    @Test
    fun testPosterDataCsPosterSkipsWhenSameAsSrc() {
        println("=== TESTE 7: data-cs-poster ignorado quando igual a src (evita duplicação) ===")
        // Se data-cs-poster == src, é porque o WebView copiou o placeholder para ambos;
        // o parser deve cair para o próximo atributo (data-echo, etc.)
        val c = card(
            title = "Filme Duplicado",
            href = "https://redecanais.af/filme-dup_456.html",
            imgs = listOf(ImgConfig(
                dataCsPoster = "https://redecanais.af/imgs-videos/echo-lzld.png",
                dataEcho = "https://redecanais.af/imgs-videos/Filmes/Capa%20Verdadeira.jpg",
                src = "https://redecanais.af/imgs-videos/echo-lzld.png"
            ))
        )
        val poster = posterOfCard(c)
        println("  URL da imagem retornada: $poster")
        // data-cs-poster == src (placeholder), então pula -> data-echo é usado
        assertEquals("https://redecanais.af/imgs-videos/Filmes/Capa%20Verdadeira.jpg", poster)
    }

    @Test
    fun testPosterMultiImageFallback() {
        println("=== TESTE 8: card com 2 imgs — 1a placeholder, 2a válida ===")
        // Caso real: alguns cards do PHP Melody têm <img> placeholder + <img> real
        val c = card(
            title = "Multi Imagem",
            href = "https://redecanais.af/multi-img_789.html",
            imgs = listOf(
                ImgConfig(
                    src = "https://redecanais.af/imgs-videos/echo-lzld.png"
                ),
                ImgConfig(
                    dataEcho = "https://redecanais.af/imgs-videos/Filmes/Segunda%20Imagem.jpg"
                )
            )
        )
        val poster = posterOfCard(c)
        println("  URL da imagem retornada: $poster")
        assertEquals("https://redecanais.af/imgs-videos/Filmes/Segunda%20Imagem.jpg", poster)
    }

    @Test
    fun testPosterMultiImageBothPlaceholders() {
        println("=== TESTE 9: card com 2 imgs, ambas placeholder -> vazio ===")
        val c = card(
            title = "Sem Capa Multi",
            href = "https://redecanais.af/sem-capa-multi_000.html",
            imgs = listOf(
                ImgConfig(src = "https://redecanais.af/imgs-videos/echo-lzld.png"),
                ImgConfig(src = "https://redecanais.af/imgs-videos/blank.gif")
            )
        )
        val poster = posterOfCard(c)
        println("  URL da imagem retornada (esperado vazio): '$poster'")
        assertEquals("", poster)
    }

    @Test
    fun testPosterDataSrcsetParsed() {
        println("=== TESTE 10: data-srcset é parseado e retorna URL de maior resolução ===")
        val c = card(
            title = "Filme com srcset",
            href = "https://redecanais.af/filme-srcset_111.html",
            imgs = listOf(ImgConfig(
                dataSrcset = "https://redecanais.af/imgs-videos/Filmes/Capa%20Pequena.jpg 300w, https://redecanais.af/imgs-videos/Filmes/Capa%20Grande.jpg 800w",
                src = "https://redecanais.af/imgs-videos/echo-lzld.png"
            ))
        )
        val poster = posterOfCard(c)
        println("  URL da imagem retornada: $poster")
        // Deve escolher a de 800w (maior resolução)
        assertEquals("https://redecanais.af/imgs-videos/Filmes/Capa%20Grande.jpg", poster)
    }

    @Test
    fun testPosterSrcsetFallback() {
        println("=== TESTE 11: srcset (direto) usado como fallback quando src é placeholder ===")
        val c = card(
            title = "Filme srcset direto",
            href = "https://redecanais.af/filme-srcset2_222.html",
            imgs = listOf(ImgConfig(
                src = "https://redecanais.af/imgs-videos/echo-lzld.png",
                srcset = "https://redecanais.af/imgs-videos/Filmes/Capa%20HD.jpg 2x, https://redecanais.af/imgs-videos/Filmes/Capa%20SD.jpg 1x"
            ))
        )
        val poster = posterOfCard(c)
        println("  URL da imagem retornada: $poster")
        // Deve escolher 2x (maior densidade)
        assertEquals("https://redecanais.af/imgs-videos/Filmes/Capa%20HD.jpg", poster)
    }

    @Test
    fun testPosterSrcsetWithSpaceInUrl() {
        println("=== TESTE 12: srcset com espaço na URL (ex: 'Top Gun 2022.jpg 300w') ===")
        val c = card(
            title = "Top Gun 2",
            href = "https://redecanais.af/top-gun-2_333.html",
            imgs = listOf(ImgConfig(
                dataSrcset = "/imgs-videos/Filmes/Top Gun 2022.jpg 300w, /imgs-videos/Filmes/Top Gun 2022 HD.jpg 1200w",
                src = "https://redecanais.af/imgs-videos/echo-lzld.png"
            ))
        )
        val poster = posterOfCard(c)
        println("  URL da imagem retornada: $poster")
        // Deve escolher 1200w e normalizar: espaço vira %20, URL relativa vira absoluta
        assertEquals("https://redecanais.af/imgs-videos/Filmes/Top%20Gun%202022%20HD.jpg", poster)
    }

    @Test
    fun testHomePageCardsReturnPosterUrls() {
        println("=== TESTE 13: página inicial simulada — cada card retorna a URL da capa ===")
        val homeHtml = """
            <html><body>
                <ul class="pm-videos-list">
                    <li class="pm-video-thumb">
                        <a href="https://redecanais.af/george-washington-dublado_abc.html">
                            <img data-echo="https://redecanais.af/imgs-videos/Filmes/George%20Washington.jpg" src="https://redecanais.af/imgs-videos/echo-lzld.png">
                            <h3>George Washington (Dublado)</h3>
                        </a>
                    </li>
                    <li class="pm-video-thumb">
                        <a href="https://redecanais.af/futurama-dublado_xyz.html">
                            <img data-echo="https://redecanais.af/imgs-videos/Series/Futurama%20Capa.jpg" src="https://redecanais.af/imgs-videos/echo-lzld.png">
                            <h3>Futurama (Dublado)</h3>
                        </a>
                    </li>
                    <li class="pm-video-thumb">
                        <a href="https://redecanais.af/naruto-animes_999.html">
                            <img src="https://redecanais.af/imgs-videos/Animes/Naruto%20Shippuden.jpg">
                            <h3>Naruto Shippuden</h3>
                        </a>
                    </li>
                </ul>
            </body></html>
        """.trimIndent()
        val doc: Document = Jsoup.parse(homeHtml)
        val cards = doc.select("div.pm-video-thumb, li.pm-video-thumb, .pm-video-thumb")

        assertTrue("Deveria encontrar 3 cards na home", cards.size == 3)

        val urls = cards.map { posterOfCard(it) }
        urls.forEachIndexed { i, url ->
            println("  Card[${i + 1}] URL da capa: $url")
            assertTrue("Card[${i + 1}] deveria ter URL HTTP de imagem", url.startsWith("https://"))
            assertFalse("Card[${i + 1}] não pode ser data URI", url.startsWith("data:"))
            assertFalse("Card[${i + 1}] não pode ser placeholder", isPlaceholderImage(url))
        }

        // O requisito central: TODOS os cards da home precisam retornar uma URL de imagem
        assertTrue("Todos os 3 cards deveriam retornar URL de imagem", urls.all { it.isNotBlank() })
        println("  >> SUCCESS: todas as capas da home retornaram URL de imagem válida")
    }

    @Test
    fun testPosterUrlStructureValid() {
        println("=== TESTE 14: validação estrutural da URL da capa ===")
        val c = card(
            title = "Minions",
            href = "https://redecanais.af/minions-dublado_777.html",
            imgs = listOf(ImgConfig(
                dataEcho = "https://redecanais.af/imgs-videos/Filmes/Minions%202%20-%20A%20Origem%20de%20Gru.jpg"
            ))
        )
        val poster = posterOfCard(c)
        assertNotNull(poster)
        assertTrue(poster.isNotBlank())
        assertTrue("Deve começar com https", poster.startsWith("https://"))
        assertTrue("Deve conter /imgs-videos/", poster.contains("/imgs-videos/"))
        assertTrue("Deve terminar em imagem", Regex("""\.(jpg|jpeg|png|webp)($|\?)""", RegexOption.IGNORE_CASE).containsMatchIn(poster))
        assertFalse("Não deve conter espaço cru", poster.contains(" "))
        println("  URL validada: $poster")
    }

    @Test
    fun testPosterDataCsPosterBase64DataUrl() {
        println("=== TESTE 15: data-cs-poster com base64 data URL é aceito (não é placeholder) ===")
        // data:image/jpeg;base64,AAAA... é uma capa real embutida pelo WebView
        val c = card(
            title = "Filme Base64",
            href = "https://redecanais.af/filme-b64_555.html",
            imgs = listOf(ImgConfig(
                dataCsPoster = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYnKSopGR8tMC0oMCUoKSj/2wBDAQcHBwoIChMKChMoGhYaKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCj/wAARCA",
                src = "https://redecanais.af/imgs-videos/echo-lzld.png"
            ))
        )
        val poster = posterOfCard(c)
        println("  URL da imagem retornada (prefixo): ${poster.take(60)}...")
        assertTrue(poster.startsWith("data:image/jpeg;base64"))
        // IsPlaceholderImage para data:image/jpeg;base64,AAAA... deve retornar false
        // (não é SVG pequeno, não contém padrões de placeholder)
        assertFalse("data:image/jpeg;base64 NÃO pode ser considerado placeholder", isPlaceholderImage(poster))
    }
}