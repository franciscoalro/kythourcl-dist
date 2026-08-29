import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Suíte de Testes em Kotlin Puro para o RedeCanaisAF.
 * Testa todas as regras de negócio, parsing, regexes, seletores e validações de stream.
 */
object RedeCanaisAFTestHarness {

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

    fun cleanMediaTitle(raw: String): String {
        var text = raw.trim()
        for (pattern in TITLE_CLEAN_PATTERNS) {
            text = text.replace(pattern, "").trim()
        }
        text = text.replace(Regex("""\s*\(\s*\)$"""), "").trim()
        text = text.replace(Regex("""\s*\[\s*\]$"""), "").trim()
        text = text.replace(Regex("""[\s(\[\-–—/:]+$"""), "").trim()
        return text
    }

    fun isSeriesUrlOrTitle(url: String, title: String): Boolean {
        val urlLower = url.lowercase()
        if (SERIES_KEYWORDS.any { urlLower.contains(it) }) return true
        val titleLower = title.lowercase()
        return SERIES_KEYWORDS.any { titleLower.contains(it) } ||
               titleLower.contains("temporada") ||
               titleLower.contains("episódio") ||
               titleLower.contains("episodio")
    }

    fun isPlaceholderImage(url: String): Boolean {
        if (url.isBlank()) return true
        if (url.startsWith("data:image/svg+xml", ignoreCase = true) && url.length < 200) return true
        return PLACEHOLDER_PATTERNS.any { url.contains(it, ignoreCase = true) }
    }

    fun extractPoster(doc: Document): String? {
        val candidates = mutableListOf<String>()
        doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { candidates.add(it) }
        doc.selectFirst("meta[name='twitter:image']")?.attr("content")?.let { candidates.add(it) }
        doc.selectFirst("img[data-echo*='/imgs-videos/']")?.attr("data-echo")?.let { candidates.add(it) }
        doc.selectFirst("img[src*='/imgs-videos/']")?.attr("src")?.let { candidates.add(it) }

        return candidates
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !isPlaceholderImage(it) && !it.startsWith("data:", true) }
    }

    fun isValidStreamUrl(url: String): Boolean {
        if (url.isBlank() || url.startsWith("javascript:", true) || url.startsWith("#")) return false
        val lower = url.lowercase()
        
        // Rejeita páginas HTML / scripts PHP
        if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".php") || lower.endsWith(".jsp") || lower.endsWith(".asp")) {
            return false
        }
        if (lower.contains("server.php") || lower.contains("player.php") || lower.contains("embed.php") || lower.contains("play.php") || lower.contains("browse-")) {
            return false
        }
        
        // Aceita streams diretos válidos
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mkv") || 
               lower.contains("/hls/") || lower.contains("/video/") || lower.contains("/stream/") || 
               lower.contains("googlevideo.com") || lower.contains("googleusercontent.com") ||
               lower.contains("storage.") || lower.contains("cdn.")
    }

    fun parseEpisodesFromHtml(html: String, baseUrl: String): List<Triple<String, Int, Int>> {
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

    fun extractDirectStreams(html: String): List<String> {
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
}

fun main() {
    println("================================================================================")
    println("INICIANDO SUÍTE DE TESTES UNITÁRIOS E DE INTEGRAÇÃO EM KOTLIN PURO")
    println("================================================================================")

    var testsPassed = 0
    var testsFailed = 0

    fun assertTest(name: String, condition: Boolean, details: String = "") {
        if (condition) {
            println("  [PASS] $name")
            testsPassed++
        } else {
            println("  [FAIL] $name -> $details")
            testsFailed++
        }
    }

    // --- TESTE 1: Limpeza de Títulos ---
    println("\n[TESTE 1] Limpeza e Normalização de Títulos:")
    val titleCases = mapOf(
        "George Washington - (Dublado) 2026 - RedeCanais" to "George Washington",
        "Futurama (Dublado) - Lista de Episódios" to "Futurama",
        "Lanternas (Legendado)" to "Lanternas",
        "X-Men '97 - Todas as Temporadas - Rede Canais" to "X-Men '97",
        "Minions & Monstros - 2026 - HD 1080p Dublado" to "Minions & Monstros - 2026",
        "Avatar: O Caminho da Água - Dublado (2022)" to "Avatar: O Caminho da Água"
    )

    for ((input, expected) in titleCases) {
        val cleaned = RedeCanaisAFTestHarness.cleanMediaTitle(input)
        assertTest("Limpeza: '$input' -> '$cleaned'", cleaned.startsWith(expected), "Esperado conter '$expected', obtido '$cleaned'")
    }

    // --- TESTE 2: Classificação de Séries vs Filmes ---
    println("\n[TESTE 2] Classificação de Séries / Animes / Desenhos vs Filmes:")
    val seriesUrls = listOf(
        "https://redecanais.af/futurama-dublado-lista-de-episodios_21fdc4414.html",
        "https://redecanais.af/x-men-97-dublado-todas-as-temporadas_9f075121e.html",
        "https://redecanais.af/naruto-shippuden-animes-lista-de-episodios_12345.html",
        "https://redecanais.af/monstros-no-trabalho-1a-temporada-episodio-01_598beecbe.html"
    )
    for (url in seriesUrls) {
        val isSeries = RedeCanaisAFTestHarness.isSeriesUrlOrTitle(url, "")
        assertTest("Série Detectada: $url", isSeries, "Deveria ser classificado como SÉRIE")
    }

    val movieUrls = listOf(
        "https://redecanais.af/george-washington-dublado-2026-1080p_e72a8c3d0.html",
        "https://redecanais.af/avatar-o-caminho-da-agua-dublado-2022-1080p_6d013063f.html"
    )
    for (url in movieUrls) {
        val isSeries = RedeCanaisAFTestHarness.isSeriesUrlOrTitle(url, "Avatar O Caminho da Agua 2022")
        assertTest("Filme Detectado (não série): $url", !isSeries, "Deveria ser classificado como FILME")
    }

    // --- TESTE 3: Extração de Capa e Rejeição de Data URIs e Placeholders ---
    println("\n[TESTE 3] Extração de Imagem / Poster:")
    val sampleHtmlWithImages = """
        <html>
            <head>
                <meta property="og:image" content="https://redecanais.af/imgs-videos/Filmes/George%20Washington.jpg">
            </head>
            <body>
                <img data-echo="https://redecanais.af/imgs-videos/echo-lzld.png" src="data:image/jpeg;base64,invalidbase64coil">
            </body>
        </html>
    """.trimIndent()
    val doc = Jsoup.parse(sampleHtmlWithImages)
    val extractedPoster = RedeCanaisAFTestHarness.extractPoster(doc)
    assertTest(
        "Poster HTTP válido extraído",
        extractedPoster == "https://redecanais.af/imgs-videos/Filmes/George%20Washington.jpg",
        "Obtido: $extractedPoster"
    )
    assertTest(
        "Rejeição de Data URI Base64 no poster",
        extractedPoster != null && !extractedPoster.startsWith("data:"),
        "Poster não pode ser Base64 para compatibilidade com Coil"
    )

    // --- TESTE 4: Validação Anti-HTML de Streams para o ExoPlayer ---
    println("\n[TESTE 4] Validação de URLs de Vídeo (Anti-HTML para ExoPlayer):")
    val invalidUrls = listOf(
        "https://redecanais.af/player3/server.php?categoria=vod&server=RCServer15&vid=123",
        "https://redecanais.af/player.php?v=456",
        "https://redecanais.af/filme.html",
        "https://redecanais.af/browse-filmes-lancamentos-videos-1-date.html"
    )
    for (url in invalidUrls) {
        val valid = RedeCanaisAFTestHarness.isValidStreamUrl(url)
        assertTest("Rejeita página HTML/PHP para o ExoPlayer: $url", !valid, "Deveria retornar false")
    }

    val validStreams = listOf(
        "https://s1.redecanais.af/ondemand/video_1080p.mp4",
        "https://cdn.redecanais.af/hls/stream.m3u8?token=xyz",
        "https://storage.googleapis.com/redecanais/movie.mp4",
        "https://rr1---sn-video.googlevideo.com/videoplayback?expire=12345"
    )
    for (url in validStreams) {
        val valid = RedeCanaisAFTestHarness.isValidStreamUrl(url)
        assertTest("Aceita Stream de Vídeo Direto: $url", valid, "Deveria retornar true")
    }

    // --- TESTE 5: Extração de Episódios com Temporadas no PHP Melody ---
    println("\n[TESTE 5] Extração de Episódios de Séries:")
    val sampleSeriesHtml = """
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
    val episodes = RedeCanaisAFTestHarness.parseEpisodesFromHtml(sampleSeriesHtml, "https://redecanais.af")
    assertTest("Total de episódios extraídos: ${episodes.size}", episodes.size == 3, "Esperado 3 episódios")
    if (episodes.size == 3) {
        assertTest("Episódio 1 da Temporada 1", episodes[0].second == 1 && episodes[0].third == 1)
        assertTest("Episódio 2 da Temporada 1", episodes[1].second == 1 && episodes[1].third == 2)
        assertTest("Episódio 1 da Temporada 2", episodes[2].second == 2 && episodes[2].third == 1)
    }

    // --- TESTE 6: Extração de Streams Diretos em Player HTML ---
    println("\n[TESTE 6] Extração de Streams dentro de Player HTML:")
    val samplePlayerHtml = """
        <html>
            <body>
                <script>
                    var player = new Clappr.Player({
                        source: 'https://s1.redecanais.af/ondemand/MNSTRSNTRBLHT01EP01.mp4',
                        parentId: '#player'
                    });
                </script>
                <video>
                    <source src="https://cdn.redecanais.af/hls/master.m3u8" type="application/x-mpegURL">
                </video>
            </body>
        </html>
    """.trimIndent()
    val directStreams = RedeCanaisAFTestHarness.extractDirectStreams(samplePlayerHtml)
    assertTest("Extração de streams do player: ${directStreams.size}", directStreams.size >= 2, "Esperado pelo menos 2 streams")
    for (s in directStreams) {
        assertTest("Stream extraído válido: $s", s.contains(".mp4") || s.contains(".m3u8"))
    }

    println("\n================================================================================")
    println("RESULTADO FINAL DOS TESTES EM KOTLIN:")
    println("  Total de Asserções Executadas: ${testsPassed + testsFailed}")
    println("  [PASS] Aprovados: $testsPassed")
    println("  [FAIL] Falhas:    $testsFailed")
    println("================================================================================")

    if (testsFailed > 0) {
        throw AssertionError("A suíte de testes em Kotlin falhou em $testsFailed asserções!")
    }
}
