package ru.fsp.talent.invitations

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import ru.fsp.talent.core.ApiException

class InvitationRulesTest {
    @Test
    fun `salary must match need workload and positive ordered range`() {
        val need = mapOf("workload_hours_per_week" to 32)
        val salary = mapOf(
            "min_kopecks" to 100_000,
            "max_kopecks" to 200_000,
            "currency" to "RUB",
            "period" to "month",
            "basis" to "gross",
            "workload_type" to "part_time",
            "hours_per_week" to 40,
        )

        assertThrows(ApiException::class.java) { InvitationRules.validateSalary(salary, need) }
    }

    @Test
    fun `retry after decline is blocked until seven day cooldown ends`() {
        val terminalAt = java.time.Instant.parse("2026-10-01T10:00:00Z")

        assertThrows(ApiException::class.java) {
            InvitationRules.ensureCooldown(terminalAt, java.time.Instant.parse("2026-10-08T09:59:59Z"))
        }
    }
}
