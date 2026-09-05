import com.NetCine.CaptchaDetector
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class TestNetCineUnit {

    companion object {
        val iframeRegex = Regex("""<div\s+id="(play-\d+)"[^>]*>.*?<iframe\s+src="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
        val labelRegex = Regex("""label\s*:\s*["']([^"']+)["']""")
        val videoSourceRegex = Regex("""href\s*=\s*["']([^"']*(?:hls\.php|hlsarchive\.php\?hls|gc\d+\.php|playerarchive\.php)[^"']*)["']""")
        val nextRegex = Regex("""next|pr[oó]ximo""", RegexOption.IGNORE_CASE)

        fun cleanTitle(rawTitle: String): String {
            return rawTitle
                .replace(Regex("""^Assistir\s+""", RegexOption.IGNORE_CASE), "")
                .substringBefore(" Online")
                .substringBefore(" em HD")
                .substringBefore(" no NetCine")
                .substringBefore(" - NetCine")
                .substringBefore(" Dublado")
                .substringBefore(" Legendado")
                .trim()
        }
    }

    @Test
    fun testTitleCleaning() {
        assertEquals("Vingadores: Ultimato", cleanTitle("Assistir Vingadores: Ultimato Online em HD"))
        assertEquals("Breaking Bad", cleanTitle("Assistir Breaking Bad Dublado - NetCine"))
        assertEquals("Inception", cleanTitle("Inception Legendado no NetCine"))
        assertEquals("Interestelar", cleanTitle("Assistir Interestelar Online Grátis"))
    }

    @Test
    fun testIframeRegex() {
        val html = """
            <div id="play-1" class="tab-pane">
                <iframe src="https://media-player.lat/embed/video123" width="100%"></iframe>
            </div>
            <div id="play-2" class="tab-pane">
                <iframe src="https://media-player.lat/embed/video456"></iframe>
            </div>
        """.trimIndent()

        val matches = iframeRegex.findAll(html).toList()
        assertEquals(2, matches.size)
        assertEquals("play-1", matches[0].groupValues[1])
        assertEquals("https://media-player.lat/embed/video123", matches[0].groupValues[2])
        assertEquals("play-2", matches[1].groupValues[1])
        assertEquals("https://media-player.lat/embed/video456", matches[1].groupValues[2])
    }

    @Test
    fun testVideoSourceRegex() {
        val html = """
            <a href="https://cdn1.lat/hls.php?token=abc" class="button">Servidor 1</a>
            <a href="https://cdn2.lat/playerarchive.php?v=xyz" class="button">Servidor 2</a>
            <a href="https://cdn3.lat/gc10.php?file=123" class="button">Servidor 3</a>
        """.trimIndent()

        val matches = videoSourceRegex.findAll(html).toList()
        assertEquals(3, matches.size)
        assertTrue(matches[0].groupValues[1].contains("hls.php"))
        assertTrue(matches[1].groupValues[1].contains("playerarchive.php"))
        assertTrue(matches[2].groupValues[1].contains("gc10.php"))
    }

    @Test
    fun testLabelRegex() {
        val sampleJs = """
            var player = { label: "1080p Full HD", file: "https://example.com/master.m3u8" };
        """.trimIndent()
        val match = labelRegex.find(sampleJs)
        assertNotNull(match)
        assertEquals("1080p Full HD", match?.groupValues?.get(1))
    }

    @Test
    fun testNextPaginationRegex() {
        assertTrue(nextRegex.containsMatchIn("Próximo"))
        assertTrue(nextRegex.containsMatchIn("proximo"))
        assertTrue(nextRegex.containsMatchIn("Next Page"))
        assertFalse(nextRegex.containsMatchIn("Anterior"))
    }

    @Test
    fun testCaptchaDetector() {
        val captchaDoc = """
            <html>
                <body>
                    <h2>Verificação Humana</h2>
                    <img src="/captcha.php?captcha_img=1" />
                    <input type="text" name="captcha_input" />
                </body>
            </html>
        """.trimIndent()
        assertTrue(CaptchaDetector.looksLikeCaptcha(captchaDoc))

        val cleanDoc = """
            <html><body><div><iframe src="https://stream.lat/embed"></iframe></div></body></html>
        """.trimIndent()
        assertFalse(CaptchaDetector.looksLikeCaptcha(cleanDoc))

        val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val fakeJpg = "<html>not png</html>".toByteArray()
        assertTrue(CaptchaDetector.isCaptchaPng(fakePng))
        assertFalse(CaptchaDetector.isCaptchaPng(fakeJpg))
    }

    @Test
    fun testJsoupSearchResultExtraction() {
        val html = """
            <div class="movie">
                <a href="https://nnn1.lat/filme/matrix/">
                    <img data-src="https://img.nnn1.lat/matrix.jpg" alt="Matrix" />
                    <h2>Matrix (1999)</h2>
                </a>
            </div>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val item = doc.selectFirst("div.movie")
        assertNotNull(item)
        val title = item?.selectFirst("h2")?.text()?.trim()
        val href = item?.selectFirst("a")?.attr("href")
        val img = item?.selectFirst("img")?.attr("data-src")

        assertEquals("Matrix (1999)", title)
        assertEquals("https://nnn1.lat/filme/matrix/", href)
        assertEquals("https://img.nnn1.lat/matrix.jpg", img)
    }
}
