package ru.fsp.talent.matching

import org.springframework.stereotype.Component
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.Store
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class NeedInsightsService(
    private val needs: NeedService,
    private val matching: MatchingService,
    private val store: Store,
) {
    fun get(actor: Actor, needId: String, expectedVersion: Int): Map<String, Any?> {
        val need = needs.get(actor, needId)
        if (need["status"] == "archived") {
            throw ApiException(409, "INVALID_STATE", "Insights are unavailable for archived needs")
        }
        val currentVersion = (need["version"] as? Number)?.toInt() ?: 0
        if (expectedVersion != currentVersion) {
            throw ApiException(409, "VERSION_CONFLICT", "Need has changed", currentVersion = currentVersion)
        }

        val scopedProfiles = matching.insightProfiles().filter { profile ->
            profile["specialization_id"] == need["specialization_id"] &&
                (profile["verified_grade"] == need["target_grade"] ||
                    (profile["verified_grade"] == null && profile["declared_grade"] == need["target_grade"]))
        }
        val baseline = MatchingPolicy.computeMatches(scopedProfiles, need)
        val allRequirements = requirements(need)
        val required = allRequirements.filter { it["importance"] == "required" }
        val testEvidence = scopedProfiles.associate { it.candidateId() to it["test_evidenced_skills"].asStringSet() }
        val declaredSkills = scopedProfiles.associate { it.candidateId() to it["candidate_declared_skills"].asStringSet() }

        val requirements = required.map { requirement ->
            val skillId = requirement.getValue("skill_id").toString()
            val testConfirmed = scopedProfiles.count { skillId in testEvidence[it.candidateId()].orEmpty() }
            val declaredOnly = scopedProfiles.count { skillId in declaredSkills[it.candidateId()].orEmpty() && skillId !in testEvidence[it.candidateId()].orEmpty() }
            val noSignal = scopedProfiles.size - testConfirmed - declaredOnly
            val relaxedNeed = need + ("requirements" to allRequirements.filterNot {
                it["importance"] == "required" && it["skill_id"] == skillId
            })
            val relaxed = MatchingPolicy.computeMatches(scopedProfiles, relaxedNeed)
            val relaxedConfirmedIds = relaxed.groups.confirmed.mapTo(mutableSetOf()) { it.candidateId }
            val baselineConfirmedIds = baseline.groups.confirmed.mapTo(mutableSetOf()) { it.candidateId }
            mapOf(
                "skill_id" to skillId,
                "importance" to requirement["importance"],
                "test_confirmed_count" to testConfirmed,
                "declared_only_count" to declaredOnly,
                "no_skill_signal_count" to noSignal,
                "additional_confirmed_matches_if_relaxed" to (relaxedConfirmedIds - baselineConfirmedIds).size,
            )
        }

        val salary = salarySummary(scopedProfiles, need)
        return mapOf(
            "need_id" to need["id"],
            "need_version" to currentVersion,
            "generated_at" to store.now().toString(),
            "policy_version" to MatchingPolicy.POLICY_VERSION,
            "scope" to "current_visible_platform_profiles",
            "counts" to mapOf(
                "profile_pool" to scopedProfiles.size,
                "confirmed" to baseline.groupCounts.confirmed,
                "needs_clarification" to baseline.groupCounts.needsClarification,
                "needs_confirmation" to baseline.groupCounts.needsConfirmation,
                "excluded" to baseline.excludedCandidateIds.size,
                "demo" to scopedProfiles.count { it["demo"] == true },
            ),
            "mandatory_requirements" to mapOf(
                "unknown_test_evidence_pairs" to required.sumOf { requirement ->
                    val skill = requirement["skill_id"].toString()
                    scopedProfiles.count { skill !in testEvidence[it.candidateId()].orEmpty() }
                },
                "blocked_by_matching_policy_count" to baseline.excludedCandidateIds.size,
            ),
            "requirements" to requirements,
            "salary" to salary,
        )
    }

    private fun salarySummary(cohort: List<Map<String, Any?>>, need: Map<String, Any?>): Map<String, Any?> {
        val needSalary = need["salary"].asStringMap()
        val needHours = need["workload_hours_per_week"].asInt()
        val knownRanges = cohort.mapNotNull { profile ->
            val profileData = profile["profile_data"].asStringMap()
            val raw = profileData["expected_salary"]
            if (raw == null) return@mapNotNull null
            val salary = raw.asStringMap()
            val minimum = salary["min_kopecks"].asLong()
            val maximum = salary["max_kopecks"].asLong()
            if (minimum == null || maximum == null || minimum <= 0 || maximum < minimum) {
                SalaryObservation(compatible = false, minimum = null, maximum = null)
            } else {
                val compatible = salary["currency"] == "RUB" && salary["period"] == "month" &&
                    salary["basis"] == needSalary["basis"] && salary["workload_type"] == needSalary["workload_type"] &&
                    salary["hours_per_week"].asInt() == needHours
                SalaryObservation(
                    compatible = compatible,
                    minimum = minimum.takeIf { compatible },
                    maximum = maximum.takeIf { compatible },
                )
            }
        }
        val comparable = knownRanges.filter { it.compatible }
        val summaryAvailable = cohort.size >= MIN_MEDIAN_SAMPLE && comparable.size >= MIN_MEDIAN_SAMPLE
        val lowSample = !summaryAvailable
        return mapOf(
            "scope" to "fixed_visible_specialization_grade_cohort",
            "summary_available" to summaryAvailable,
            "known_count" to privacyCount(knownRanges.size).takeIf { summaryAvailable },
            "compatible_count" to comparable.size.takeIf { summaryAvailable },
            "unknown_count" to privacyCount(cohort.size - knownRanges.size).takeIf { summaryAvailable },
            "incompatible_count" to privacyCount(knownRanges.count { !it.compatible }).takeIf { summaryAvailable },
            "within_range_count" to null,
            "range_count_available" to false,
            "range_count_suppression_reason" to "privacy_protected",
            "median_min_kopecks" to if (summaryAvailable) coarseMedian(comparable.mapNotNull { it.minimum }) else null,
            "median_max_kopecks" to if (summaryAvailable) coarseMedian(comparable.mapNotNull { it.maximum }) else null,
            "low_sample" to lowSample,
        )
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else
            BigDecimal.valueOf(sorted[middle - 1]).add(BigDecimal.valueOf(sorted[middle]))
                .divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP).longValueExact()
    }

    private fun coarseMedian(values: List<Long>): Long {
        val binSize = MEDIAN_BIN_KOPECKS
        return BigDecimal.valueOf(median(values))
            .divide(BigDecimal.valueOf(binSize), 0, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(binSize))
            .longValueExact()
    }

    private fun privacyCount(count: Int): Int? = count.takeIf { it >= MIN_MEDIAN_SAMPLE }

    private fun requirements(need: Map<String, Any?>): List<Map<String, Any?>> =
        (need["requirements"] as? Iterable<*>)?.mapNotNull { it.asStringMap() }.orEmpty()

    private data class SalaryObservation(
        val compatible: Boolean,
        val minimum: Long?,
        val maximum: Long?,
    )

    private fun Map<String, Any?>.candidateId(): String = (this["id"] ?: this["user_id"]).toString()

    private fun Any?.asStringMap(): Map<String, Any?> = (this as? Map<*, *>)?.entries
        ?.associate { it.key.toString() to it.value }.orEmpty()

    private fun Any?.asStringSet(): Set<String> = (this as? Iterable<*>)?.mapNotNull { it?.toString() }?.toSet().orEmpty()

    private fun Any?.asLong(): Long? = (this as? Number)?.toLong()

    private fun Any?.asInt(): Int? = (this as? Number)?.toInt()

    companion object {
        private const val MIN_MEDIAN_SAMPLE = 5
        private const val MEDIAN_BIN_KOPECKS = 1_000_000L
    }
}
