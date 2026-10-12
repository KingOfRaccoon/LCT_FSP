package ru.fsp.talent.operations

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.fsp.talent.core.Store
import java.lang.management.ManagementFactory
import java.time.Instant

@Service
class OperationsSnapshotService(
    private val store: Store,
    private val metrics: OperationalMetrics,
    @Value("\${fsp.outbox-enabled:false}") private val outboxEnabled: Boolean,
) {
    fun snapshot(): Map<String, Any?> {
        val now = store.now()
        val databaseSnapshot = readDatabaseSnapshot()
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val http = metrics.httpSummary()
        return linkedMapOf(
            "generated_at" to now,
            "process" to mapOf(
                "uptime_seconds" to ManagementFactory.getRuntimeMXBean().uptime / 1000,
                "heap_used_bytes" to heap.used,
                "heap_max_bytes" to heap.max.takeIf { it >= 0 },
            ),
            "database" to mapOf("available" to databaseSnapshot.available),
            "http" to mapOf(
                "requests_total" to http.requestsTotal,
                "server_errors_total" to http.serverErrorsTotal,
                "average_duration_seconds" to http.averageDurationSeconds,
            ),
            "outbox" to mapOf(
                "worker_enabled" to outboxEnabled,
                "worker_last_tick_at" to databaseSnapshot.lastTickAt,
                "counts" to databaseSnapshot.counts,
                "oldest_due_age_seconds" to databaseSnapshot.oldestDueAgeSeconds,
            ),
            "upstream" to metrics.upstreamSummary(outboxEnabled),
        )
    }

    private fun readDatabaseSnapshot(): DatabaseSnapshot = try {
        store.one("SELECT 1 AS available")
        val counts = mutableMapOf("pending" to 0L, "delivered" to 0L, "failed" to 0L, "discarded" to 0L)
        store.rows(
            "SELECT state, count(*) AS total FROM outbox_events " +
                "WHERE state IN ('pending','delivered','failed','discarded') GROUP BY state",
        ).forEach { row -> counts[row.getValue("state").toString()] = (row.getValue("total") as Number).toLong() }
        val oldestDueAge = store.one(
            "SELECT floor(extract(epoch FROM (now() - min(created_at))))::bigint AS age " +
                "FROM outbox_events WHERE state='pending' AND next_attempt_at <= now()",
        )?.get("age")?.let { (it as? Number)?.toLong()?.coerceAtLeast(0) }
        val lastTick = store.one(
            "SELECT last_tick_at FROM operations_worker_heartbeat WHERE worker_key='outbox'",
        )?.get("last_tick_at")?.let(::instantOrNull)
        DatabaseSnapshot(available = true, counts = counts, oldestDueAgeSeconds = oldestDueAge, lastTickAt = lastTick)
    } catch (_: Exception) {
        DatabaseSnapshot(available = false, counts = null, oldestDueAgeSeconds = null, lastTickAt = null)
    }

    private fun instantOrNull(value: Any?): Instant? = when (value) {
        is Instant -> value
        is java.sql.Timestamp -> value.toInstant()
        else -> value?.toString()?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }

    private data class DatabaseSnapshot(
        val available: Boolean,
        val counts: Map<String, Long>?,
        val oldestDueAgeSeconds: Long?,
        val lastTickAt: Instant?,
    )
}
