package ru.fsp.talent.shorttasks

import ru.fsp.talent.core.ApiException
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime

internal object ShortTaskRules {
    const val SHARING_NOTICE_VERSION = "short-task-sharing-v1"
    const val SHARING_NOTICE_TEXT = "Я отдельно разрешаю показать этой компании моё решение и обратную связь по этой задаче. Это не открывает контакты; доступ к решению можно отозвать."

    fun validateCreate(body: Map<String, Any?>, now: Instant = Instant.now()): ValidatedShortTaskCreate {
        val title = requiredText(body, "title", 160)
        val purpose = requiredText(body, "purpose", 500)
        val instructions = requiredText(body, "instructions", 8000)
        val estimatedMinutes = number(body["estimated_minutes"], "estimated_minutes")
        if (estimatedMinutes !in 1..480) fail("estimated_minutes", "Estimated time must be from 1 to 480 minutes")
        val dueAt = parseInstant(body["due_at"], "due_at")
        if (!dueAt.isAfter(now)) fail("due_at", "Deadline must be in the future")
        val mode = body["evaluation_mode"]?.toString()
        val criteria = (body["rubric"] as? Iterable<*>)?.map { item ->
            val criterion = item as? Map<*, *> ?: fail("rubric", "Each rubric criterion must be an object")
            val id = criterion["id"]?.toString()?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,64}")) }
                ?: fail("rubric", "Each rubric criterion needs a valid id")
            val label = criterion["label"]?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
                ?: fail("rubric", "Each rubric criterion needs a label")
            val maxPoints = number(criterion["max_points"], "rubric.max_points")
            if (maxPoints !in 1..100) fail("rubric.max_points", "Maximum points must be from 1 to 100")
            mapOf("id" to id, "label" to label, "max_points" to maxPoints)
        } ?: fail("rubric", "rubric is required")
        if (criteria.size > 20 || criteria.map { it.getValue("id") }.toSet().size != criteria.size) {
            fail("rubric", "Rubric must have at most 20 unique criteria")
        }
        val rawExpected = body["expected_answer"]
        val expectedAnswer = when (mode) {
            "exact" -> (rawExpected as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 1000 }
                ?: fail("expected_answer", "Exact evaluation requires a private expected answer")
            "rubric" -> {
                if (rawExpected != null) fail("expected_answer", "Rubric evaluation cannot include an expected answer")
                null
            }
            else -> fail("evaluation_mode", "Unsupported evaluation mode")
        }
        if ((mode == "exact" && criteria.isNotEmpty()) || (mode == "rubric" && criteria.isEmpty())) {
            fail("rubric", "Exact evaluation requires an empty rubric; rubric evaluation requires criteria")
        }
        return ValidatedShortTaskCreate(title, purpose, instructions, estimatedMinutes, dueAt, mode!!, expectedAnswer, criteria)
    }

    fun validateReview(body: Map<String, Any?>, rubric: List<Map<String, Any?>>): Map<String, Any?> {
        val requested = (body["criteria"] as? Iterable<*>)?.map { item ->
            val row = item as? Map<*, *> ?: fail("criteria", "Each review criterion must be an object")
            val id = row["id"]?.toString() ?: fail("criteria", "Review criterion id is required")
            val score = number(row["score"], "criteria.score")
            mapOf("id" to id, "score" to score)
        } ?: fail("criteria", "Review criteria are required")
        val rubricById = rubric.associateBy { it["id"].toString() }
        if (requested.map { it.getValue("id") }.toSet().size != requested.size ||
            requested.size != rubricById.size || requested.any { criterion ->
                val maximum = (rubricById[criterion.getValue("id")]?.get("max_points") as? Number)?.toInt() ?: return@any true
                (criterion.getValue("score") as Int) !in 0..maximum
            }
        ) fail("criteria", "Scores must cover the declared rubric and stay within each criterion range")
        val feedback = body["feedback"] as? String ?: fail("feedback", "feedback must be a string")
        val source = body["source"]?.toString() ?: fail("source", "source is required")
        if (source != "employer") fail("source", "Only employer review is supported")
        return mapOf("criteria" to requested, "feedback" to feedback, "source" to source)
    }

    fun version(body: Map<String, Any?>): Int = number(body["expected_version"], "expected_version").also {
        if (it < 1) fail("expected_version", "expected_version must be positive")
    }

    private fun requiredText(body: Map<String, Any?>, field: String, maxLength: Int): String =
        (body[field] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= maxLength }
            ?: fail(field, "$field must contain 1 to $maxLength characters")

    private fun parseInstant(value: Any?, field: String): Instant =
        runCatching { OffsetDateTime.parse(value?.toString()).toInstant() }.getOrNull()
            ?: fail(field, "$field must be an ISO-8601 timestamp")

    private fun number(value: Any?, field: String): Int = (value as? Number)?.toInt()
        ?: fail(field, "$field must be an integer")

    private fun fail(field: String, message: String): Nothing =
        throw ApiException(400, "VALIDATION_ERROR", message, field)
}

internal data class ValidatedShortTaskCreate(
    val title: String,
    val purpose: String,
    val instructions: String,
    val estimatedMinutes: Int,
    val dueAt: Instant,
    val evaluationMode: String,
    val expectedAnswer: String?,
    val rubric: List<Map<String, Any?>>,
)

internal fun exactAnswerMatches(expected: String?, actual: String): Boolean {
    val expectedText = expected?.trim() ?: return false
    val actualText = actual.trim()
    val expectedNumber = expectedText.toBigDecimalOrNull()
    val actualNumber = actualText.toBigDecimalOrNull()
    return if (expectedNumber != null && actualNumber != null) expectedNumber.compareTo(actualNumber) == 0
    else expectedText == actualText
}
