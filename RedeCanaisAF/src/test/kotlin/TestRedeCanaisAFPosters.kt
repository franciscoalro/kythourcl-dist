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
 * Replica fielmente a lógica atual de extração de capas do provider
 * (RedeCanaisAF.kt):
 *   - extractPosterFromCardElement(): data-echo -> data-src/data-original -> src
 *   - isPlaceholderImage(): PLACEHOLDER_PATTERNS (echo-lzld, blank.gif, pixel.gif, ...)
 *   - optimizePosterUrl() / fixUrl(): URL absoluta + %20
 *
 * Os testes retornam/imprimem a URL da imagem extraída de cada card simulado.
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

    /** Replica extractPosterFromCardElement(element) do provider. */
    private fun extractPosterFromCardElement(element: Element): String {
        val img = element.selectFirst("img") ?: return ""

        val dataEcho = img.attr("data-echo").trim()
        if (dataEcho.isNotBlank() && !isPlaceholderImage(dataEcho)) {
            return dataEcho
        }

        val dataSrc = img.attr("data-src").ifBlank { img.attr("data-original") }.trim()
        if (dataSrc.isNotBlank() && !isPlaceholderImage(dataSrc)) {
            return dataSrc
        }

        val src = img.attr("src").trim()
        if (src.isNotBlank() && !isPlaceholderImage(src) && !src.startsWith("data:", true)) {
            return src
        }

        return ""
    }

    /** Replica a parte de capa do parseCard(element) do provider. */
    private fun posterOfCard(card: Element): String {
        val rawPoster = extractPosterFromCardElement(card)
        return optimizePosterUrl(rawPoster)
    }

    /** Monta um card .pm-video-thumb igual ao da home do PHP Melody. */
    private fun card(
        title: String,
        href: String,
        dataEcho: String = "",
        dataSrc: String = "",
        src: String = ""
    ): Element {
        val imgAttrs = buildString {
            if (dataEcho.isNotBlank()) append(""" data-echo="$dataEcho"""")
            if (dataSrc.isNotBlank()) append(""" data-src="$dataSrc"""")
            append(""" src="$src"""")
        }
        val html = """
            <li class="pm-video-thumb">
                <a href="$href">
                    <div class="pm-video-thumb-img">
                        <img$imgAttrs alt="$title">
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
            dataEcho = "https://redecanais.af/imgs-videos/Filmes/George%20Washington.jpg",
            src = "https://redecanais.af/imgs-videos/echo-lzld.png"
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
            dataEcho = "https://redecanais.af/imgs-videos/echo-lzld.png",
            src = "https://redecanais.af/imgs-videos/Series/Futurama%20-%20Capa.jpg"
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
            dataSrc = "https://redecanais.af/imgs-videos/Filmes/Avatar%202022.jpg",
            src = "data:image/gif;base64,R0lGODlhAQABAAAAACw="
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
            dataEcho = "https://redecanais.af/imgs-videos/echo-lzld.png",
            src = "https://redecanais.af/imgs-videos/blank.gif"
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
            dataEcho = "/imgs-videos/Filmes/Top Gun Maverick.jpg"
        )
        val poster = posterOfCard(c)
        println("  URL da imagem retornada: $poster")
        assertEquals("https://redecanais.af/imgs-videos/Filmes/Top%20Gun%20Maverick.jpg", poster)
    }

    @Test
    fun testPosterDataCsPosterNotReadByCurrentParser() {
        println("=== TESTE 6: data-cs-poster (WebView) NÃO é lido pelo parser atual ===")
        // O JS do CloudflareSolver grava data-cs-poster, mas o extractPosterFromCardElement
        // atual só lê data-echo/data-src/src. Documenta o comportamento atual.
        val html = """
            <li class="pm-video-thumb">
                <a href="https://redecanais.af/filme_123.html">
                    <img data-cs-poster="data:image/jpeg;base64,AAAA" src="https://redecanais.af/imgs-videos/echo-lzld.png">
                    <h3>Filme X</h3>
                </a>
            </li>
        """.trimIndent()
        val el = Jsoup.parseBodyFragment(html).selectFirst(".pm-video-thumb")!!
        val poster = posterOfCard(el)
        println("  URL da imagem retornada (esperado vazio — data-cs-poster ignorado): '$poster'")
        assertEquals("", poster)
    }

    @Test
    fun testHomePageCardsReturnPosterUrls() {
        println("=== TESTE 7: página inicial simulada — cada card retorna a URL da capa ===")
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
        println("=== TESTE 8: validação estrutural da URL da capa ===")
        val c = card(
            title = "Minions",
            href = "https://redecanais.af/minions-dublado_777.html",
            dataEcho = "https://redecanais.af/imgs-videos/Filmes/Minions%202%20-%20A%20Origem%20de%20Gru.jpg"
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
}
