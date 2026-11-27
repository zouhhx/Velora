package com.zouhu.speedlimit.data.settings

/**
 * Single place for secret masking/redaction. Never log a raw AK, SK, Bearer token
 * or a URL query parameter that carries one.
 */
object SecretRedaction {
    private val jsonField = Regex(
        "(\"(?:ak|sk|token|uid|key|authorization)\"\\s*:\\s*\")[^\"]*(\")",
        RegexOption.IGNORE_CASE,
    )
    private val queryParam = Regex(
        "(?i)(\\b(?:ak|sk|key|token|authorization)=)[^&\\s]+",
    )
    private val bearer = Regex(
        "(?i)(\\bBearer\\s+)[A-Za-z0-9._~+/=-]+",
    )
    private val prefixedKey = Regex("(?:5QQB" + "t|ONEH" + "10)[A-Za-z0-9]+")
    private val mimoKey = Regex("\\bsk-[A-Za-z0-9_-]{8,}\\b")

    fun redact(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var safe = jsonField.replace(text, "$1***$2")
        safe = queryParam.replace(safe, "$1***")
        safe = bearer.replace(safe, "$1***")
        safe = prefixedKey.replace(safe, "***")
        safe = mimoKey.replace(safe, "***")
        return safe
    }

    /** UI-only display, e.g. ONE••••V4; empty means not configured. */
    fun mask(value: String?): String {
        val v = value.orEmpty()
        if (v.isBlank()) return ""
        if (v.length <= 8) return "***"
        return v.take(4) + "••••" + v.takeLast(2)
    }
}
