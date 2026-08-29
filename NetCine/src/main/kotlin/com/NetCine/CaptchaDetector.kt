package com.NetCine

/**
 * Detector rápido de captcha usado no NetCine.kt
 * Replica a lógica de loadLinks: checa se o HTML parece captcha
 */
object CaptchaDetector {
    fun looksLikeCaptcha(html: String): Boolean {
        if (html.isBlank()) return false
        val lower = html.lowercase()
        return lower.contains("captcha") ||
               html.contains("Verificação Humana") ||
               html.contains("verificação humana", ignoreCase = true) ||
               html.contains("?captcha_img=1") ||
               html.contains("captcha_input") ||
               html.contains("captcha_img")
    }

    fun isCaptchaPng(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return bytes[0] == 0x89.toByte() &&
               bytes[1] == 0x50.toByte() &&
               bytes[2] == 0x4E.toByte() &&
               bytes[3] == 0x47.toByte()
    }

    fun debug(html: String): String {
        val hits = mutableListOf<String>()
        if (html.contains("captcha", ignoreCase = true)) hits.add("captcha")
        if (html.contains("Verificação Humana")) hits.add("Verificação Humana")
        if (html.contains("?captcha_img=1")) hits.add("?captcha_img=1")
        if (html.contains("captcha_input")) hits.add("captcha_input")
        return if (hits.isEmpty()) "SEM captcha" else "CAPTCHA -> ${hits.joinToString(", ")}"
    }
}
