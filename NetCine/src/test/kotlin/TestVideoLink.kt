import com.NetCine.NetCine
import kotlinx.coroutines.runBlocking

/**
 * Teste simples em Kotlin para ver o link do vídeo.
 * Roda: ./gradlew :NetCine:runTestVideoLink
 * Não tenta burlar captcha automaticamente - apenas relata se captcha foi encontrado.
 */
fun main() = runBlocking {
    val api = NetCine()
    // URL de teste - pode trocar por qualquer filme/série do nnn1.lat
    val testUrls = listOf(
        "https://nnn1.lat/matrix-revolutions/",
        "https://nnn1.lat/avengers-endgame/"
    )

    for (url in testUrls) {
        println("\n========== TESTANDO: $url ==========")
        try {
            println("-> load() ...")
            val load = api.load(url)
            println("   load OK: ${load::class.simpleName} | ${load.name} | ${load.url}")

            val episodes = (load as? com.lagradost.cloudstream3.TvSeriesLoadResponse)?.episodes
            val targetUrl = if (!episodes.isNullOrEmpty()) {
                println("   série com ${episodes.size} episódios, usando primeiro: ${episodes.first().data}")
                episodes.first().data
            } else {
                url
            }

            println("-> loadLinks($targetUrl) ...")
            var found = 0
            val success = api.loadLinks(targetUrl, false, { sub ->
                println("   [subtitle] $sub")
            }, { link ->
                found++
                println("   [LINK $found] url=${link.url}")
                println("             type=${link.type} name=${link.name} referer=${link.referer} quality=${link.quality}")
                // link direto m3u8/mp4
                if (link.url.contains(".m3u8") || link.url.contains(".mp4")) {
                    println("             >>> LINK DIRETO DO VIDEO: ${link.url}")
                }
            })
            println("   loadLinks retornou: $success found=$found")
            if (!success || found == 0) {
                println("   [!] Nenhum link encontrado - provavelmente captcha/proteção ativa na página.")
                println("   Verifique o log acima para mensagens 'Captcha' do NetCine.kt")
            }
        } catch (e: Exception) {
            println("   ERRO: ${e.message}")
            e.printStackTrace()
        }
    }
    println("\n=== FIM TESTE ===")
}
