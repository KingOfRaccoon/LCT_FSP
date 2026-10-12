package ru.fsp.talent.operations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class OperationsSummaryIntegrationTest : DbTestBase() {
    @Test
    fun `summary reflects empty baseline and bounded outbox state without exposing row data`() {
        val operator = seedActor()
        jdbc.update(
            "INSERT INTO operations_grants(user_id, permission, granted_by) VALUES (?::uuid, 'monitor_read', ?::uuid)",
            operator.id, operator.id,
        )
        val pendingId = UUID.randomUUID().toString()
        val failedId = UUID.randomUUID().toString()
        val privatePayload = "private-${UUID.randomUUID()}@example.test"
        val priorHeartbeat = jdbc.query("SELECT last_tick_at FROM operations_worker_heartbeat WHERE worker_key='outbox'") { rs, _ ->
            rs.getTimestamp("last_tick_at")?.toInstant()
        }.firstOrNull()
        val testHeartbeat = Instant.now().minusSeconds(90).truncatedTo(ChronoUnit.MICROS)
        jdbc.update(
            "INSERT INTO operations_worker_heartbeat(worker_key,last_tick_at) VALUES ('outbox',?) " +
                "ON CONFLICT (worker_key) DO UPDATE SET last_tick_at=EXCLUDED.last_tick_at",
            Timestamp.from(testHeartbeat),
        )

        try {
            val before = request(operator, HttpMethod.GET, "/api/v1/operations/summary")
            assertEquals(200, before.statusCode.value())
            assertEquals(expectedDatabaseCounts(), before.body!!.path("data").path("outbox").path("counts").let { counts ->
                mapOf("pending" to counts.path("pending").asLong(), "delivered" to counts.path("delivered").asLong(),
                    "failed" to counts.path("failed").asLong(), "discarded" to counts.path("discarded").asLong())
            })
            assertEquals(testHeartbeat, Instant.parse(before.body!!.path("data").path("outbox").path("worker_last_tick_at").asText()))

            jdbc.update(
                """INSERT INTO outbox_events(id, type, payload, state, attempts, created_at, next_attempt_at)
                   VALUES (?::uuid, 'test', ?::jsonb, 'pending', 0, now() - interval '2 minutes', now() - interval '1 minute')""",
                pendingId, objectMapper.writeValueAsString(mapOf("email" to privatePayload, "secret" to "never expose")),
            )
            jdbc.update(
                """INSERT INTO outbox_events(id, type, payload, state, attempts)
                   VALUES (?::uuid, 'test', ?::jsonb, 'failed', 5)""",
                failedId, objectMapper.writeValueAsString(mapOf("email" to privatePayload)),
            )

            val result = request(operator, HttpMethod.GET, "/api/v1/operations/summary")
            assertEquals(200, result.statusCode.value())
            val data = result.body!!.path("data")
            assertEquals(expectedDatabaseCounts(), data.path("outbox").path("counts").let { counts ->
                mapOf("pending" to counts.path("pending").asLong(), "delivered" to counts.path("delivered").asLong(),
                    "failed" to counts.path("failed").asLong(), "discarded" to counts.path("discarded").asLong())
            })
            assertTrue(data.path("outbox").path("oldest_due_age_seconds").asLong() >= 100)
            assertTrue(data.path("database").path("available").asBoolean())
            assertFalse(result.body.toString().contains(pendingId))
            assertFalse(result.body.toString().contains(failedId))
            assertFalse(result.body.toString().contains(privatePayload))
            assertFalse(result.body.toString().contains("never expose"))
        } finally {
            jdbc.update("DELETE FROM outbox_events WHERE id IN (?::uuid, ?::uuid)", pendingId, failedId)
            if (priorHeartbeat == null) {
                jdbc.update("DELETE FROM operations_worker_heartbeat WHERE worker_key='outbox'")
            } else {
                jdbc.update(
                    "UPDATE operations_worker_heartbeat SET last_tick_at=? WHERE worker_key='outbox'",
                    Timestamp.from(priorHeartbeat),
                )
            }
        }
    }

    private fun expectedDatabaseCounts(): Map<String, Long> = listOf("pending", "delivered", "failed", "discarded").associateWith { state ->
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE state=?", Long::class.java, state) ?: 0L
    }
}
