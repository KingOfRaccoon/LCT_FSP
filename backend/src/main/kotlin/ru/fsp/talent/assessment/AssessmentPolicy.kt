package ru.fsp.talent.assessment

import java.time.Duration
import java.time.Instant

internal const val ASSESSMENT_POLICY_ID = "backend-python-policy-v1"
internal const val ASSESSMENT_POLICY_VERSION = "1.0.0"
internal const val ASSESSMENT_BANK_VERSION = "backend-python-v1.0"
internal const val ASSESSMENT_SPECIALIZATION = "backend-python-v1"

internal val ASSESSMENT_COMPETENCIES = listOf(
    "python-basics",
    "http-api",
    "sql-transactions",
    "reliability",
)

internal data class AssessmentScore(
    val total: Int,
    val byCompetency: Map<String, Int>,
    val passed: Boolean,
    val confident: Boolean,
)

internal data class AssessmentQuestionKey(
    val taskId: String,
    val competencyId: String,
    val subquestionId: String,
    val correctOptionId: String,
)

internal data class GradeTransition(
    val confirmedGrade: String?,
    val lastSuccessfulGradeChangeAt: Instant?,
    val refreshesEvidence: Boolean,
)

internal data class GradeEligibility(
    val allowed: Boolean,
    val eligibleAt: Instant?,
    val reason: String?,
    val activeAttemptId: String? = null,
)

internal object AssessmentScoring {
    fun score(correctByCompetency: Map<String, Int>): AssessmentScore {
        require(correctByCompetency.keys == ASSESSMENT_COMPETENCIES.toSet()) {
            "Scores must contain exactly the assessment competencies"
        }
        require(correctByCompetency.values.all { it in 0..6 }) {
            "Each competency score must be between zero and six"
        }

        val scores = ASSESSMENT_COMPETENCIES.associateWith { correctByCompetency.getValue(it) }
        val total = scores.values.sum()
        val passed = total >= 18 && scores.values.all { it >= 3 }
        val confident = passed && total >= 22 && scores.values.all { it >= 4 }
        return AssessmentScore(total, scores, passed, confident)
    }

    fun score(questionKeys: List<AssessmentQuestionKey>, answers: List<Map<String, Any?>>): AssessmentScore {
        require(questionKeys.size == 24) { "An assessment form must contain 24 subquestions" }
        val keysByPair = questionKeys.associateBy { it.taskId to it.subquestionId }
        require(keysByPair.size == questionKeys.size) { "Assessment subquestion IDs must be unique" }
        val correctCounts = ASSESSMENT_COMPETENCIES.associateWith { competency ->
            questionKeys.count { key ->
                key.competencyId == competency && answers.any { answer ->
                    answer["task_id"]?.toString() == key.taskId &&
                        answer["subquestion_id"]?.toString() == key.subquestionId &&
                        answer["selected_option_id"]?.toString() == key.correctOptionId
                }
            }
        }
        return score(correctCounts)
    }
}

internal object AssessmentPolicy {
    private val retakeInterval = Duration.ofHours(24)
    private val gradeChangeInterval = Duration.ofDays(30)

    fun transition(
        confirmedGrade: String?,
        targetGrade: String,
        passed: Boolean,
        evaluatedAt: Instant,
        lastSuccessfulGradeChangeAt: Instant?,
    ): GradeTransition {
        require(targetGrade in setOf("junior", "middle")) { "Unsupported assessment grade" }
        require(confirmedGrade == null || confirmedGrade in setOf("junior", "middle")) {
            "Unsupported confirmed grade"
        }

        if (confirmedGrade == "middle" && targetGrade == "junior") {
            return GradeTransition(confirmedGrade, lastSuccessfulGradeChangeAt, refreshesEvidence = false)
        }
        if (!passed) return GradeTransition(confirmedGrade, lastSuccessfulGradeChangeAt, refreshesEvidence = false)
        val gradeChanged = confirmedGrade != null && confirmedGrade != targetGrade
        return GradeTransition(
            confirmedGrade = targetGrade,
            lastSuccessfulGradeChangeAt = if (gradeChanged) evaluatedAt else lastSuccessfulGradeChangeAt,
            refreshesEvidence = true,
        )
    }

    fun eligibility(
        targetGrade: String,
        confirmedGrade: String?,
        evaluatedAtForTarget: Instant?,
        lastSuccessfulGradeChangeAt: Instant?,
        activeAttemptId: String?,
        now: Instant,
    ): GradeEligibility {
        require(targetGrade in setOf("junior", "middle")) { "Unsupported assessment grade" }
        require(confirmedGrade == null || confirmedGrade in setOf("junior", "middle")) {
            "Unsupported confirmed grade"
        }

        if (confirmedGrade == "middle" && targetGrade == "junior") {
            return GradeEligibility(false, null, "policy_blocked")
        }
        if (activeAttemptId != null) return GradeEligibility(false, null, "attempt_active", activeAttemptId)
        if (evaluatedAtForTarget != null) {
            val eligibleAt = evaluatedAtForTarget.plus(retakeInterval)
            if (now.isBefore(eligibleAt)) return GradeEligibility(false, eligibleAt, "same_grade_retake_wait")
        }
        if (confirmedGrade == "junior" && targetGrade == "middle" && lastSuccessfulGradeChangeAt != null) {
            val eligibleAt = lastSuccessfulGradeChangeAt.plus(gradeChangeInterval)
            if (now.isBefore(eligibleAt)) return GradeEligibility(false, eligibleAt, "grade_change_cooldown")
        }
        return GradeEligibility(true, null, "allowed")
    }
}
