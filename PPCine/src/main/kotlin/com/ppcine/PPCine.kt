// Plugin CloudStream para PPCine (com.ppit.ppcineit)
// API reverse-engineered: i5hs.k9vo.com
// Criptografia SHOK: AES-128-CBC com keys hardcoded

package com.ppcine

import com.lagradost.cloudstream3.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

@CloudstreamPlugin
class PPCine : MainAPI() {
    
    override var mainUrl = "https://i5hs.k9vo.com"
    override var name = "PPCine"
    override var lang = "pt"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    companion object {
        private const val SIGN = "4B052E5D63974ACE96FAF51DF861A7FA"
        
        private fun getShokKey() = SecretKeySpec("0123456789123456".toByteArray(), "AES")
        private fun getShokIv() = IvParameterSpec("2015030120123456".toByteArray())
    }
    
    override val mainPage = mainPageOf(
        "🔥 Em Alta" to "api/vod/type?area=1&psize=20&is_random=1&type_id=0",
        "🎬 Filmes" to "api/vod/type?area=1&psize=20&type_id=1",
        "📺 Séries" to "api/vod/type?area=1&psize=20&type_id=2",
        "🎭 Animação" to "api/vod/type?area=1&psize=20&type_id=3",
        "⭐ Novidades" to "api/vod/type?area=1&psize=20&orderby=time"
    )
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val resp = app.post(
            "$mainUrl$path",
            data = emptyMap(),
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded")
        )
        val decrypted = decryptShok(resp.text)
        val json = parseJsonSafe(decrypted) ?: return newHomePageResponse(emptyList(), false)
        
        val list = json.optJSONArray("list") ?: json.optJSONArray("data") ?: JSONArray("[]")
        val parsedList = parseVideoList(list)
        
        return newHomePageResponse(
            list = listOf(newHomePageList(request.name, parsedList)),
            hasNext = false
        )
    }
    
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        val searchResp = app.post(
            "$mainUrl/api/search/result",
            data = mapOf("kw" to query, "pn" to "1"),
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded")
        )
        val decrypted = decryptShok(searchResp.text)
        val json = parseJsonSafe(decrypted)
        
        if (json != null) {
            val list = json.optJSONArray("list") ?: json.optJSONArray("data") ?: JSONArray("[]")
            
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val id = item.optString("vod_id")
                val name = item.optString("vod_name", query)
                val poster = item.optString("vod_pic")
                val year = item.optString("vod_year")
                val typeId = item.optString("type_id").toIntOrNull() ?: 0
                val type = if (typeId > 1) TvType.TvSeries else TvType.Movie
                
                results.add(newMovieSearchResponse(name, "$mainUrl/vod/$id", type) {
                    this.posterUrl = poster
                    this.year = year.toIntOrNull()
                })
            }
        }
        
        return results.distinctBy { it.name }
    }
    
    override suspend fun load(url: String): LoadResponse? {
        val vodId = url.replace("$mainUrl/vod/", "")
        
        val resp = app.post(
            "$mainUrl/api/vod/info_new",
            data = mapOf(
                "vod_id" to vodId,
                "sign" to SIGN,
                "cur_time" to System.currentTimeMillis().toString(),
                "audio_type" to "0"
            ),
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded")
        )
        val decrypted = decryptShok(resp.text)
        val json = parseJsonSafe(decrypted) ?: return null
        
        val vod = if (json.has("vod_info")) json.getJSONObject("vod_info") else json
        
        val name = vod.optString("vod_name", "Unknown")
        val poster = vod.optString("vod_pic")
        val plot = vod.optString("vod_blurb")
        val year = vod.optString("vod_year").toIntOrNull()
        val typeId = vod.optString("type_id").toIntOrNull() ?: 0
        val type = if (typeId > 1) TvType.TvSeries else TvType.Movie
        
        val episodes = mutableListOf<Episode>()
        val playlists = vod.optJSONArray("vod_play_list") ?: JSONArray("[]")
        
        for (p in 0 until playlists.length()) {
            val playlist = playlists.getJSONObject(p)
            val playUrl = playlist.optString("play_url")
            val lines = playUrl.split("#")
            
            for (line in lines) {
                val parts = line.split("\\$".toRegex())
                if (parts.size >= 2) {
                    val epName = parts[0].trim()
                    val epUrl = parts[1].trim()
                    val epNum = parseEpisodeNumber(epName)
                    episodes.add(newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                    })
                }
            }
        }
        
        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(name, url, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(name, url, type, vodId) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("http")) {
            callback.invoke(
                newExtractorLink("PPCine", data, data, ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                }
            )
            return true
        }
        
        val resp = app.post(
            "$mainUrl/api/vod/info_new",
            data = mapOf(
                "vod_id" to data,
                "sign" to SIGN,
                "cur_time" to System.currentTimeMillis().toString(),
                "audio_type" to "0"
            ),
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded")
        )
        val decrypted = decryptShok(resp.text)
        val json = parseJsonSafe(decrypted) ?: return false
        
        val vod = if (json.has("vod_info")) json.getJSONObject("vod_info") else json
        val playlists = vod.optJSONArray("vod_play_list") ?: JSONArray("[]")
        
        for (p in 0 until playlists.length()) {
            val playlist = playlists.getJSONObject(p)
            val playUrl = playlist.optString("play_url")
            val serverName = playlist.optString("from", "Server ${p + 1}")
            val lines = playUrl.split("#")
            
            for (line in lines) {
                val parts = line.split("\\$".toRegex())
                if (parts.size >= 2) {
                    val epName = parts[0].trim()
                    val streamUrl = parts[1].trim()
                    
                    if (streamUrl.contains(".m3u8") || streamUrl.contains(".mp4") || streamUrl.startsWith("http")) {
                        callback.invoke(
                            newExtractorLink(serverName, epName, streamUrl, ExtractorLinkType.VIDEO) {
                                this.referer = mainUrl
                                this.headers = mapOf("User-Agent" to "PPCine/63")
                            }
                        )
                    }
                }
            }
        }
        
        return true
    }
    
    private fun parseEpisodeNumber(name: String): Int? {
        val match = Regex("""(?i)(?:epis[oó]dio|ep)[\s_-]*(\d+)""").find(name)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
    
    private fun parseVideoList(list: JSONArray): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val id = item.optString("vod_id")
            val name = item.optString("vod_name", "Unknown")
            val poster = item.optString("vod_pic")
            val typeId = item.optString("type_id").toIntOrNull() ?: 0
            val type = if (typeId > 1) TvType.TvSeries else TvType.Movie
            
            results.add(newMovieSearchResponse(name, "$mainUrl/vod/$id", type) {
                this.posterUrl = poster
            })
        }
        return results
    }
    
    fun decryptShok(shokString: String): String {
        if (!shokString.startsWith("SHOK")) return shokString
        
        val b64Data = shokString.substring(4)
        val raw = Base64.getDecoder().decode(b64Data)
        
        val encrypted = raw.copyOfRange(45, raw.size)
        
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, getShokKey(), getShokIv())
        val decrypted = cipher.doFinal(encrypted)
        
        val padLen = decrypted[decrypted.size - 1].toInt()
        val actualLen = decrypted.size - padLen
        
        val jsonBytes = decrypted.copyOfRange(16, actualLen)
        
        return String(jsonBytes, Charsets.UTF_8)
    }
    
    fun parseJsonSafe(jsonString: String): JSONObject? {
        try {
            return JSONObject(jsonString)
        } catch (e: Exception) {
            var cleaned = jsonString.trim()
            
            val prefixMatch = Regex("""^\d+\s*,\s*""").find(cleaned)
            if (prefixMatch != null) {
                cleaned = cleaned.substring(prefixMatch.range.endInclusive + 1)
            }
            
            if (cleaned.startsWith("\"") && !cleaned.startsWith("{")) {
                cleaned = "{" + cleaned
            }
            if (!cleaned.endsWith("}")) {
                cleaned = cleaned + "}"
            }
            
            try {
                return JSONObject(cleaned)
            } catch (e: Exception) {
                val start = cleaned.indexOf('{')
                if (start >= 0) {
                    val end = cleaned.indexOf('}', start)
                    if (end > start) {
                        try {
                            return JSONObject(cleaned.substring(start, end + 1))
                        } catch (e: Exception) {
                            return null
                        }
                    }
                }
                return null
            }
        }
    }
}
