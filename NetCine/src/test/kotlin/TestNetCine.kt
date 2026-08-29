import com.NetCine.NetCine
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val api = NetCine()
    println("=== SEARCH ===")
    val search = api.search("The Walking Dead")
    println("search ${search.size}")
    assert(search.isNotEmpty())
    val series = search.first { it.url.contains("/tvshows/") }
    println("series ${series.url}")
    val load = api.load(series.url)
    println("load ${load::class.simpleName}")
    val episodes = (load as? com.lagradost.cloudstream3.TvSeriesLoadResponse)?.episodes ?: emptyList()
    println("episodes ${episodes.size}")
    assert(episodes.isNotEmpty())
    for (ep in episodes.take(3)) {
        println("ep ${ep.name} s${ep.season} e${ep.episode} ${ep.data}")
    }
    val epUrl = episodes.first().data
    println("=== LOADLINKS $epUrl ===")
    var found = false
    api.loadLinks(epUrl, false, { sub -> println("subtitle $sub") }, { link ->
        println("ExtractorLink ${link.url.take(80)} type ${link.type} referer ${link.referer}")
        assert(link.url.contains(".m3u8") || link.url.contains(".mp4"))
        found = true
    })
    println("loadLinks found $found")
    assert(found)
    println("=== MOVIE LOADLINKS ===")
    val movies = api.search("Avengers")
    val movie = movies.first()
    println("movie ${movie.url}")
    var foundMovie = false
    api.loadLinks(movie.url, false, {}, { link ->
        println("movie link ${link.url.take(80)}")
        foundMovie = true
    })
    println("movie found $foundMovie")
    assert(foundMovie)
    println("=== ALL PUBLIC INTERFACE PASS ===")
}
