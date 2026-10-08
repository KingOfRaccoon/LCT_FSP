package ru.fsp.talent.privacy

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Bounded redaction for direct contact strings in candidate-authored public text. */
object PublicTextRedactor {
    private const val REPLACEMENT = "[контакт скрыт]"
    private val uriContact = Regex("(?i)\\b(?:mailto|tel):[^\\s<>\\\"']+")
    private val telPhone = Regex("(?i)\\btel:\\+?[\\d\\s().-]{8,}\\d")
    private val email = Regex("(?iu)(?<![\\p{L}\\p{N}._%+\\-])[\\p{L}\\p{N}._%+\\-]+@[\\p{L}\\p{N}](?:[\\p{L}\\p{N}.\\-]*[\\p{L}\\p{N}])?\\.[\\p{L}]{2,}(?![\\p{L}\\p{N}_\\-])")
    private val phoneCandidate = Regex("(?<![\\p{L}\\p{N}])(?:\\+[\\d\\s().-]{8,}\\d|[78](?:[\\s().-]*\\d){10})(?![\\p{L}\\p{N}])")

    fun redact(text: String?, maxCodePoints: Int? = null): String? {
        require(maxCodePoints == null || maxCodePoints >= 0)
        val redacted = text?.replace(telPhone, REPLACEMENT)
            ?.replace(uriContact, REPLACEMENT)
            ?.replace(email, REPLACEMENT)
            ?.replace(phoneCandidate) { match ->
                val digits = match.value.filter(Char::isDigit)
                val explicitInternational = match.value.trimStart().startsWith('+')
                if ((explicitInternational && digits.length in 10..15) ||
                    (!explicitInternational && digits.length == 11 && digits.firstOrNull() in setOf('7', '8') && !isClearNumericRange(match.value))
                ) {
                    REPLACEMENT
                } else match.value
            }
        return if (redacted == null || maxCodePoints == null) redacted else truncate(redacted, maxCodePoints)
    }

    /** Keeps only a safe HTTPS URL; checks decoded text too, without resolving or fetching it. */
    fun publicUrl(value: Any?): String? {
        val text = value as? String ?: return null
        if (text.codePointCount(0, text.length) > 2048) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        val decoded = runCatching { URLDecoder.decode(text, StandardCharsets.UTF_8) }.getOrDefault(text)
        if (containsDirectContact(text) || containsDirectContact(decoded)) return null
        return text
    }

    private fun containsDirectContact(value: String): Boolean {
        val redacted = redact(value)
        return redacted != value
    }

    private fun isClearNumericRange(value: String): Boolean {
        val parts = value.split(Regex("\\s*[\\-–—]\\s*"))
        if (parts.size != 2) return false
        val digitCounts = parts.map { part -> part.count(Char::isDigit) }
        return digitCounts.all { it >= 4 }
    }

    private fun truncate(value: String, maxCodePoints: Int): String {
        val codePoints = value.codePoints().toArray()
        if (codePoints.size <= maxCodePoints) return value
        var clipped = String(codePoints, 0, maxCodePoints)
        val marker = REPLACEMENT.codePoints().toArray()
        if (maxCodePoints >= marker.size && markerCrossesLimit(codePoints, maxCodePoints, marker)) {
            clipped = String(codePoints, 0, maxCodePoints - marker.size) + REPLACEMENT
        }
        return clipped
    }

    private fun markerCrossesLimit(codePoints: IntArray, limit: Int, marker: IntArray): Boolean {
        val firstPossibleStart = (limit - marker.size + 1).coerceAtLeast(0)
        for (start in firstPossibleStart until limit) {
            if (start + marker.size <= codePoints.size &&
                (0 until marker.size).all { offset -> codePoints[start + offset] == marker[offset] }
            ) return true
        }
        return false
    }
}
