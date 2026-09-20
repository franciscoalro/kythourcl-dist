package com.CineGato

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Headers
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.security.MessageDigest
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CineGato : MainAPI() {
    override var mainUrl = "https://api.hwcty.info"
    override var name = "CineGato"
    override val hasMainPage = true
    override var lang = "pt"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private var registered = false
    private val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val AK by lazy { generateAK() }

    private fun generateAK(): String {
        val uuid = UUID.randomUUID().toString()
        val input = "~$uuid CineGato HWCTY Android/2.1.2 (153)"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    override val mainPage = mainPageOf(
        "home" to "https://api.hwcty.info/film-api/home"
    )

    private data class CachedIds(
        val deviceId: String,
        val guid: String,
        val psid: String,
        val ps: String,
        val pm: String,
        val appId: String,
        val trackId: String
    )

    private fun buildRegisterBody(): JSONObject {
        val cachedIds = CachedIds(
            deviceId = "d" + UUID.randomUUID().toString().replace("-", "").take(16),
            guid = "g" + UUID.randomUUID().toString().replace("-", "").take(16),
            psid = UUID.randomUUID().toString().replace("-", "").take(16),
            ps = UUID.randomUUID().toString().replace("-", "").take(8),
            pm = UUID.randomUUID().toString().replace("-", "").take(8),
            appId = UUID.randomUUID().toString().replace("-", "").take(16),
            trackId = UUID.randomUUID().toString().replace("-", "").take(16)
        )

        return JSONObject().apply {
            put("os_vn", "14")
            put("os_vc", "34")
            put("package_name", "com.naughty.cinegato")
            put("app_vn", "2.1.2")
            put("app_vc", 153)
            put("device_id", cachedIds.deviceId)
            put("guid", cachedIds.guid)
            put("psid", cachedIds.psid)
            put("ps", cachedIds.ps)
            put("pm", cachedIds.pm)
            put("app_id", cachedIds.appId)
            put("track_id", cachedIds.trackId)
        }
    }

    private fun extractCookiesFromResponse(headers: Headers): String {
        return headers.values("set-cookie").joinToString("; ") { cookie ->
            cookie.substringBefore(";").trim()
        }
    }

    private fun mergeCookies(existing: String, new: String): String {
        val map = mutableMapOf<String, String>()
        existing.split(";").forEach { part ->
            val cookiePart = part.trim()
            if (cookiePart.isNotEmpty()) {
                val key = cookiePart.substringBefore("=").trim()
                map[key] = cookiePart
            }
        }
        new.split(";").forEach { part ->
            val cookiePart = part.trim()
            if (cookiePart.isNotEmpty()) {
                val key = cookiePart.substringBefore("=").trim()
                map[key] = cookiePart
            }
        }
        return map.values.joinToString("; ")
    }

    private val apiHeaders = mapOf(
        "User-Agent" to UA,
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "Authorization" to "Bearer $AK"
    )

    private val browserHeaders = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
    )

    private var sessionCookies: String = ""

    private suspend fun ensureRegistered(): Boolean {
        if (registered) return true

        val baseUrl = this.mainUrl
        val regUrl = "$baseUrl/user-layer/v1.9.8/pu/popup"

        val jsonResp = app.post(
            regUrl,
            requestBody = buildRegisterBody().toString().toRequestBody("application/json".toMediaType()),
            headers = apiHeaders
        )
        sessionCookies = extractCookiesFromResponse(jsonResp.headers)
        registered = true
        return true
    }

    /**
     * Decrypts an AES-128-CBC/PKCS5Padding enveloped payload.
     * Envelope format: magic (4B) + header (12B) + IV (16B at pos 4-19) + ciphertext (from pos 16)
     * Returns plaintext JSON string or null on failure.
     */
    private fun decryptAesEnvelope(envelopedB64: String, ak: String): String? {
        return try {
            val akRaw = Base64.decode(ak, Base64.NO_WRAP)
            val akBytes = if (akRaw.size >= 16) akRaw.copyOf(16) else ak.toByteArray(Charsets.UTF_8)
            val enveloped = Base64.decode(envelopedB64, Base64.NO_WRAP)
            if (enveloped.size < 20) return null
            val iv = enveloped.copyOfRange(4, 20)
            val ciphertext = enveloped.copyOfRange(16, enveloped.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(akBytes, "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getStreamInfo(mediaId: String, episodeId: String?, key: String): String? {
        val url = "${this.mainUrl}/film-layer/v1.9.8/episode/info"
        val data = JSONObject().apply {
            put("media_id", mediaId)
            episodeId?.let { put("episode_id", it) }
            put("key", key)
        }

        return try {
            val resp = app.post(
                url,
                requestBody = data.toString().toRequestBody("application/json".toMediaType()),
                headers = apiHeaders + mapOf("Cookie" to sessionCookies)
            )
            val json = JSONObject(resp.text)
            val streamUrl = json.optString("stream_url", json.optString("url", ""))
            // stream_url is AES-enveloped — decrypt it
            if (streamUrl.startsWith("crypt:")) {
                val b64 = streamUrl.removePrefix("crypt:")
                val plaintext = decryptAesEnvelope(b64, AK)
                if (plaintext != null) {
                    val inner = tryParseJson<JSONObject>(plaintext)
                    inner?.optString("url", plaintext)
                } else streamUrl
            } else streamUrl
        } catch (e: Exception) { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!ensureRegistered()) return newHomePageResponse(request.name, listOf(), false)

        val url = "${this.mainUrl}/film-api/home"
        val items = mutableListOf<HomePageList>()

        try {
            val resp = app.get(url, headers = apiHeaders + mapOf("Cookie" to sessionCookies))
            val json = tryParseJson<JSONObject>(resp.text) ?: return newHomePageResponse(request.name, listOf(), false)

            val sections = listOf(
                "Em Alta" to json.optJSONArray("em_alta"),
                "Lançamentos" to json.optJSONArray("lancamentos"),
                "Assistir de novo" to json.optJSONArray("continue"),
                "Recomendados" to json.optJSONArray("recomendados")
            )

            for ((sectionName, sectionArray) in sections) {
                if (sectionArray == null) continue
                val mediaItems = mutableListOf<SearchResponse>()
                for (i in 0 until sectionArray.length()) {
                    val obj = sectionArray.optJSONObject(i) ?: continue
                    val mediaId = obj.optString("id")
                    val title = obj.optString("title", obj.optString("name", ""))
                    val poster = obj.optString("thumb", obj.optString("poster", ""))
                    val year = obj.optInt("year", 0)
                    val isMovie = obj.optBoolean("is_movie", true)
                    val type = if (isMovie) TvType.Movie else TvType.TvSeries

                    mediaItems.add(
                        newMovieSearchResponse(title, "${this.mainUrl}/film/$mediaId", type) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    )
                }
                if (mediaItems.isNotEmpty()) {
                    items.add(
                        HomePageList(sectionName, mediaItems)
                    )
                }
            }
        } catch (e: Exception) { }

        return newHomePageResponse(items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (!ensureRegistered()) return emptyList()

        val url = "${this.mainUrl}/film-api/search"
        val results = mutableListOf<SearchResponse>()

        try {
            val resp = app.get("$url?q=$query", headers = apiHeaders + mapOf("Cookie" to sessionCookies))
            val array = tryParseJson<JSONArray>(resp.text) ?: return emptyList()

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val mediaId = obj.optString("id")
                val title = obj.optString("title", obj.optString("name", ""))
                val poster = obj.optString("thumb", obj.optString("poster", ""))
                val isMovie = obj.optBoolean("is_movie", true)
                val type = if (isMovie) TvType.Movie else TvType.TvSeries

                results.add(
                    newMovieSearchResponse(title, "${this.mainUrl}/film/$mediaId", type) {
                        this.posterUrl = poster
                    }
                )
            }
        } catch (e: Exception) { }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        if (!ensureRegistered()) return newMovieLoadResponse("Erro", url, TvType.Movie, url) {}

        val mediaId = url.trimEnd('/').substringAfterLast("/film/")
        val detailUrl = "${this.mainUrl}/film-api/film/$mediaId"

        try {
            val resp = app.get(detailUrl, headers = apiHeaders + mapOf("Cookie" to sessionCookies))
            val json = tryParseJson<JSONObject>(resp.text) ?: return newMovieLoadResponse("Erro", url, TvType.Movie, url) {}

            val title = json.optString("title", json.optString("name", ""))
            val poster = json.optString("thumb", json.optString("poster", ""))
            val description = json.optString("description", json.optString("sinopse", ""))
            val year = json.optInt("year", 0)
            val duration = json.optInt("duration", 0)
            val isMovie = json.optBoolean("is_movie", true)
            val rating = json.optDouble("rating", 0.0).takeIf { it > 0 }?.let { Score.from10(it) }

            if (isMovie) {
                val encryptUrl = "${this.mainUrl}/film-layer/v1.9.8/episode/key"
                val encResp = app.post(
                    encryptUrl,
                    requestBody = JSONObject().apply { put("media_id", mediaId) }.toString()
                        .toRequestBody("application/json".toMediaType()),
                    headers = apiHeaders + mapOf("Cookie" to sessionCookies)
                )
                val encJson = tryParseJson<JSONObject>(encResp.text)
                val encryptedId = encJson?.optString("media_id", "") ?: ""
                val keyPart = AK.take(16)

                val streamUrl = getStreamInfo(mediaId, null, keyPart)

                return newMovieLoadResponse(title, streamUrl ?: url, TvType.Movie, streamUrl ?: url) {
                    this.posterUrl = poster
                    this.year = year
                    this.duration = duration
                    this.score = rating
                    this.plot = description
                }
            } else {
                val episodesArray = json.optJSONArray("episodes") ?: json.optJSONArray("temporadas")
                val episodeList = mutableListOf<Episode>()

                if (episodesArray != null) {
                    for (i in 0 until episodesArray.length()) {
                        val epObj = episodesArray.optJSONObject(i) ?: continue
                        val epId = epObj.optString("id")
                        val epTitle = epObj.optString("title", epObj.optString("name", ""))
                        val epNumber = epObj.optInt("number", epObj.optInt("episode_number", i + 1))
                        val epSeason = epObj.optInt("season", 1)
                        val epThumb = epObj.optString("thumb", "")

                        episodeList.add(
                            newEpisode("${this.mainUrl}/episode/$epId") {
                                this.name = epTitle
                                this.episode = epNumber
                                this.season = epSeason
                                this.posterUrl = epThumb
                            }
                        )
                    }
                }

                return newMovieLoadResponse(title, url, TvType.TvSeries, episodeList) {
                    this.posterUrl = poster
                    this.year = year
                    this.duration = duration
                    this.score = rating
                    this.plot = description
                }
            }
        } catch (e: Exception) { }

        return newMovieLoadResponse("Erro", url, TvType.Movie, url) {}
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!ensureRegistered()) return false

        val (mediaId, episodeId) = if (data.contains("/episode/")) {
            val id = data.substringAfterLast("/episode/")
            "" to id
        } else {
            val id = data.trimEnd('/').substringAfterLast("/film/")
            id to null
        }

        val keyPart = AK.take(16)
        val streamUrl = getStreamInfo(mediaId, episodeId, keyPart)

        if (!streamUrl.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = this@CineGato.mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        val encryptUrl = "${this.mainUrl}/film-layer/v1.9.8/episode/key"
        try {
            val reqBody = JSONObject().apply {
                put("media_id", mediaId)
                episodeId?.let { put("episode_id", it) }
            }
            val encResp = app.post(
                encryptUrl,
                requestBody = reqBody.toString().toRequestBody("application/json".toMediaType()),
                headers = apiHeaders + mapOf("Cookie" to sessionCookies)
            )
            val obj = tryParseJson<JSONObject>(encResp.text) ?: return false

            val m3u8 = obj.optString("m3u8", obj.optString("streamUrl", ""))
            val authKey = obj.optString("auth_key", "")
            val expire = obj.optString("expire", "")

            if (m3u8.isNotBlank()) {
                val fullUrl = if (authKey.isNotBlank() && expire.isNotBlank()) {
                    val separator = if (m3u8.contains("?")) "&" else "?"
                    "$m3u8${separator}auth_key=$authKey&expire=$expire"
                } else m3u8

                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fullUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = this@CineGato.mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: Exception) { }

        return false
    }
}