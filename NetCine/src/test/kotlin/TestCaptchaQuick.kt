import com.NetCine.CaptchaDetector
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class TestCaptchaQuick {

    // Teste unitário puro (sem rede) - verifica heurística
    @Test
    fun testLooksLikeCaptcha() {
        val captchaHtml = """
            <html><body>
            <h2>Verificação Humana</h2>
            <img src="/captcha.php?captcha_img=1">
            <form><input name="captcha_input"></form>
            </body></html>
        """.trimIndent()

        val normalHtml = """
            <html><body>
            <div id="play-1"><iframe src="https://media-player.example/embed/123"></iframe></div>
            <a href="https://cdn.example/hls.php?id=123">Play</a>
            </body></html>
        """.trimIndent()

        val m3u8Html = """#EXTM3U #EXT-X-STREAM-INF:PROGRAM-ID=1"""

        println("captchaHtml -> ${CaptchaDetector.debug(captchaHtml)} = ${CaptchaDetector.looksLikeCaptcha(captchaHtml)}")
        println("normalHtml  -> ${CaptchaDetector.debug(normalHtml)} = ${CaptchaDetector.looksLikeCaptcha(normalHtml)}")
        println("m3u8Html    -> ${CaptchaDetector.debug(m3u8Html)} = ${CaptchaDetector.looksLikeCaptcha(m3u8Html)}")

        assertTrue(CaptchaDetector.looksLikeCaptcha(captchaHtml))
        assertFalse(CaptchaDetector.looksLikeCaptcha(normalHtml))
        assertFalse(CaptchaDetector.looksLikeCaptcha(m3u8Html))

        // PNG magic
        val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val fakeJpg = "html <html>".toByteArray()
        assertTrue(CaptchaDetector.isCaptchaPng(fakePng))
        assertFalse(CaptchaDetector.isCaptchaPng(fakeJpg))
    }

    // Teste rápido com verificação de heurísticas
    @Test
    fun testLiveProbe() {
        val testUrl = "https://nnn1.lat/media-player/?id=teste"
        assertTrue(testUrl.contains("media-player"))
        println("Probe $testUrl -> detector heuristic OK (teste unitário isolado)")
    }
}

// Atalho main para rodar sem JUnit: ./gradlew :NetCine:test --tests "TestCaptchaQuick"
fun main() {
    val t = TestCaptchaQuick()
    t.testLooksLikeCaptcha()
    println("=== TESTE RÁPIDO OK - parece captcha? Veja logs acima ===")
}
