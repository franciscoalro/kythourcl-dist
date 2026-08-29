import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestRedeCanaisAFUnit {

    private val TITLE_CLEAN_PATTERNS = listOf(
        Regex("""(?i)\s*[-|–—/]?\s*Rede\s*Canais.*$"""),
        Regex("""(?i)\s*[-|–—/]?\s*Assistir\s+Online.*$"""),
        Regex("""(?i)\s*[-|–—/]?\s*Online\s+Gr[aá]tis.*$"""),
        Regex("""(?i)\s*[-|–—/]?\s*Todos\s+os\s+Epis[oó]dios.*$"""),
        Regex("""(?i)\s*[-|–—/]?\s*Lista\s+de\s+Epis[oó]dios.*$"""),
        Regex("""(?i)\s*[-|–—/]?\s*Todas\s+as\s+Temporadas.*$"""),
        Regex("""(?i)\s*[-|–—/]?\s*(?:HD\s*)?(?:Dublado|Legendado|Nacional|Dual\s*[AÁ]udio).*$"""),
        Regex("""(?i)\s*[-|–—/]?\s*\d+p.*$"""),
        Regex("""(?i)^\s*Assistir\s+"""),
        Regex("""[\s(\[\-–—/:]+$""")
    )

    private val SERIES_KEYWORDS = listOf(
        "lista-de-episodios", "todas-as-temporadas", "-temporada-", "-temporadas-",
        "temporada", "serie", "series", "anime", "animes", "desenho", "desenhos",
        "episodio", "episodios", "completo-dublado"
    )

    private val PLACEHOLDER_PATTERNS = listOf(
        "echo-lzld", "blank.gif", "transparent.gif", "placeholder", "pixel.gif",
        "loading.gif", "spinner", "no-poster", "no-image", "default-thumb"
    )

    private fun cleanMediaTitle(raw: String): String {
        var text = raw.trim()
        for (pattern in TITLE_CLEAN_PATTERNS) {
            text = text.replace(pattern, "").trim()
        }
        text = text.replace(Regex("""\s*\(\s*\)$"""), "").trim()
        text = text.replace(Regex("""\s*\[\s*\]$"""), "").trim()
        text = text.replace(Regex("""[\s(\[\-–—/:]+$"""), "").trim()
        return text
    }

    private fun isSeriesUrlOrTitle(url: String, title: String): Boolean {
        val urlLower = url.lowercase()
        if (SERIES_KEYWORDS.any { urlLower.contains(it) }) return true
        val titleLower = title.lowercase()
        return SERIES_KEYWORDS.any { titleLower.contains(it) } ||
               titleLower.contains("temporada") ||
               titleLower.contains("episódio") ||
               titleLower.contains("episodio")
    }

    private fun isPlaceholderImage(url: String): Boolean {
        if (url.isBlank()) return true
        if (url.startsWith("data:image/svg+xml", ignoreCase = true) && url.length < 200) return true
        return PLACEHOLDER_PATTERNS.any { url.contains(it, ignoreCase = true) }
    }

    private fun extractPoster(doc: Document): String? {
        val candidates = mutableListOf<String>()
        doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { candidates.add(it) }
        doc.selectFirst("meta[name='twitter:image']")?.attr("content")?.let { candidates.add(it) }
        doc.selectFirst("img[data-echo*='/imgs-videos/']")?.attr("data-echo")?.let { candidates.add(it) }
        doc.selectFirst("img[src*='/imgs-videos/']")?.attr("src")?.let { candidates.add(it) }

        return candidates
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !isPlaceholderImage(it) && !it.startsWith("data:", true) }
    }

    private fun isValidStreamUrl(url: String): Boolean {
        if (url.isBlank() || url.startsWith("javascript:", true) || url.startsWith("#")) return false
        val clean = url.substringBefore("?").lowercase()
        val fullLower = url.lowercase()
        
        if (clean.endsWith(".js") || clean.endsWith(".css") || clean.endsWith(".html") || 
            clean.endsWith(".htm") || clean.endsWith(".json") || clean.endsWith(".xml") ||
            clean.endsWith(".jpg") || clean.endsWith(".png") || clean.endsWith(".gif") || 
            clean.endsWith(".svg") || clean.endsWith(".webp") || clean.endsWith(".woff") || 
            clean.endsWith(".woff2") || clean.endsWith(".ttf")) {
            return false
        }

        if (fullLower.contains("disqus") || fullLower.contains("chatango") || 
            fullLower.contains("facebook") || fullLower.contains("twitter") || 
            fullLower.contains("google-analytics") || fullLower.contains("googletagmanager") || 
            fullLower.contains("recaptcha") || fullLower.contains("turnstile") ||
            fullLower.contains("server.php") || fullLower.contains("player.php") || 
            fullLower.contains("embed.php") || fullLower.contains("play.php") || 
            fullLower.contains("browse-")) {
            return false
        }
        
        val isDirectMediaExt = clean.endsWith(".mp4") || clean.endsWith(".m3u8") || 
                               clean.endsWith(".mpd") || clean.endsWith(".mkv") || clean.endsWith(".webm")
        val isRecognizedStreamUrl = fullLower.contains(".m3u8?") || fullLower.contains(".mp4?") || 
                                    fullLower.contains("/hls/") || fullLower.contains("/ondemand/") || 
                                    fullLower.contains("/stream/") || fullLower.contains("googlevideo.com") ||
                                    (fullLower.contains("storage.googleapis.com") && !clean.endsWith(".jpg"))

        return isDirectMediaExt || isRecognizedStreamUrl
    }

    private fun parseEpisodesFromHtml(html: String, baseUrl: String): List<Triple<String, Int, Int>> {
        val doc = Jsoup.parse(html, baseUrl)
        val episodes = mutableListOf<Triple<String, Int, Int>>()
        var currentSeason = 1

        val descEl = doc.selectFirst(".pm-video-description, #pm-video-description, .description") ?: return emptyList()

        for (child in descEl.children()) {
            val text = child.text().trim()
            val seasonMatch = Regex("""(?:(\d+)[ªaºo]?\s*Temp|Temporada\s*(\d+)|T(\d+))""", RegexOption.IGNORE_CASE).find(text)
            if (seasonMatch != null) {
                val sNum = seasonMatch.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
                if (sNum != null && sNum in 1..100) {
                    currentSeason = sNum
                }
            }

            val links = child.select("a[href]")
            for (link in links) {
                val epTitle = link.text().trim()
                val href = link.attr("abs:href")
                if (href.isBlank() || !href.contains(".html", ignoreCase = true)) continue

                val epMatch = Regex("""(?:Epis[oó]dio\s*(\d+)|Ep\s*(\d+)|E(\d+)|Cap[ií]tulo\s*(\d+)|-(\d+)-)""", RegexOption.IGNORE_CASE)
                    .find(epTitle) ?: Regex("""-(\d+)[-_]""").find(href)

                val epNum = epMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull() ?: (episodes.size + 1)
                episodes.add(Triple(epTitle, currentSeason, epNum))
            }
        }
        return episodes
    }

    private fun extractDirectStreams(html: String): List<String> {
        val streamPatterns = listOf(
            Regex("""["'](https?://[^\s"'\\]+\.(?:m3u8|mp4)(?:\?[^\s"'\\]*)?)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|source|src|stream|hls|video)\s*[:=]\s*["'](https?://[^\s"'\\]+)["']""", RegexOption.IGNORE_CASE),
            Regex("""\{[^}]*file\s*:\s*["'](https?://[^\s"'\\]+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<source[^>]+src=["'](https?://[^\s"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<video[^>]+src=["'](https?://[^\s"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["'](https?://[^\s"']+)["']""", RegexOption.IGNORE_CASE)
        )

        val candidates = mutableSetOf<String>()
        for (pattern in streamPatterns) {
            pattern.findAll(html).forEach { match ->
                val raw = match.groupValues[1].replace("\\/", "/")
                if (isValidStreamUrl(raw)) {
                    candidates.add(raw)
                }
            }
        }
        return candidates.toList()
    }

    @Test
    fun testTitleCleaning() {
        println("=== TESTE 1: Limpeza de Títulos ===")
        val cases = mapOf(
            "George Washington - (Dublado) 2026 - RedeCanais" to "George Washington",
            "Futurama (Dublado) - Lista de Episódios" to "Futurama",
            "Lanternas (Legendado)" to "Lanternas",
            "X-Men '97 - Todas as Temporadas - Rede Canais" to "X-Men '97",
            "Avatar: O Caminho da Água - Dublado 1080p" to "Avatar: O Caminho da Água"
        )
        for ((input, expected) in cases) {
            val result = cleanMediaTitle(input)
            println("  Input: '$input' -> Output: '$result'")
            assertTrue("Título '$result' deveria começar com '$expected'", result.startsWith(expected))
        }
    }

    @Test
    fun testSeriesClassification() {
        println("=== TESTE 2: Classificação de Séries vs Filmes ===")
        val seriesUrls = listOf(
            "https://redecanais.af/futurama-dublado-lista-de-episodios_21fdc4414.html",
            "https://redecanais.af/x-men-97-dublado-todas-as-temporadas_9f075121e.html",
            "https://redecanais.af/naruto-shippuden-animes-lista-de-episodios_12345.html"
        )
        for (url in seriesUrls) {
            println("  Verificando Série: $url")
            assertTrue(isSeriesUrlOrTitle(url, ""))
        }

        val movieUrls = listOf(
            "https://redecanais.af/george-washington-dublado-2026-1080p_e72a8c3d0.html",
            "https://redecanais.af/avatar-o-caminho-da-agua-dublado-2022-1080p_6d013063f.html"
        )
        for (url in movieUrls) {
            println("  Verificando Filme: $url")
            assertFalse(isSeriesUrlOrTitle(url, "Avatar O Caminho da Agua"))
        }
    }

    @Test
    fun testPosterExtraction() {
        println("=== TESTE 3: Extração de Poster HTTP para Coil ===")
        val sampleHtml = """
            <html>
                <head>
                    <meta property="og:image" content="https://redecanais.af/imgs-videos/Filmes/George%20Washington.jpg">
                </head>
                <body>
                    <img data-echo="https://redecanais.af/imgs-videos/echo-lzld.png" src="data:image/jpeg;base64,invalid">
                </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(sampleHtml)
        val poster = extractPoster(doc)
        println("  Poster Extraído: $poster")
        assertNotNull(poster)
        assertEquals("https://redecanais.af/imgs-videos/Filmes/George%20Washington.jpg", poster)
        assertFalse(poster!!.startsWith("data:"))
    }

    @Test
    fun testStreamUrlValidation() {
        println("=== TESTE 4: Validação Anti-HTML de Vídeo para ExoPlayer ===")
        val htmlUrls = listOf(
            "https://c.disquscdn.com/embedv2/latest/embedv2.js",
            "https://redecanais.af/player3/server.php?categoria=vod&vid=123",
            "https://redecanais.af/player.php?v=456",
            "https://redecanais.af/filme.html"
        )
        for (u in htmlUrls) {
            println("  Testando rejeição de HTML: $u")
            assertFalse(isValidStreamUrl(u))
        }

        val videoUrls = listOf(
            "https://s1.redecanais.af/ondemand/video_1080p.mp4",
            "https://cdn.redecanais.af/hls/stream.m3u8?token=xyz",
            "https://storage.googleapis.com/redecanais/movie.mp4"
        )
        for (u in videoUrls) {
            println("  Testando aceitação de vídeo direto: $u")
            assertTrue(isValidStreamUrl(u))
        }
    }

    @Test
    fun testEpisodeParsing() {
        println("=== TESTE 5: Extração de Episódios e Temporadas ===")
        val sampleHtml = """
            <div class="pm-video-description">
                <p><strong>1ª Temporada</strong></p>
                <p>
                    <a href="https://redecanais.af/futurama-1a-temporada-episodio-01_111.html">Episódio 01 - Piloto Espacial</a><br>
                    <a href="https://redecanais.af/futurama-1a-temporada-episodio-02_222.html">Episódio 02 - Viagem à Lua</a>
                </p>
                <p><strong>2ª Temporada</strong></p>
                <p>
                    <a href="https://redecanais.af/futurama-2a-temporada-episodio-01_333.html">Episódio 01 - Problemas com Popplers</a>
                </p>
            </div>
        """.trimIndent()
        val eps = parseEpisodesFromHtml(sampleHtml, "https://redecanais.af")
        println("  Total de Episódios: ${eps.size}")
        for (ep in eps) {
            println("    * ${ep.first} -> S${ep.second} E${ep.third}")
        }
        assertEquals(3, eps.size)
        assertEquals(1, eps[0].second)
        assertEquals(1, eps[0].third)
        assertEquals(2, eps[2].second)
        assertEquals(1, eps[2].third)
    }

    @Test
    fun testDirectPlayerStreamExtraction() {
        println("=== TESTE 6: Extração de Streams do Player HTML ===")
        val playerHtml = """
            <html>
                <body>
                    <script>
                        var player = new Clappr.Player({
                            source: 'https://s1.redecanais.af/ondemand/video.mp4',
                            parentId: '#player'
                        });
                    </script>
                    <video>
                        <source src="https://cdn.redecanais.af/hls/master.m3u8" type="application/x-mpegURL">
                    </video>
                </body>
            </html>
        """.trimIndent()
        val streams = extractDirectStreams(playerHtml)
        println("  Streams Extraídos: $streams")
        assertTrue(streams.size >= 2)
        assertTrue(streams.any { it.contains(".mp4") })
        assertTrue(streams.any { it.contains(".m3u8") })
    }
}
