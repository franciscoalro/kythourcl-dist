package com.lagradost.cloudstream3

enum class TvType(val apiName: String?) {
    Movie("movie"), TvSeries("series"), Anime("anime"), Cartoon("cartoon"),
    AsianDrama("asian_drama"), AnimeMovie("anime_movie"), Documentary("documentary"),
    TvShow("tv_show"), NSFW("nsfw"), Unknown(null)
}

enum class SearchQuality(val value: Int) {
    Unknown(0), HUNDRED(100), P360(360), P480(480), P720(720), P1080(1080), P2160(2160),
    UHD(2160), HD(1080), SD(480), CAM(360)
}

open class ExtractorLink {
    var source: String = ""
    var name: String = ""
    var url: String = ""
    var type: ExtractorLinkType = ExtractorLinkType.VIDEO
    var quality: Int = 0
    var referer: String = ""
    var headers: Map<String, String> = emptyMap()
    var isM3u8: Boolean = false
    var extractorData: String = ""
}

enum class ExtractorLinkType { VIDEO, M3U8, AUDIO }

open class SubtitleFile(val lang: String, val url: String)

open class Episode(val url: String) {
    var name: String? = null
    var season: Int? = null
    var episode: Int? = null
    var data: String = url
}

open class SearchResponse {
    var name: String = ""
    var url: String = ""
    var type: TvType = TvType.Movie
    var posterUrl: String? = null
    var posterHeaders: Map<String, String>? = null
    var quality: SearchQuality = SearchQuality.Unknown
    var year: Int? = null
}

open class LoadResponse {
    var name: String = ""
    var url: String = ""
    var type: TvType = TvType.Movie
    var posterUrl: String? = null
    var backgroundPosterUrl: String? = null
    var posterHeaders: Map<String, String>? = null
    var plot: String? = null
    var tags: List<String>? = null
    var year: Int? = null
    var duration: Int? = null
}

class TvSeriesLoadResponse(
    name: String, url: String, type: TvType,
    val episodes: List<Episode>
) : LoadResponse() {
    init { this.name = name; this.url = url; this.type = type }
}

class MovieLoadResponse(name: String, url: String, type: TvType, val data: String) : LoadResponse() {
    init { this.name = name; this.url = url; this.type = type }
}

class HomePageResponse(val list: List<HomePageList>, val hasNext: Boolean)
class HomePageList(val name: String, val list: List<SearchResponse>)
class MainPageRequest(val name: String, val data: String)
class MainPage(val data: Map<String, String>)

class MovieSearchResponse(name: String, url: String, type: TvType) : SearchResponse() {
    init { this.name = name; this.url = url; this.type = type }
}

class TvSeriesSearchResponse(name: String, url: String, type: TvType) : SearchResponse() {
    init { this.name = name; this.url = url; this.type = type }
}

class Response(val code: Int, val text: String, val headers: Map<String, String>)

class ResponseBody(val raw: String) {
    fun string(): String = raw
    fun bytes(): ByteArray = raw.toByteArray()
}

// Simple JSON implementation
class JSONObject(private val content: String) {
    private val map: Map<String, Any?> by lazy { parseJson(content) }
    
    companion object {
        private fun parseJson(json: String): Map<String, Any?> {
            return try {
                val trimmed = json.trim()
                if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyMap()
                
                val content = trimmed.substring(1, trimmed.length - 1).trim()
                val result = mutableMapOf<String, Any?>()
                var i = 0
                var key = ""
                var value = ""
                var inQuotes = false
                var escape = false
                var depth = 0
                var arrayDepth = 0
                
                while (i < content.length) {
                    val c = content[i]
                    
                    if (escape) {
                        value += c
                        escape = false
                        i++
                        continue
                    }
                    
                    if (c == '\\') {
                        escape = true
                        value += c
                        i++
                        continue
                    }
                    
                    if (c == '"') {
                        inQuotes = !inQuotes
                        value += c
                        i++
                        continue
                    }
                    
                    if (inQuotes) {
                        value += c
                        i++
                        continue
                    }
                    
                    when (c) {
                        '{' -> depth++
                        '}' -> depth--
                        '[' -> arrayDepth++
                        ']' -> arrayDepth--
                        ':' -> {
                            key = value.trim().removeSurrounding("\"")
                            value = ""
                        }
                        ',' -> {
                            if (depth == 0 && arrayDepth == 0) {
                                result[key] = parseJsonValue(value.trim())
                                key = ""
                                value = ""
                            } else {
                                value += c
                            }
                        }
                        else -> {
                            if (depth > 0 || arrayDepth > 0) value += c
                        }
                    }
                    i++
                }
                
                if (key.isNotEmpty()) {
                    result[key] = parseJsonValue(value.trim())
                }
                
                result
            } catch (e: Exception) {
                emptyMap()
            }
        }
        
        @Suppress("UNCHECKED_CAST")
        private fun parseJsonValue(s: String): Any? {
            return when {
                s == "null" -> null
                s == "true" -> true
                s == "false" -> false
                s.startsWith("\"") -> s.removeSurrounding("\"")
                s.toIntOrNull() != null -> s.toInt()
                s.toLongOrNull() != null -> s.toLong()
                s.toDoubleOrNull() != null -> s.toDouble()
                s.startsWith("{") -> JSONObject(s)
                s.startsWith("[") -> JSONArray(s)
                else -> s
            }
        }
    }
    
    fun has(key: String): Boolean = map.containsKey(key)
    fun optString(key: String, default: String = ""): String = map[key]?.toString() ?: default
    fun optInt(key: String, default: Int = 0): Int = map[key]?.toString()?.toIntOrNull() ?: default
    fun optBoolean(key: String, default: Boolean = false): Boolean = map[key]?.toString()?.toBoolean() ?: default
    fun optJSONObject(key: String): JSONObject = (map[key] as? JSONObject) ?: JSONObject("{}")
    fun optJSONArray(key: String): JSONArray = (map[key] as? JSONArray) ?: JSONArray("[]")
    fun getJSONObject(key: String): JSONObject = optJSONObject(key)
    fun getJSONArray(key: String): JSONArray = optJSONArray(key)
}

class JSONArray(private val content: String) {
    private val list: List<Any?> by lazy { parseJsonArray(content) }
    
    companion object {
        private fun parseJsonArray(json: String): List<Any?> {
            return try {
                val trimmed = json.trim()
                if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
                
                val content = trimmed.substring(1, trimmed.length - 1).trim()
                if (content.isEmpty()) return emptyList()
                
                val result = mutableListOf<Any?>()
                var depth = 0
                var arrayDepth = 0
                var current = ""
                
                for (c in content) {
                    when (c) {
                        '{' -> {
                            depth++
                            current += c
                        }
                        '}' -> {
                            if (depth == 0) {
                                result.add(parseJsonValue(current.trim()))
                                current = ""
                            } else {
                                depth--
                                current += c
                            }
                        }
                        '[' -> arrayDepth++
                        ']' -> arrayDepth--
                        ',' -> {
                            if (depth == 0 && arrayDepth == 0) {
                                result.add(parseJsonValue(current.trim()))
                                current = ""
                            } else {
                                current += c
                            }
                        }
                        else -> current += c
                    }
                }
                
                if (current.trim().isNotEmpty()) {
                    result.add(parseJsonValue(current.trim()))
                }
                
                result
            } catch (e: Exception) {
                emptyList()
            }
        }
        
        @Suppress("UNCHECKED_CAST")
        private fun parseJsonValue(s: String): Any? {
            return when {
                s == "null" -> null
                s == "true" -> true
                s == "false" -> false
                s.startsWith("\"") -> s.removeSurrounding("\"")
                s.toIntOrNull() != null -> s.toInt()
                s.toLongOrNull() != null -> s.toLong()
                s.toDoubleOrNull() != null -> s.toDouble()
                s.startsWith("{") -> JSONObject(s)
                s.startsWith("[") -> JSONArray(s)
                else -> s
            }
        }
    }
    
    fun length(): Int = list.size
    fun getJSONObject(index: Int): JSONObject = JSONObject("{}")
    fun getString(index: Int): String = list[index]?.toString() ?: ""
    fun optJSONObject(index: Int): JSONObject = JSONObject("{}")
    fun optString(index: Int): String = list[index]?.toString() ?: ""
}

abstract class MainAPI {
    open var mainUrl: String = ""
    open var name: String = ""
    open var lang: String = ""
    open val hasMainPage: Boolean = false
    open val hasQuickSearch: Boolean = false
    open val supportedTypes: Set<TvType> = emptySet()
    open val mainPage: MainPage = MainPage(emptyMap())
    
    val app: ApiClient get() = ApiClient()
    
    open suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        HomePageResponse(emptyList(), false)
    open suspend fun search(query: String): List<SearchResponse> = emptyList()
    open suspend fun load(url: String): LoadResponse? = null
    open suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = false
    open suspend fun quickSearch(query: String): List<SearchResponse> = emptyList()
}

class ApiClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), timeout: Long = 30L): Response {
        return Response(200, "", headers)
    }
    
    suspend fun post(url: String, data: Map<String, String>, headers: Map<String, String>): Response {
        return Response(200, "", headers)
    }
}

fun mainPageOf(vararg pairs: Pair<String, String>): MainPage = MainPage(pairs.toMap())
fun newHomePageResponse(list: List<HomePageList>, hasNext: Boolean = false): HomePageResponse =
    HomePageResponse(list, hasNext)
fun newHomePageList(name: String, list: List<SearchResponse>): HomePageList =
    HomePageList(name, list)
fun newMovieSearchResponse(name: String, url: String, type: TvType, block: MovieSearchResponse.() -> Unit = {}): MovieSearchResponse =
    MovieSearchResponse(name, url, type).apply(block)
fun newTvSeriesSearchResponse(name: String, url: String, type: TvType, block: TvSeriesSearchResponse.() -> Unit = {}): TvSeriesSearchResponse =
    TvSeriesSearchResponse(name, url, type).apply(block)
fun newMovieLoadResponse(name: String, url: String, type: TvType, data: String, block: MovieLoadResponse.() -> Unit = {}): MovieLoadResponse =
    MovieLoadResponse(name, url, type, data).apply(block)
fun newTvSeriesLoadResponse(name: String, url: String, type: TvType, episodes: List<Episode>, block: TvSeriesLoadResponse.() -> Unit = {}): TvSeriesLoadResponse =
    TvSeriesLoadResponse(name, url, type, episodes).apply(block)
fun newEpisode(url: String, block: Episode.() -> Unit = {}): Episode =
    Episode(url).apply(block)
fun newExtractorLink(source: String, name: String, url: String, type: ExtractorLinkType, block: ExtractorLink.() -> Unit = {}): ExtractorLink =
    ExtractorLink().apply {
        this.source = source
        this.name = name
        this.url = url
        this.type = type
        block()
    }
