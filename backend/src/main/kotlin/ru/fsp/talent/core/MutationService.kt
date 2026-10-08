package ru.fsp.talent.core

import com.fasterxml.jackson.databind.ObjectMapper
import org.postgresql.util.PGobject
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.TreeMap

@Component
class MutationService(private val store: Store, private val mapper: ObjectMapper) {
    fun run(
        request: HttpServletRequest,
        scope: String,
        body: Map<String, Any?>,
        status: Int = 200,
        action: () -> Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val key = request.getHeader("Idempotency-Key")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ApiException(400, "VALIDATION_ERROR", "Idempotency-Key is required", "Idempotency-Key")
        if (key.length !in 16..128) {
            throw ApiException(400, "VALIDATION_ERROR", "Idempotency-Key must contain 16 to 128 characters", "Idempotency-Key")
        }
        val operation = "${request.method} ${request.requestURI}"
        val requestHash = sha256(mapper.writeValueAsString(canonical(body)))
        return store.tx.execute<ResponseEntity<Map<String, Any?>>> {
            val id = store.id()
            val inserted = store.one(
                """INSERT INTO idempotency_records(id, scope, operation, key, request_hash, response, status_code)
                   VALUES (:id, :scope, :operation, :key, :hash, '{}'::jsonb, :status)
                   ON CONFLICT (scope, operation, key) DO NOTHING RETURNING id""".trimIndent(),
                mapOf("id" to id, "scope" to scope, "operation" to operation, "key" to key,
                    "hash" to requestHash, "status" to status),
            )
            if (inserted == null) {
                val prior = store.one(
                    """SELECT request_hash, response, status_code FROM idempotency_records
                       WHERE scope = :scope AND operation = :operation AND key = :key FOR UPDATE""".trimIndent(),
                    mapOf("scope" to scope, "operation" to operation, "key" to key),
                ) ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)
                if (prior["request_hash"].toString().trim() != requestHash) {
                    throw ApiException(409, "IDEMPOTENCY_CONFLICT", "This key was used with a different request")
                }
                val replay: Map<String, Any?> = when (val value = prior["response"]) {
                    is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
                    is PGobject -> mapper.readValue(value.value, Map::class.java)
                        .entries.associate { it.key.toString() to it.value }
                    else -> mapper.convertValue(value, Map::class.java).entries.associate { it.key.toString() to it.value }
                }
                return@execute ResponseEntity.status((prior["status_code"] as Number).toInt()).body(replay)
            }
            val envelope = mapOf("data" to action())
            store.exec(
                "UPDATE idempotency_records SET response = :response WHERE id = :id",
                mapOf("response" to store.json(envelope), "id" to id),
            )
            ResponseEntity.status(status).body(envelope)
        } ?: throw IllegalStateException("Mutation transaction returned no response")
    }

    private fun canonical(value: Any?): Any? = when (value) {
        is Map<*, *> -> TreeMap<String, Any?>().apply {
            value.forEach { (key, child) -> put(key.toString(), canonical(child)) }
        }
        is Iterable<*> -> value.map(::canonical)
        else -> value
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
