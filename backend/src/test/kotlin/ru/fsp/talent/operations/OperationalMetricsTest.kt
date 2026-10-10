package ru.fsp.talent.operations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OperationalMetricsTest {
    @Test
    fun `prometheus family metadata is emitted once for all fixed outbox state gauges`() {
        val metrics = OperationalMetrics()
        metrics.recordHttp("GET", "/api/v1/operations/summary", 200, 1_000_000)
        val body = metrics.renderPrometheus(
            mapOf(
                "database" to mapOf("available" to true),
                "outbox" to mapOf(
                    "worker_enabled" to false,
                    "worker_last_tick_at" to null,
                    "counts" to mapOf("pending" to 1, "delivered" to 2, "failed" to 3, "discarded" to 4),
                    "oldest_due_age_seconds" to 5,
                ),
            ),
        )

        assertEquals(1, body.lines().count { it == "# HELP fsp_outbox_events Outbox event rows by state." })
        assertEquals(1, body.lines().count { it == "# TYPE fsp_outbox_events gauge" })
        listOf("pending", "delivered", "failed", "discarded").forEach { state ->
            assertTrue(body.contains("fsp_outbox_events{state=\"$state\"}"))
        }
        assertTrue(body.contains("fsp_upstream_calls_total{provider=\"smtp\",outcome=\"success\"} NaN"))
        assertTrue(body.contains("fsp_outbox_delivery_attempts_total{outcome=\"delivered\"} NaN"))
        assertTrue(body.contains("fsp_http_request_duration_seconds_bucket{method=\"GET\",route=\"/api/v1/operations/summary\",le=\"+Inf\"}"))
    }
}
