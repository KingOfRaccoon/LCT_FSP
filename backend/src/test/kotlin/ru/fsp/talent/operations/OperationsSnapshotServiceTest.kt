package ru.fsp.talent.operations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import ru.fsp.talent.core.Store
import java.time.Instant

class OperationsSnapshotServiceTest {
    @Test
    fun `database failure keeps queue gauges unknown instead of returning zero`() {
        val store = Mockito.mock(Store::class.java)
        Mockito.`when`(store.now()).thenReturn(Instant.parse("2026-10-10T12:00:00Z"))
        Mockito.`when`(store.one("SELECT 1 AS available")).thenThrow(IllegalStateException("database unavailable"))

        val snapshot = OperationsSnapshotService(store, OperationalMetrics(), outboxEnabled = false).snapshot()
        val database = snapshot.getValue("database") as Map<*, *>
        val outbox = snapshot.getValue("outbox") as Map<*, *>

        assertFalse(database["available"] as Boolean)
        assertNull(outbox["counts"])
        assertNull(outbox["oldest_due_age_seconds"])
        assertNull(outbox["worker_last_tick_at"])
        val upstream = snapshot.getValue("upstream") as Map<*, *>
        assertNull(upstream["smtp"])
    }
}
