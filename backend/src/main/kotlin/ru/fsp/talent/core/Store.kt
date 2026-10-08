package ru.fsp.talent.core

import com.fasterxml.jackson.databind.ObjectMapper
import org.postgresql.util.PGobject
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.Clock
import java.time.OffsetDateTime
import java.sql.Timestamp
import java.util.UUID
import java.util.regex.Pattern

@Component
class Store(
    val jdbc: NamedParameterJdbcTemplate,
    val tx: TransactionTemplate,
    val mapper: ObjectMapper,
    private val clock: Clock,
) {
    fun one(sql: String, params: Map<String, *> = emptyMap<String, Any?>()): Map<String, Any?>? =
        jdbc.query(sql, bindParams(params)) { rs, _ ->
            val metadata = rs.metaData
            buildMap<String, Any?> {
                for (column in 1..metadata.columnCount) {
                    put(metadata.getColumnLabel(column), decode(rs.getObject(column)))
                }
            }
        }.firstOrNull()

    fun rows(sql: String, params: Map<String, *> = emptyMap<String, Any?>()): List<Map<String, Any?>> =
        jdbc.query(sql, bindParams(params)) { rs, _ ->
            val metadata = rs.metaData
            buildMap<String, Any?> {
                for (column in 1..metadata.columnCount) {
                    put(metadata.getColumnLabel(column), decode(rs.getObject(column)))
                }
            }
        }

    fun exec(sql: String, params: Map<String, *> = emptyMap<String, Any?>()): Int = jdbc.update(sql, bindParams(params))

    fun now(): Instant = clock.instant()

    fun id(): String = UUID.randomUUID().toString()

    fun json(value: Any?): PGobject = PGobject().also {
        it.type = "jsonb"
        it.value = mapper.writeValueAsString(value)
    }

    private fun decode(value: Any?): Any? = when (value) {
        is PGobject -> when (value.type) {
            "json", "jsonb" -> mapper.readValue(value.value, Any::class.java)
            else -> value.value
        }
        is UUID -> value.toString()
        is Timestamp -> value.toInstant()
        is OffsetDateTime -> value.toInstant()
        else -> value
    }

    private fun bindParams(params: Map<String, *>): Map<String, Any?> = params.mapValues { (name, value) ->
        val uuidParameter = isUuidParameter(name)
        when (value) {
            is String -> if (uuidParameter && UUID_PATTERN.matcher(value).matches()) UUID.fromString(value) else value
            is Collection<*> -> value.map { item ->
                if (uuidParameter && item is String && UUID_PATTERN.matcher(item).matches()) UUID.fromString(item) else item
            }
            is Instant -> Timestamp.from(value)
            is OffsetDateTime -> Timestamp.from(value.toInstant())
            else -> value
        }
    }

    private fun isUuidParameter(name: String): Boolean {
        val snakeName = Regex("([a-z0-9])([A-Z])")
            .replace(name) { "${it.groupValues[1]}_${it.groupValues[2]}" }
            .lowercase()
        if (snakeName in NON_UUID_PARAMETERS) return false
        return snakeName in setOf("id", "ids") || snakeName.endsWith("_id") || snakeName.endsWith("_ids") ||
            snakeName in UUID_PARAMETER_ALIASES
    }

    companion object {
        private val UUID_PATTERN = Pattern.compile("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val NON_UUID_PARAMETERS = setOf(
            "scope", "key", "operation", "cursor", "external_id", "source_record_id",
            "family_id", "variant_id", "source_id",
        )
        private val UUID_PARAMETER_ALIASES = setOf(
            "user", "actor", "owner", "member", "company", "candidate", "profile", "need",
            "invitation", "attempt", "session", "grant", "qualification", "shortlist", "link",
            "entity", "record", "task",
        )
    }
}
