package ru.fsp.talent.identity

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.SessionSupport
import ru.fsp.talent.core.Store
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class AuthReplay(
    private val store: Store,
    private val tx: TransactionTemplate,
    private val sessions: SessionSupport,
    private val mapper: ObjectMapper,
    @Value("\${fsp.session-secret}") private val sessionSecret: String,
) {
    data class Outcome(val body: Map<String, Any?>, val status: Int)
    data class Created(val data: Map<String, Any?>, val sessionId: String)

    fun scope(operation: String, subject: String): String = hmac("auth-replay:$operation:$subject")

    fun find(
        request: HttpServletRequest,
        scope: String,
        body: Map<String, Any?>,
        response: HttpServletResponse? = null,
        protectBodyHash: Boolean = true,
    ): Outcome? = replay(request, scope, body, response, protectBodyHash)

    fun execute(
        request: HttpServletRequest,
        response: HttpServletResponse,
        scope: String,
        body: Map<String, Any?>,
        status: Int,
        create: () -> Created,
    ): Outcome {
        replay(request, scope, body, response, protectBodyHash = true)?.let { return it }
        return tx.execute<Outcome> {
            val key = idempotencyKey(request)
            val operation = operation(request)
            val hash = requestHash(body, protectBodyHash = true)
            val inserted = store.one(
                """INSERT INTO idempotency_records(id, scope, operation, key, request_hash, response, status_code)
                   VALUES (:id, :scope, :operation, :key, :hash, '{}'::jsonb, :status)
                   ON CONFLICT (scope, operation, key) DO NOTHING RETURNING id""".trimIndent(),
                mapOf("id" to store.id(), "scope" to scope, "operation" to operation, "key" to key,
                    "hash" to hash, "status" to status),
            )
            if (inserted == null) {
                val prior = prior(scope, operation, key)
                return@execute replayOutcome(prior, hash, response)
            }
            val created = create()
            val internal = linkedMapOf<String, Any?>("data" to created.data, INTERNAL_SESSION_ID to created.sessionId)
            store.exec(
                "UPDATE idempotency_records SET response = :response WHERE id = :id",
                mapOf("response" to store.json(internal), "id" to inserted.getValue("id")),
            )
            Outcome(mapOf("data" to created.data), status)
        } ?: throw IllegalStateException("Authentication replay transaction returned no result")
    }

    private fun replay(
        request: HttpServletRequest,
        scope: String,
        body: Map<String, Any?>,
        response: HttpServletResponse?,
        protectBodyHash: Boolean,
    ): Outcome? {
        val record = store.one(
            "SELECT request_hash, response, status_code FROM idempotency_records WHERE scope=:scope AND operation=:operation AND key=:key",
            mapOf("scope" to scope, "operation" to operation(request), "key" to idempotencyKey(request)),
        ) ?: return null
        return replayOutcome(record, requestHash(body, protectBodyHash), response)
    }

    private fun replayOutcome(record: Map<String, Any?>, expectedHash: String, response: HttpServletResponse?): Outcome {
        if (record.getValue("request_hash").toString().trim() != expectedHash) {
            throw ApiException(409, "IDEMPOTENCY_CONFLICT", "This key was used with a different request")
        }
        val stored = mapValue(record["response"])
        val sessionId = stored[INTERNAL_SESSION_ID]?.toString()
        if (sessionId != null) sessions.restore(sessionId, requireNotNull(response))
        val body = stored.filterKeys { it != INTERNAL_SESSION_ID }
        return Outcome(body, (record.getValue("status_code") as Number).toInt())
    }

    private fun prior(scope: String, operation: String, key: String): Map<String, Any?> = store.one(
        "SELECT request_hash, response, status_code FROM idempotency_records WHERE scope=:scope AND operation=:operation AND key=:key FOR UPDATE",
        mapOf("scope" to scope, "operation" to operation, "key" to key),
    ) ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)

    private fun idempotencyKey(request: HttpServletRequest): String = request.getHeader("Idempotency-Key")?.trim()
        ?.takeIf { it.length in 16..128 }
        ?: throw ApiException(400, "VALIDATION_ERROR", "A valid Idempotency-Key is required", "Idempotency-Key")

    private fun operation(request: HttpServletRequest) = "${request.method} ${request.requestURI}"

    private fun requestHash(body: Map<String, Any?>, protectBodyHash: Boolean): String {
        val canonicalBody = mapper.writeValueAsString(canonical(body))
        return if (protectBodyHash) hmac("auth-request:$canonicalBody") else sha256(canonicalBody)
    }

    private fun canonical(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { it.key.toString() to canonical(it.value) }.toSortedMap()
        is Iterable<*> -> value.map(::canonical)
        else -> value
    }

    private fun mapValue(value: Any?): Map<String, Any?> = when (value) {
        is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
        else -> mapper.convertValue(value, Map::class.java).entries.associate { it.key.toString() to it.value }
    }

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val INTERNAL_SESSION_ID = "_auth_session_id"
    }
}
