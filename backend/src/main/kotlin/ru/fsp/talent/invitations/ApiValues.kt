package ru.fsp.talent.invitations

import com.fasterxml.jackson.core.type.TypeReference
import org.postgresql.util.PGobject
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.Store
import java.time.Instant
import java.util.Base64

internal fun Store.objectValue(value: Any?): Map<String, Any?> = when (value) {
    is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
    is PGobject -> mapper.readValue(value.value, object : TypeReference<Map<String, Any?>>() {})
    is String -> mapper.readValue(value, object : TypeReference<Map<String, Any?>>() {})
    null -> emptyMap()
    else -> mapper.convertValue(value, object : TypeReference<Map<String, Any?>>() {})
}

internal fun Store.listValue(value: Any?): List<Map<String, Any?>> = when (value) {
    is Collection<*> -> value.mapNotNull { item -> item as? Map<*, *> }
        .map { item -> item.entries.associate { it.key.toString() to it.value } }
    is PGobject -> mapper.readValue(value.value, object : TypeReference<List<Map<String, Any?>>>() {})
    is String -> mapper.readValue(value, object : TypeReference<List<Map<String, Any?>>>() {})
    null -> emptyList()
    else -> mapper.convertValue(value, object : TypeReference<List<Map<String, Any?>>>() {})
}

internal fun Any?.asInstant(): Instant? = when (this) {
    is Instant -> this
    is java.sql.Timestamp -> toInstant()
    is java.time.OffsetDateTime -> toInstant()
    is java.time.LocalDateTime -> toInstant(java.time.ZoneOffset.UTC)
    is String -> runCatching { Instant.parse(this) }.getOrNull()
    null -> null
    else -> throw IllegalArgumentException("Unexpected timestamp type: ${this.javaClass.name}")
}

internal data class PageCursor(val at: Instant, val id: String) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("${at}|$id".toByteArray(Charsets.UTF_8))

    companion object {
        fun decode(value: String?): PageCursor? {
            if (value.isNullOrBlank()) return null
            return runCatching {
                val parts = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8).split('|', limit = 2)
                require(parts.size == 2)
                PageCursor(Instant.parse(parts[0]), java.util.UUID.fromString(parts[1]).toString())
            }.getOrElse {
                throw ApiException(400, "VALIDATION_ERROR", "Invalid cursor", "cursor")
            }
        }
    }
}

internal fun checkPageLimit(limit: Int): Int {
    if (limit !in 1..100) throw ApiException(400, "VALIDATION_ERROR", "limit must be between 1 and 100", "limit")
    return limit
}

internal fun requireUuid(value: String, field: String): String = runCatching {
    java.util.UUID.fromString(value).toString().also { require(value.equals(it, ignoreCase = true)) }
}.getOrElse {
    throw ApiException(400, "VALIDATION_ERROR", "Must be a UUID", field)
}
