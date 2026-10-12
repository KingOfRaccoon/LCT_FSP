package ru.fsp.talent.assessment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

class AssessmentPolicyTest {
    @Test
    fun `all frozen numeric score examples match the scoring service`() {
        val fixtures = ObjectMapper().readTree(File("../docs/validation/assessment-cases.v1.json"))
        fixtures.path("scoring_cases").forEach { example ->
            val byCompetency = example.path("scores_by_competency").fields().asSequence()
                .associate { it.key to it.value.asInt() }
            val result = AssessmentScoring.score(correctByCompetency = byCompetency)
            assertEquals(example.path("expected_pass").asBoolean(), result.passed, example.path("id").asText())
            assertEquals(example.path("expected_confident").asBoolean(), result.confident, example.path("id").asText())
        }
    }

    @Test
    fun `pass requires eighteen points and at least three in every competency`() {
        val boundary = AssessmentScoring.score(
            correctByCompetency = mapOf(
                "python-basics" to 5,
                "http-api" to 5,
                "sql-transactions" to 4,
                "reliability" to 4,
            ),
        )
        val criticalBelow = AssessmentScoring.score(
            correctByCompetency = mapOf(
                "python-basics" to 6,
                "http-api" to 6,
                "sql-transactions" to 6,
                "reliability" to 2,
            ),
        )

        assertEquals(18, boundary.total)
        assertTrue(boundary.passed)
        assertFalse(boundary.confident)
        assertEquals(20, criticalBelow.total)
        assertFalse(criticalBelow.passed)
    }

    @Test
    fun `answer-key evaluation gives one point per correct subquestion and zero for wrong answers`() {
        val targetScores = mapOf(
            "python-basics" to 5,
            "http-api" to 5,
            "sql-transactions" to 4,
            "reliability" to 4,
        )
        val questions = ASSESSMENT_COMPETENCIES.flatMap { competency ->
            (0 until 6).map { index ->
                AssessmentQuestionKey(
                    taskId = "$competency-task-${index / 2}",
                    competencyId = competency,
                    subquestionId = "$competency-question-$index",
                    correctOptionId = "right-$index",
                )
            }
        }
        val answers: List<Map<String, Any?>> = questions.mapIndexed { index, question ->
            val perCompetencyIndex = index % 6
            mapOf(
                "task_id" to question.taskId,
                "subquestion_id" to question.subquestionId,
                "selected_option_id" to if (perCompetencyIndex < targetScores.getValue(question.competencyId)) {
                    question.correctOptionId
                } else "wrong-option",
            )
        }

        val score = AssessmentScoring.score(questions, answers)

        assertEquals(18, score.total)
        assertEquals(targetScores, score.byCompetency)
        assertTrue(score.passed)
    }

    @Test
    fun `confident result does not promote and failed assessment preserves qualification`() {
        val confident = AssessmentScoring.score(
            correctByCompetency = mapOf(
                "python-basics" to 6,
                "http-api" to 6,
                "sql-transactions" to 5,
                "reliability" to 5,
            ),
        )
        val initialJunior = AssessmentPolicy.transition(
            confirmedGrade = null,
            targetGrade = "junior",
            passed = confident.passed,
            evaluatedAt = Instant.parse("2026-10-07T10:00:00Z"),
            lastSuccessfulGradeChangeAt = null,
        )

        assertTrue(confident.confident)
        assertEquals("junior", initialJunior.confirmedGrade)
        assertNull(initialJunior.lastSuccessfulGradeChangeAt)

        val priorChangeAt = Instant.parse("2026-09-01T10:00:00Z")
        val failedPromotion = AssessmentPolicy.transition(
            confirmedGrade = "junior",
            targetGrade = "middle",
            passed = false,
            evaluatedAt = Instant.parse("2026-10-08T10:00:00Z"),
            lastSuccessfulGradeChangeAt = priorChangeAt,
        )
        assertEquals("junior", failedPromotion.confirmedGrade)
        assertEquals(priorChangeAt, failedPromotion.lastSuccessfulGradeChangeAt)
    }

    @Test
    fun `initial middle pass has no grade-change cooldown but promotion does`() {
        val firstMiddle = AssessmentPolicy.transition(
            confirmedGrade = null,
            targetGrade = "middle",
            passed = true,
            evaluatedAt = Instant.parse("2026-10-07T10:00:00Z"),
            lastSuccessfulGradeChangeAt = null,
        )
        val promotion = AssessmentPolicy.transition(
            confirmedGrade = "junior",
            targetGrade = "middle",
            passed = true,
            evaluatedAt = Instant.parse("2026-10-07T10:00:00Z"),
            lastSuccessfulGradeChangeAt = null,
        )

        assertEquals("middle", firstMiddle.confirmedGrade)
        assertNull(firstMiddle.lastSuccessfulGradeChangeAt)
        assertEquals("middle", promotion.confirmedGrade)
        assertEquals(Instant.parse("2026-10-07T10:00:00Z"), promotion.lastSuccessfulGradeChangeAt)
    }

    @Test
    fun `same-grade success refreshes evidence without resetting grade-change time`() {
        val priorChangeAt = Instant.parse("2026-09-01T10:00:00Z")
        val result = AssessmentPolicy.transition(
            confirmedGrade = "junior",
            targetGrade = "junior",
            passed = true,
            evaluatedAt = Instant.parse("2026-10-07T10:00:00Z"),
            lastSuccessfulGradeChangeAt = priorChangeAt,
        )

        assertEquals("junior", result.confirmedGrade)
        assertEquals(priorChangeAt, result.lastSuccessfulGradeChangeAt)
        assertTrue(result.refreshesEvidence)
    }

    @Test
    fun `retake delay is per target grade and expires exactly at boundary`() {
        val evaluated = Instant.parse("2026-10-07T10:00:00Z")
        val firstMiddleFailure = AssessmentPolicy.eligibility(
            targetGrade = "middle",
            confirmedGrade = null,
            evaluatedAtForTarget = evaluated,
            lastSuccessfulGradeChangeAt = null,
            activeAttemptId = null,
            now = Instant.parse("2026-10-08T09:59:59Z"),
        )
        val juniorImmediatelyAfterMiddleFailure = AssessmentPolicy.eligibility(
            targetGrade = "junior",
            confirmedGrade = null,
            evaluatedAtForTarget = null,
            lastSuccessfulGradeChangeAt = null,
            activeAttemptId = null,
            now = Instant.parse("2026-10-07T10:00:01Z"),
        )
        val middleAtBoundary = AssessmentPolicy.eligibility(
            targetGrade = "middle",
            confirmedGrade = null,
            evaluatedAtForTarget = evaluated,
            lastSuccessfulGradeChangeAt = null,
            activeAttemptId = null,
            now = Instant.parse("2026-10-08T10:00:00Z"),
        )

        assertFalse(firstMiddleFailure.allowed)
        assertTrue(juniorImmediatelyAfterMiddleFailure.allowed)
        assertTrue(middleAtBoundary.allowed)
    }

    @Test
    fun `confirmed middle cannot be voluntarily downgraded`() {
        val eligibility = AssessmentPolicy.eligibility(
            targetGrade = "junior",
            confirmedGrade = "middle",
            evaluatedAtForTarget = null,
            lastSuccessfulGradeChangeAt = null,
            activeAttemptId = null,
            now = Instant.parse("2026-10-07T10:00:00Z"),
        )

        assertFalse(eligibility.allowed)
    }

    @Test
    fun `promotion cooldown expires exactly thirty days after the successful change`() {
        val changedAt = Instant.parse("2026-09-01T10:00:00Z")
        val beforeBoundary = AssessmentPolicy.eligibility(
            targetGrade = "middle",
            confirmedGrade = "junior",
            evaluatedAtForTarget = null,
            lastSuccessfulGradeChangeAt = changedAt,
            activeAttemptId = null,
            now = Instant.parse("2026-10-01T09:59:59Z"),
        )
        val atBoundary = AssessmentPolicy.eligibility(
            targetGrade = "middle",
            confirmedGrade = "junior",
            evaluatedAtForTarget = null,
            lastSuccessfulGradeChangeAt = changedAt,
            activeAttemptId = null,
            now = Instant.parse("2026-10-01T10:00:00Z"),
        )

        assertFalse(beforeBoundary.allowed)
        assertTrue(atBoundary.allowed)
    }

    @Test
    fun `active attempt blocks both grades and returns its own identifier`() {
        val decision = AssessmentPolicy.eligibility(
            targetGrade = "junior",
            confirmedGrade = null,
            evaluatedAtForTarget = null,
            lastSuccessfulGradeChangeAt = null,
            activeAttemptId = "a1d87caf-a3c5-4b7f-8c72-8edcb4516878",
            now = Instant.parse("2026-10-07T10:00:00Z"),
        )

        assertFalse(decision.allowed)
        assertEquals("a1d87caf-a3c5-4b7f-8c72-8edcb4516878", decision.activeAttemptId)
    }
}
