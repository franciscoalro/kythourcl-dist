package com.CineGato

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import android.util.Base64
import java.util.UUID
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

    private val UA = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val REAL_DEVICE_ID = "e88383e97ba1da73"
    private val REAL_GUID = "5c1f010f3910c266a8775f0a35db4dbd"
    private val PACKAGE_NAME = "com.naughty.cinegato"
    private val APP_VERSION = "2.1.2"
    private val SIGN_APP_KEY = "T!BgJBAppSf"
    private val SIGN_KEY_STATIC = "T!BgJB"

    private var cachedAesKey: ByteArray? = null

    override val mainPage = mainPageOf(
        "Em Alta" to "em_alta",
        "Lançamentos" to "lancamentos",
        "Filmes" to "filmes",
        "Séries" to "series"
    )

    private fun getApiHeaders(timestamp: String? = null): Map<String, String> {
        val ts = timestamp ?: System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        return mapOf(
            "User-Agent" to UA,
            "version" to APP_VERSION,
            "clientType" to "1",
            "deviceId" to REAL_DEVICE_ID,
            "guid" to REAL_GUID,
            "channel" to "google",
            "timestamp" to ts,
            "nonce" to nonce,
            "lang" to "pt-BR",
            "Content-Type" to "application/json; charset=utf-8",
            "Accept" to "application/json"
        )
    }

    /**
     * Obtém a chave AES dinamicamente do endpoint do servidor + constante JNI libbasedqso.so.
     */
    private suspend fun getAesKey(): ByteArray {
        cachedAesKey?.let { return it }
        return try {
            val secResp = app.get(
                "$mainUrl/v0.1/system/getSecurityKey/1",
                headers = mapOf("User-Agent" to UA)
            )
            val json = tryParseJson<JSONObject>(secResp.text)
            val secB64 = json?.optString("data", "") ?: ""
            val dynamicPart = if (secB64.isNotBlank()) {
                String(Base64.decode(secB64, Base64.NO_WRAP), Charsets.UTF_8)
            } else {
                "fJATm*kgfJ"
            }
            val fullKeyStr = dynamicPart + SIGN_KEY_STATIC
            val keyBytes = fullKeyStr.toByteArray(Charsets.UTF_8)
            cachedAesKey = keyBytes
            keyBytes
        } catch (e: Exception) {
            val fallbackKey = ("fJATm*kgfJ" + SIGN_KEY_STATIC).toByteArray(Charsets.UTF_8)
            cachedAesKey = fallbackKey
            fallbackKey
        }
    }

    /**
     * Descriptografa o envelope AES-128-CBC do CineGato (PKCS5Padding, IV = Key).
     */
    private suspend fun decryptPayload(encryptedB64: String): String? {
        return try {
            val key = getAesKey()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(key)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val ct = Base64.decode(encryptedB64.trim(), Base64.NO_WRAP)
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()

        try {
            val bannerUrl = "$mainUrl/film-api/v1.9.8/banner/getBannerByClientAndLocation?clientType=1&location=1"
            val resp = app.get(bannerUrl, headers = getApiHeaders())
            val decrypted = decryptPayload(resp.text)
            if (decrypted != null) {
                val json = tryParseJson<JSONObject>(decrypted)
                val list = json?.optJSONArray("data")
                val searchList = mutableListOf<SearchResponse>()
                if (list != null) {
                    for (i in 0 until list.length()) {
                        val obj = list.optJSONObject(i) ?: continue
                        val mid = obj.optString("movieId", obj.optString("id"))
                        val title = obj.optString("title", obj.optString("name", "Filme $mid"))
                        val poster = obj.optString("coverVerticalImage", obj.optString("cover", ""))
                        val type = if (obj.optInt("movieType", 1) == 2) TvType.Movie else TvType.TvSeries
                        if (mid.isNotBlank()) {
                            searchList.add(
                                newMovieSearchResponse(title, "$mainUrl/film/$mid", type) {
                                    this.posterUrl = poster
                                }
                            )
                        }
                    }
                }
                if (searchList.isNotEmpty()) {
                    items.add(HomePageList(request.name, searchList))
                }
            }
        } catch (e: Exception) {}

        // Se a chamada à API não trouxer dados, mantemos destaques populares
        if (items.isEmpty()) {
            val defaultList = listOf(
                newMovieSearchResponse(
                    "Batman",
                    "$mainUrl/film/6610078462420992",
                    TvType.Movie
                ) {
                    this.posterUrl = "https://img2.fumdx.com/image/f4a16aac18f227e5530fca5a9a1565f94cc5f91e.jpg"
                }
            )
            items.add(HomePageList(request.name, defaultList))
        }

        return newHomePageResponse(items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val searchUrl = "$mainUrl/film-api/v2.1.2/movie/searchByKeyword?keyword=$query&clientType=1&packageName=$PACKAGE_NAME&lang=pt-BR"
            val resp = app.get(searchUrl, headers = getApiHeaders())
            val decrypted = decryptPayload(resp.text)
            if (decrypted != null) {
                val json = tryParseJson<JSONObject>(decrypted)
                val data = json?.optJSONObject("data")
                val rows = data?.optJSONArray("rows")
                if (rows != null) {
                    for (i in 0 until rows.length()) {
                        val obj = rows.optJSONObject(i) ?: continue
                        val mid = obj.optString("id", "")
                        val title = obj.optString("title", "")
                        val poster = obj.optString("coverVerticalImage", obj.optString("coverHorizontalImage", ""))
                        val movieType = obj.optInt("movieType", 2)
                        val type = if (movieType == 2) TvType.Movie else TvType.TvSeries
                        if (mid.isNotBlank()) {
                            results.add(
                                newMovieSearchResponse(title, "$mainUrl/film/$mid", type) {
                                    this.posterUrl = poster
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val mediaId = url.trimEnd('/').substringAfterLast("/film/")

        var title = "CineGato $mediaId"
        var plot: String? = null
        var poster: String? = null
        var year: Int? = null
        var isSeries = false

        try {
            val detailUrl = "$mainUrl/film-api/v2.1.2/movie?movieId=$mediaId&clientType=1&packageName=$PACKAGE_NAME&lang=pt-BR"
            val resp = app.get(detailUrl, headers = getApiHeaders())
            val decrypted = decryptPayload(resp.text)
            if (decrypted != null) {
                val json = tryParseJson<JSONObject>(decrypted)
                val data = json?.optJSONObject("data")
                if (data != null) {
                    title = data.optString("title", title)
                    plot = data.optString("briefIntroduction", null)
                    poster = data.optString("coverVerticalImage", data.optString("coverHorizontalImage", null))
                    isSeries = data.optInt("movieType", 2) != 2
                }
            }
        } catch (e: Exception) {}

        // Buscar lista de episódios
        val episodesList = mutableListOf<Episode>()
        try {
            val epUrl = "$mainUrl/film-api/v1.8.7/movie/getEpisodes?movieId=$mediaId&clientType=1&packageName=$PACKAGE_NAME&lang=pt-BR"
            val epResp = app.get(epUrl, headers = getApiHeaders())
            val epDec = decryptPayload(epResp.text)
            if (epDec != null) {
                val epJson = tryParseJson<JSONObject>(epDec)
                val data = epJson?.optJSONObject("data")
                val eps = data?.optJSONArray("episodes")
                if (eps != null) {
                    for (i in 0 until eps.length()) {
                        val epObj = eps.optJSONObject(i) ?: continue
                        val epId = epObj.optString("id", "")
                        val epNum = epObj.optInt("number", i + 1)
                        val epTitle = epObj.optString("title", "Episódio $epNum")
                        if (epId.isNotBlank()) {
                            episodesList.add(
                                newEpisode("$mainUrl/episode/$epId?mid=$mediaId") {
                                    this.name = epTitle
                                    this.episode = epNum
                                    this.posterUrl = poster
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        if (isSeries || episodesList.size > 1) {
            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodesList
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        val singleEpisodeId = if (episodesList.isNotEmpty()) {
            episodesList[0].data
        } else {
            "$mainUrl/episode/$mediaId?mid=$mediaId"
        }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            singleEpisodeId
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeId = if (data.contains("/episode/")) {
            data.substringAfter("/episode/").substringBefore("?")
        } else {
            data.substringAfterLast("/film/").substringBefore("?")
        }

        val movieId = if (data.contains("mid=")) {
            data.substringAfter("mid=").substringBefore("&")
        } else {
            episodeId
        }

        // Chamar o endpoint nativo getVideo2 com a chave apkSignKey oficial
        try {
            val videoUrl = "$mainUrl/film-api/v2.0.7/movie/getVideo2"
            val body = JSONObject().apply {
                put("episodeId", episodeId)
                put("movieId", movieId)
                put("clientType", 1)
                put("mode", 0)
                put("resolution", 0)
                put("apkSignKey", SIGN_APP_KEY)
                put("androidVersion", "14")
            }

            val resp = app.post(
                videoUrl,
                requestBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()),
                headers = getApiHeaders()
            )

            val decrypted = decryptPayload(resp.text)
            if (decrypted != null) {
                val json = tryParseJson<JSONObject>(decrypted)
                val dataObj = json?.optJSONObject("data")
                if (dataObj != null) {
                    val mainM3u8 = dataObj.optString("videoUrl", "")
                    if (mainM3u8.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name Principal",
                                url = mainM3u8,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://api.hwcty.info/"
                            }
                        )
                    }

                    // Extrair legendas oficiais
                    val subs = dataObj.optJSONArray("subtitles")
                    if (subs != null) {
                        for (i in 0 until subs.length()) {
                            val subObj = subs.optJSONObject(i) ?: continue
                            val subUrl = subObj.optString("url", "")
                            val subTitle = subObj.optString("title", "Legenda")
                            if (subUrl.isNotBlank()) {
                                subtitleCallback(
                                    SubtitleFile(
                                        lang = subTitle,
                                        url = subUrl
                                    )
                                )
                            }
                        }
                    }

                    return true
                }
            }
        } catch (e: Exception) {}

        return false
    }
}
