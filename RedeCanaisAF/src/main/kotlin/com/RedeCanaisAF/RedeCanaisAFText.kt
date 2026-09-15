package com.RedeCanaisAF

import com.lagradost.cloudstream3.TvType

internal object RedeCanaisAFText {
    val PLACEHOLDER_PATTERNS = listOf(
        "echo-lzld",
        "blank.gif",
        "pixel.gif",
        "no-thumbnail",
        "default-thumbnail",
        "lazy.png",
        "1x1",
        "data:image/gif;base64,R0lGOD"
    )

    val SERIES_URL_KEYWORDS = listOf(
        "lista-de-episodios", "todas-as-temporadas", "temporada", "temporadas",
        "serie", "series", "animes", "anime", "desenhos", "desenho",
        "episodio", "episodios", "completo-dublado", "temp", "browse-"
    )

    val SERIES_TITLE_KEYWORDS = listOf(
        "Temporada", "Temp", "Episódio", "Episodio", "Ep.", "Ep ",
        "Completo Dublado", "Lista de Episódios", "Todas as Temporadas",
        "1ª", "2ª", "3ª", "4ª", "5ª", "6ª", "7ª", "8ª", "9ª"
    )

    fun isJunkText(text: String): Boolean {
        val lower = text.lowercase()
        val junk = listOf(
            "caso o vídeo", "se o vídeo não", "problema para assistir",
            "todos os direitos reservados",
            "reportar erro", "clique aqui", "navegador recomendado",
            "baixe o app", "grupo telegram", "compartilhe com seus amigos",
            "web server is returning", "error code 520", "error code 522",
            "error code 524", "just a moment", "checking your browser", "attention required"
        )
        return junk.any { lower.contains(it) } && text.length < 150
    }

    fun cleanMediaTitle(raw: String): String {
        var title = raw
            .replace(Regex("""(?i)\s*[-|–—/]?\s*Rede\s*Canais.*$"""), "")
            .replace(Regex("""(?i)^Assistir\s+"""), "")
            .replace(Regex("""(?i)\s*[-|–—/]?\s*Assistir\s+Online.*$"""), "")
            .replace(Regex("""(?i)\s+Online(\s+Gr[aá]tis|\s+em\s+HD|\s+HD)?\b"""), "")
            .replace(Regex("""(?i)\s*[-|–—/]?\s*Lista\s+de\s+Epis[oó]dios.*$"""), "")
            .replace(Regex("""(?i)\s*[-|–—/]?\s*Todas\s+as\s+Temporadas.*$"""), "")
            .replace(Regex("""(?i)\s*[-|–—/]?\s*Completo\s+(?:Dublado|Legendado)?.*$"""), "")
            .replace(Regex("""(?i)\s*\([^)]*(?:Dublado|Legendado|Nacional|Dual|Temporada|Epis[oó]dio)[^)]*\)"""), "")
            .replace(Regex("""(?i)\s*\[[^]]*(?:Dublado|Legendado|Nacional|Dual|Temporada|Epis[oó]dio)[^]]*]"""), "")
            .replace(Regex("""(?i)\s*[-|–—/]?\s*(?:Dublado|Legendado|Nacional|Dual\s*[AÁ]udio).*$"""), "")
            .replace(Regex("""(?i)\b(?:720p|1080p|4k|uhd|fhd|hd|sd|cam|ts)\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        title = title.replace(Regex("""[\s(\[\-–—/:]+$"""), "").trim()
        return title
    }

    fun cleanPlotText(text: String): String {
        return text
            .replace(Regex("""(?i)^\s*Sinopse\s*:\s*"""), "")
            .replace(Regex("""(?i)\s*Rede\s*Canais.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun isSeriesUrlOrTitle(url: String, title: String): Boolean {
        val urlLower = url.lowercase()
        if (SERIES_URL_KEYWORDS.any { urlLower.contains(it) }) return true
        return SERIES_TITLE_KEYWORDS.any { title.contains(it, ignoreCase = true) }
    }

    fun extractSeasonHeaderNumber(text: String): Int? {
        val match = Regex("""(?i)(?:^|[^\w])(\d+)[ªaºo°]?\s*(?:temp|temporada|season)\b""").find(text)
            ?: Regex("""(?i)\b(?:temporada|temp|season)\s*(\d+)\b""").find(text)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun extractSeasonNumber(text: String): Int? {
        val compact = Regex("""(?i)\bS(\d+)\s*E\d+\b""").find(text)
            ?: Regex("""(?i)\b(\d+)x\d+\b""").find(text)
        if (compact != null) return compact.groupValues[1].toIntOrNull()

        val word = Regex("""(?i)(\d+)[ªaºo°]?\s*[-_]?\s*(?:temporada|temp|season)\b""").find(text)
            ?: Regex("""(?i)\b(?:temporada|temp|season)[-_]?\s*(\d+)\b""").find(text)
            ?: Regex("""(?i)\bT(\d+)\b""").find(text)
        return word?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun extractEpisodeNumber(text: String): Int? {
        val compact = Regex("""(?i)\bS\d+\s*E(\d+)\b""").find(text)
            ?: Regex("""(?i)\b\d+x(\d+)\b""").find(text)
        if (compact != null) return compact.groupValues[1].toIntOrNull()

        return Regex("""(?i)\b(?:epis[oó]dio|ep|e)[-_.\s]*(\d+)\b""").find(text)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)\b(?:cap[ií]tulo|cap)[-_.\s]*(\d+)\b""").find(text)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun extractSeasonAndEpisode(text: String, url: String, fallbackSeason: Int = 1): Pair<Int, Int> {
        val season = extractSeasonNumber(text)
            ?: extractSeasonNumber(url)
            ?: fallbackSeason

        val epNum = extractEpisodeNumber(text)
            ?: extractEpisodeNumber(url)
            ?: 1

        return Pair(season, epNum)
    }

    fun cleanEpisodeTitle(raw: String, episodeNumber: Int): String {
        val cleaned = raw
            .replace(Regex("""(?i)\s*[-|]\s*Rede\s*Canais.*$"""), "")
            .replace(Regex("""(?i)^Assistir\s+"""), "")
            .replace(Regex("""(?i)\s+Online(\s+Gr[aá]tis|\s+em\s+HD|\s+HD)?\b"""), "")
            .replace(Regex("""(?i)\s*[-|–—/]?\s*(?:Dublado|Legendado|Nacional|Dual\s*[AÁ]udio).*$"""), "")
            .replace(Regex("""(?i)\s*\((?:Dublado|Legendado|Nacional|Dual\s*[AÁ]udio)\)"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        return if (cleaned.isBlank() || cleaned.equals("assistir", true) ||
            cleaned.equals("online", true) || cleaned.length < 3
        ) {
            "Episódio $episodeNumber"
        } else {
            cleaned
        }
    }

    fun isPlaceholderImage(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isBlank() || trimmed == "/" || trimmed == "#" ||
            trimmed == "https://redecanais.af" || trimmed == "https://redecanais.af/" ||
            trimmed == "http://redecanais.af" || trimmed == "http://redecanais.af/" ||
            trimmed.endsWith(".html", ignoreCase = true) ||
            trimmed.endsWith(".php", ignoreCase = true)) return true
        if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            return trimmed.startsWith("data:image/svg+xml", ignoreCase = true) && trimmed.length < 200
        }
        if (trimmed.startsWith("data:image/svg+xml", ignoreCase = true) && trimmed.length < 200) return true
        return PLACEHOLDER_PATTERNS.any { trimmed.contains(it, ignoreCase = true) }
    }

    fun parseSrcset(srcset: String, fixUrl: (String) -> String = { it }): String {
        if (srcset.isBlank()) return ""
        val candidates = srcset.split(",").mapNotNull { raw ->
            val entry = raw.trim()
            if (entry.isEmpty()) return@mapNotNull null
            val descriptorMatch = Regex("""^(.*?)[\s]+(\d+(?:\.\d+)?[wx]|\d+(?:\.\d+)?)$""", RegexOption.IGNORE_CASE)
                .matchEntire(entry)
            val urlRaw: String
            val descriptor: String
            if (descriptorMatch != null) {
                urlRaw = descriptorMatch.groupValues[1].trim()
                descriptor = descriptorMatch.groupValues[2]
            } else {
                urlRaw = entry
                descriptor = ""
            }
            if (urlRaw.isEmpty()) return@mapNotNull null
            val urlFixed = urlRaw.replace("%20", " ")
            val w = Regex("""^(\d+)w$""", RegexOption.IGNORE_CASE).matchEntire(descriptor)?.groupValues?.get(1)?.toIntOrNull()
            val d = if (w == null) {
                Regex("""^([\d.]+)x$""", RegexOption.IGNORE_CASE).matchEntire(descriptor)?.groupValues?.get(1)?.toFloatOrNull() ?: 1.0f
            } else 1.0f
            val normalized = optimizePosterUrl(urlFixed, fixUrl)
            if (normalized.isBlank() || isPlaceholderImage(normalized)) return@mapNotNull null
            Triple(normalized, w ?: -1, d)
        }
        val best = candidates.maxWithOrNull(
            compareBy<Triple<String, Int, Float>> { it.second }
                .thenBy { it.third }
                .thenBy { -it.first.length }
        )
        return best?.first ?: ""
    }

    fun optimizePosterUrl(url: String, fixUrl: (String) -> String = { it }): String {
        val trimmed = url.trim()
        if (trimmed.isBlank() || isPlaceholderImage(trimmed)) return ""
        if (trimmed.startsWith("data:image/", ignoreCase = true)) return trimmed

        val resolved = if (trimmed.startsWith("/")) {
            val base = fixUrl("").ifBlank { "https://redecanais.af" }.trimEnd('/')
            "$base$trimmed"
        } else {
            fixUrl(trimmed)
        }

        val absoluteUrl = if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) {
            val base = fixUrl("").ifBlank { "https://redecanais.af" }.trimEnd('/')
            "$base/${resolved.trimStart('/')}"
        } else {
            resolved
        }

        val rawUrl = absoluteUrl.replace(" ", "%20")
        val stripped = rawUrl.trimEnd('/')
        if (stripped == "https://redecanais.af" || stripped == "http://redecanais.af" || isPlaceholderImage(rawUrl)) {
            return ""
        }
        return if (rawUrl.contains("redecanais.af") || rawUrl.contains("/imgs-videos/")) {
            LocalImageProxy.wrapUrl(rawUrl)
        } else {
            rawUrl
        }
    }

    fun extractDurationMinutes(durText: String): Int? {
        if (durText.isBlank()) return null

        val isoH = Regex("""(?i)(\d+)H""").find(durText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val isoM = Regex("""(?i)(\d+)M""").find(durText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        if (isoH > 0 || isoM > 0) return isoH * 60 + isoM

        val hours = Regex("""(?i)(\d+)\s*(?:h|hora|horas)""").find(durText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(?i)(\d+)\s*(?:min|m|minuto|minutos)""").find(durText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        if (hours > 0 || minutes > 0) return hours * 60 + minutes

        val plainMin = Regex("""\b(\d{2,3})\b""").find(durText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (plainMin != null && plainMin in 1..600) return plainMin

        return null
    }

    fun extractYearFromTitleOrText(rawTitle: String, fallbackText: String? = null): Int? {
        val fromTitle = Regex("""\b(19\d{2}|20\d{2})\b""").find(rawTitle)?.value?.toIntOrNull()
        if (fromTitle != null) return fromTitle

        if (!fallbackText.isNullOrBlank()) {
            val fromFallback = Regex("""\b(19\d{2}|20\d{2})\b""").find(fallbackText)?.value?.toIntOrNull()
            if (fromFallback != null) return fromFallback
        }

        return null
    }

    fun extractYear(text: String): Int? = extractYearFromTitleOrText(text)

    fun determineTvType(url: String, tags: List<String> = emptyList(), isSeries: Boolean = false): TvType {
        val lowerUrl = url.lowercase()
        val allTags = tags.joinToString(" ").lowercase()
        if (lowerUrl.contains("anime") || allTags.contains("anime")) return TvType.Anime
        if (lowerUrl.contains("desenho") || allTags.contains("desenho") || allTags.contains("animação") || allTags.contains("animacao")) return TvType.Cartoon
        if (isSeries || lowerUrl.contains("serie") || allTags.contains("série") || allTags.contains("serie")) return TvType.TvSeries
        return TvType.Movie
    }

    private fun normalizeForSearch(text: String): String {
        val decomposed = java.text.Normalizer.normalize(text.lowercase().trim(), java.text.Normalizer.Form.NFD)
        return Regex("\\p{InCombiningDiacriticalMarks}+").replace(decomposed, "")
    }

    fun isRelevantSearchTitle(itemTitle: String, query: String): Boolean {
        if (query.isBlank()) return true
        val normTitle = normalizeForSearch(itemTitle)
        val normQuery = normalizeForSearch(query)
        if (normTitle.contains(normQuery)) return true
        val tokens = normQuery.split(" ").filter { it.isNotBlank() }
        return tokens.isNotEmpty() && tokens.all { normTitle.contains(it) }
    }

    fun isValidEpisodeLink(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (lower.contains("browse-") || lower.contains("category") || lower.contains("#") || 
            lower.contains("javascript") || lower.contains("lista-de-episodios") || 
            lower.contains("facebook.com") || lower.contains("twitter.com") || lower.contains("whatsapp")) {
            return false
        }
        return lower.contains(".html") || lower.contains("vid=") || lower.contains("video.php") || lower.contains("watch.php")
    }
}
