import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Teste: o Jsoup consegue encontrar data-cs-poster em HTML grande (simulando o
 * HTML de 10.9MB que o WebView devolve com capas embutidas)?
 */
class TestJsoupPoster {

    private fun makeBigHtml(): String {
        // data URL fake pequena (seria grande na vida real)
        val dataUrl = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q=="
        val sb = StringBuilder()
        sb.append("<html><head>")
        sb.append("<meta property=\"og:image\" content=\"https://redecanais.af/imgs-videos/Filmes/Real.jpg\" data-cs-poster=\"$dataUrl\">")
        sb.append("</head><body>")
        // simula 8 cards com data-cs-poster
        repeat(8) { i ->
            sb.append("""
                <li class="pm-video-thumb">
                    <a href="https://redecanais.af/filme-$i.html">
                        <img data-echo="https://redecanais.af/imgs-videos/Filmes/Filme%20$i.jpg" src="https://redecanais.af/imgs-videos/echo-lzld.png" data-cs-poster="$dataUrl">
                        <h3>Filme $i</h3>
                    </a>
                </li>
            """.trimIndent())
        }
        // enche o HTML até ~11MB com conteúdo inofensivo para simular o tamanho real
        sb.append("<!--")
        repeat(11000000) { sb.append('x') }
        sb.append("-->")
        sb.append("</body></html>")
        return sb.toString()
    }

    @Test
    fun testIsPlaceholderImageComDataUrlGrande() {
        println("=== TESTE: isPlaceholderImage com data URL grande (contém '1x1') ===")
        // replica a lógica CORRIGIDA (v110): data URLs de imagem são legítimas
        val placeholderPatterns = listOf(
            "echo-lzld", "blank.gif", "pixel.gif", "no-thumbnail",
            "default-thumbnail", "lazy.png", "1x1", "data:image/gif;base64,R0lGOD"
        )
        fun isPlaceholderImage(url: String): Boolean {
            if (url.isBlank()) return true
            if (url.startsWith("data:image/", ignoreCase = true)) {
                return url.startsWith("data:image/svg+xml", ignoreCase = true) && url.length < 200
            }
            if (url.startsWith("data:image/svg+xml", ignoreCase = true) && url.length < 200) return true
            return placeholderPatterns.any { url.contains(it, ignoreCase = true) }
        }

        // data URL grande simulando capa real (base64 aleatório contém "1x1")
        val fakeBase64 = java.util.Base64.getEncoder().encodeToString(
            ByteArray(150000) { (it * 31 % 251).toByte() }
        )
        val bigDataUrl = "data:image/jpeg;base64,$fakeBase64"
        println("data URL tamanho: ${bigDataUrl.length}")
        println("contém '1x1': ${bigDataUrl.contains("1x1")}")
        assertEquals("data URL grande NÃO pode ser placeholder", false, isPlaceholderImage(bigDataUrl))

        // svg pequeno (1px) continua sendo placeholder
        assertEquals("svg pequeno é placeholder", true, isPlaceholderImage("data:image/svg+xml;utf8,<svg/>"))
        // URL HTTP placeholder continua rejeitada
        assertEquals("URL HTTP com echo-lzld é placeholder", true, isPlaceholderImage("https://redecanais.af/imgs-videos/echo-lzld.png"))
        // URL HTTP real é aceita
        assertEquals("URL HTTP real é aceita", false, isPlaceholderImage("https://redecanais.af/imgs-videos/Filmes/Capa.jpg"))
    }

    @Test
    fun testFindDataCsPosterInBigHtml() {
        println("=== TESTE: Jsoup + data-cs-poster em HTML de ~11MB ===")
        val html = makeBigHtml()
        println("tamanho do HTML simulado: ${html.length} chars (~${html.length / 1048576.0}MB)")
        val doc: Document = Jsoup.parse(html)
        println("parse OK — tamanho do doc: ${doc.html().length}")

        // 1. img[data-cs-poster]
        val imgEl = doc.selectFirst("img[data-cs-poster]")
        println("img[data-cs-poster]: ${if (imgEl != null) "ACHADO (prefixo: ${imgEl.attr("data-cs-poster").take(30)})" else "NÃO ACHADO"}")
        assertNotNull("img[data-cs-poster] deveria ser encontrado", imgEl)

        // 2. meta[property='og:image'][data-cs-poster]
        val metaEl = doc.selectFirst("meta[property='og:image'][data-cs-poster]")
        println("meta[og:image][data-cs-poster]: ${if (metaEl != null) "ACHADO (prefixo: ${metaEl.attr("data-cs-poster").take(30)})" else "NÃO ACHADO"}")
        assertNotNull("meta[og:image][data-cs-poster] deveria ser encontrado", metaEl)

        // 3. contagem
        val count = doc.select("[data-cs-poster]").size
        println("total [data-cs-poster]: $count")
        assertTrue("deveria ter pelo menos 9 (8 imgs + 1 meta)", count >= 9)
    }
}
