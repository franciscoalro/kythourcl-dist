import com.RedeCanaisAF.CloudflareSolver
import com.RedeCanaisAF.RedeCanaisAFText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestRedeCanaisAFV139 {
    @Test
    fun detectsLocalizedCloudflareChallenges() {
        assertTrue(CloudflareSolver.isChallengeContent("<title>Just a moment...</title>"))
        assertTrue(CloudflareSolver.isChallengeContent("<title>Um momento…</title>"))
        assertTrue(CloudflareSolver.isChallengeContent("Verificando seu navegador"))
        assertTrue(CloudflareSolver.isChallengeContent("Error code 520"))
        assertTrue(CloudflareSolver.isChallengeContent("/cdn-cgi/challenge-platform/h/b/?id=cf-chl-test"))
        assertFalse(CloudflareSolver.isChallengeContent("<title>Filmes lançamentos</title><article>Filme</article>"))
    }

    @Test
    fun cleansPortugueseTitlesWithoutMojibake() {
        assertEquals(
            "Futurama",
            RedeCanaisAFText.cleanMediaTitle("Assistir Futurama - Lista de Episódios - Rede Canais")
        )
        assertEquals(
            "Avatar: O Caminho da Água",
            RedeCanaisAFText.cleanMediaTitle("Avatar: O Caminho da Água - Dublado 1080p")
        )
        assertEquals("Episódio 7", RedeCanaisAFText.cleanEpisodeTitle("Online", 7))
    }

    @Test
    fun parsesPortugueseSeasonAndEpisodeMarkers() {
        assertEquals(2, RedeCanaisAFText.extractSeasonHeaderNumber("2ª Temporada"))
        assertEquals(3, RedeCanaisAFText.extractSeasonNumber("S03E12"))
        assertEquals(12, RedeCanaisAFText.extractEpisodeNumber("S03E12"))
        assertEquals(9, RedeCanaisAFText.extractEpisodeNumber("Capítulo 09"))
    }

    @Test
    fun mapsTurnstileDomRectToAndroidTouchCoordinates() {
        val desktopPoint = CloudflareSolver.turnstileTapPoint(
            "\"turnstile_rect|512|304|68.390625|1920|869\"",
            viewWidth = 1920,
            viewHeight = 869
        )
        assertEquals(544f, desktopPoint!!.first, 0.1f)
        assertEquals(336.5f, desktopPoint.second, 0.1f)

        val scaledPoint = CloudflareSolver.turnstileTapPoint(
            "\"turnstile_rect|42|116|65|412|800\"",
            viewWidth = 1080,
            viewHeight = 2100
        )
        assertEquals(194f, scaledPoint!!.first, 1f)
        assertEquals(389.8f, scaledPoint.second, 1f)

        assertNull(CloudflareSolver.turnstileTapPoint("\"turnstile_missing\"", 1080, 2100))
    }

    @Test
    fun normalizesEmbeddedWebViewUserAgentForCloudflare() {
        val raw = "Mozilla/5.0 (Linux; Android 14; sdk_gphone64_x86_64 Build/UE1A.230829.050; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/152.0.7977.54 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/152.0.7977.54 Safari/537.36",
            CloudflareSolver.challengeUserAgent(raw)
        )
        assertEquals("custom-agent", CloudflareSolver.challengeUserAgent("custom-agent"))
    }
}
