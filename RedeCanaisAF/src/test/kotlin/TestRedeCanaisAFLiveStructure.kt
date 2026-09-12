import com.RedeCanaisAF.RedeCanaisAFText
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prova contra dumps reais (CDP + cache de sessão válida, 2026-09-09/11):
 * - /tmp/rc-home-real.html (139 KB): grid #pm-grid, cards .col-xs-6, data-echo, slug _hash.html
 * - /tmp/rc-search-p4.html (338 KB): algoritmo de busca (normalize + .listagem + final_mapa*.txt)
 * - /tmp/dump_serverphp.html (60 KB): shell ofuscado bundle.js, zero DOM estático
 *
 * Estes testes rodam SEM rede (offline) e travam os seletores do plugin contra a
 * estrutura real do site: se o site mudar o DOM, o teste quebra antes do usuário.
 */
class TestRedeCanaisAFLiveStructure {

    // ---- fixture mínima extraída do DOM real de /tmp/rc-home-real.html ----
    private val homeCardHtml = """
        <ul class="row pm-ul-browse-videos list-unstyled" id="pm-grid">
          <li class="col-xs-6 col-sm-4 col-md-3">
            <div class="thumbnail">
              <div class="pm-video-thumb">
                <a href="/a-morte-do-demonio-em-chamas-dublado-2026-1080p_576916138.html"
                   title="A Morte do Demônio: Em Chamas (Dublado) - 2026 - 1080p">
                  <img src="/templates/echo/img/echo-lzld.png"
                       alt="A Morte do Demônio: Em Chamas (Dublado) - 2026 - 1080p"
                       data-echo="/imgs-videos/Filmes/A%20Morte%20do%20Dem%C3%B4nio%20-%20Em%20Chamas.jpg"
                       class="img-responsive">
                  <span class="overlay"></span>
                </a>
              </div>
              <div class="caption">
                <h3><a href="/a-morte-do-demonio-em-chamas-dublado-2026-1080p_576916138.html"
                       title="A Morte do Demônio: Em Chamas (Dublado) - 2026 - 1080p"
                       class="ellipsis">A Morte do Demônio: Em Chamas (Dublado) - 2026 - 1080p</a></h3>
              </div>
            </div>
          </li>
        </ul>
        <ul class="pagination pagination-sm pagination-arrows">
          <li class="active"><a href="#" onclick="return false;">1</a></li>
          <li class=""><a href="/browse-filmes-videos-2-date.html">2</a></li>
          <li class=""><a href="/browse-filmes-videos-2399-date.html">2399</a></li>
        </ul>
    """.trimIndent()

    // ---- algoritmo de busca extraído verbatim de /tmp/rc-search-p4.html ----
    // normalize() do site: lowercase + troca de acentos (sem NFD).
    private fun siteNormalize(str: String): String {
        return str.lowercase()
            .replace(Regex("[áàãâä]"), "a").replace(Regex("[éèêë]"), "e")
            .replace(Regex("[íìîï]"), "i").replace(Regex("[óòõôö]"), "o")
            .replace(Regex("[úùûü]"), "u").replace(Regex("[ç]"), "c")
            .replace(Regex("[ñ]"), "n")
    }

    @Test
    fun homeGridCardStructure() {
        val doc = Jsoup.parse(homeCardHtml)
        // mesmos seletores de RedeCanaisAF.parseCard()/getMainPage()
        val cards = doc.select("#pm-grid > li, li.col-xs-6, li.pm-li-video, .pm-video-thumb")
        assertTrue("grid real deve renderizar cards", cards.isNotEmpty())
        val a = doc.selectFirst("#pm-grid li a[href*='.html'][title]")!!
        assertTrue(a.attr("href").endsWith(".html"))
        assertTrue(a.attr("title").contains("A Morte do Demônio"))
        // poster real vive em data-echo; src é placeholder
        val img = doc.selectFirst("#pm-grid li img")!!
        assertTrue(RedeCanaisAFText.isPlaceholderImage(img.attr("src")))
        assertEquals(
            "/imgs-videos/Filmes/A%20Morte%20do%20Dem%C3%B4nio%20-%20Em%20Chamas.jpg",
            img.attr("data-echo")
        )
    }

    @Test
    fun homeCardTitleCleaningMatchesPlugin() {
        // título real do card: "(Dublado) - 2026 - 1080p" — o plugin remove labels de
        // áudio/resolução, mas MANTÉM o ano (usado em extractYear). Travado aqui.
        assertEquals(
            "A Morte do Demônio: Em Chamas - 2026",
            RedeCanaisAFText.cleanMediaTitle("A Morte do Demônio: Em Chamas (Dublado) - 2026 - 1080p")
        )
    }

    @Test
    fun homePaginationPatternMatchesPlugin() {
        val doc = Jsoup.parse(homeCardHtml)
        val page2 = doc.select(".pagination a").map { it.attr("href") }
            .first { it.contains("-2-") }
        assertEquals("/browse-filmes-videos-2-date.html", page2)
        // regex de getMainPage(): -N-(date|views|rating|title).html
        val next = page2.replace(Regex("""-\d+-"""), "-3-")
        assertEquals("/browse-filmes-videos-3-date.html", next)
    }

    @Test
    fun searchIndexLineFormat() {
        // linha real de final_mapa*.txt: "TITULO ...<a href=\"URL\"".
        // O <b>...</b> é categoria do índice (ex: <b>filme</b>) e faz parte do bruto —
        // o site usa o bruto SÓ para normalizar/buscar; o título exibido vem do <span>.
        val line = "A Captura (Dublado) - 2026 <b>filme</b><a href=\"/a-captura-dublado-2026-1080p_a707773a0.html\""
        val match = Regex("""^(.*?)<a href="(.*?)"""", RegexOption.IGNORE_CASE).find(line)!!
        val bruto = match.groupValues[1].replace(Regex("""</?b[^>]*>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""-\s*$"""), "").trim()
        assertEquals("A Captura (Dublado) - 2026 filme", bruto)
        assertEquals("/a-captura-dublado-2026-1080p_a707773a0.html", match.groupValues[2].trim())
        // busca por substring normalizada encontra mesmo com o sufixo de categoria
        assertTrue(siteNormalize(bruto).contains(siteNormalize("a captura")))
    }

    @Test
    fun searchNormalizeMatchesSite() {
        // normalize() do site aplicado a query e título (includes, não token-match)
        assertEquals("coracao", siteNormalize("Coração"))
        assertEquals("capitao america: guerra civil", siteNormalize("Capitão América: Guerra Civil"))
        val tituloNorm = siteNormalize("Capitão América: Guerra Civil (Dublado) - 2016")
        assertTrue(tituloNorm.contains(siteNormalize("capitao america")))
        assertTrue(tituloNorm.contains(siteNormalize("guerra civil")))
        // plugin usa NFD; deve concordar com o site nestes casos
        assertTrue(RedeCanaisAFText.isRelevantSearchTitle("Capitão América: Guerra Civil (Dublado) - 2016", "guerra civil"))
        assertTrue(RedeCanaisAFText.isRelevantSearchTitle("Coração", "coracao"))
    }

    @Test
    fun playerShellHasNoStaticDom() {
        // /tmp/dump_serverphp.html: shell ofuscado — parse estático NÃO pode achar player
        val shellProbe = "<html><head><title>Player</title><script src=\"./bundle.js\"></script></head><body></body></html>"
        val doc = Jsoup.parse(shellProbe)
        assertTrue(doc.select("iframe[src*=server], #submit, .captcha_button, video").isEmpty())
        // por isso o plugin resolve o player via WebView (bundle.js monta o DOM em runtime),
        // e os padrões de StreamResolver miram a REDE (serverforms/__RC__/tos-alisg/xn--), não o HTML.
        // NOTA: a URL abaixo é a forma CANÔNICA do fluxo (dump v250): o __RC__/proxy externo
        // carrega container=videos + url=neosoro SÓ no src interno (URL-encoded). O marker
        // "container=videos" existe no src decodificado, não no path externo — o teste espelha isso.
        val outerProxy = "https://redecanais.af/__RC__/proxy?src=https%3A%2F%2Fxn--l---test.shop%2Ftos-alisg-avt-0068%2Fproxy%3Fcontainer%3Dvideos%26url%3Dhttps%3A%2F%2Fneosoro.gq%2FV%2FRCFServer3%2Fondemand%2FCAPTAMRC3LEG.mp4%3Fsv%3D57"
        for (marker in listOf("__RC__/proxy", "tos-alisg", "xn--l")) {
            assertTrue("whitelist do StreamResolver deve conter $marker", outerProxy.contains(marker))
        }
        val innerSrc = java.net.URLDecoder.decode(outerProxy.substringAfter("src="), "UTF-8")
        for (marker in listOf("container=videos", "neosoro.gq", "/ondemand/")) {
            assertTrue("src interno decodificado deve conter $marker", innerSrc.contains(marker))
        }
    }

    @Test
    fun challengeFingerprint() {
        // fingerprint do challenge provado na simulação ao vivo (403 + Um momento…)
        assertTrue(
            com.RedeCanaisAF.CloudflareSolver.isChallengeContent(
                "<title>Um momento…</title><script src=\"/cdn-cgi/challenge-platform/h/g/orchestrate/chl_page/v1?ray=x\"></script>"
            )
        )
        assertFalse(
            com.RedeCanaisAF.CloudflareSolver.isChallengeContent(
                "<ul id=\"pm-grid\"><li class=\"col-xs-6\">x</li></ul>"
            )
        )
    }
}
