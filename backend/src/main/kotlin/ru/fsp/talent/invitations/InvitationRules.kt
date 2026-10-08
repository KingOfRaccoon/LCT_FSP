package ru.fsp.talent.invitations

import ru.fsp.talent.core.ApiException
import java.time.Duration
import java.time.Instant

internal object InvitationRules {
    fun validateSalary(salary: Map<String, Any?>, need: Map<String, Any?>?) {
        val min = (salary["min_kopecks"] as? Number)?.toLong() ?: 0
        val max = (salary["max_kopecks"] as? Number)?.toLong() ?: 0
        val hours = (salary["hours_per_week"] as? Number)?.toInt() ?: 0
        val workload = (need?.get("workload_hours_per_week") as? Number)?.toInt()
        val workloadType = salary["workload_type"]?.toString()
        if (min <= 0 || max < min || hours !in 1..40 || (workload != null && hours != workload) ||
            (workloadType == "full_time" && hours != 40) || (workloadType == "part_time" && hours !in 1..39)
        ) {
            throw ApiException(400, "VALIDATION_ERROR", "Salary range and workload are invalid", "salary")
        }
    }

    fun ensureCooldown(terminalAt: Instant, now: Instant) {
        val eligibleAt = terminalAt.plus(COOLDOWN)
        if (now.isBefore(eligibleAt)) {
            throw ApiException(
                409,
                "INVITATION_COOLDOWN",
                "A new invitation to this candidate is not available yet",
                eligibleAt = eligibleAt,
            )
        }
    }

    fun ensurePairCanTransition(state: String, expectedVersion: Int, currentVersion: Int) {
        if (expectedVersion != currentVersion) {
            throw ApiException(409, "VERSION_CONFLICT", "Invitation has changed", currentVersion = currentVersion)
        }
        if (state != "pending") throw ApiException(409, "INVALID_STATE", "Invitation is no longer pending")
    }

    private val COOLDOWN: Duration = Duration.ofDays(7)
}
