package com.RedeCanaisAF

internal object RedeCanaisAFText {
    fun isJunkText(text: String): Boolean {
        val lower = text.lowercase()
        val junk = listOf(
            "caso o vídeo", "se o vídeo não", "problema para assistir",
            "redecanais", "rede canais", "todos os direitos reservados",
            "reportar erro", "clique aqui", "navegador recomendado",
            "baixe o app", "grupo telegram", "compartilhe com seus amigos",
            "lista de episódios", "todas as temporadas"
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
}
