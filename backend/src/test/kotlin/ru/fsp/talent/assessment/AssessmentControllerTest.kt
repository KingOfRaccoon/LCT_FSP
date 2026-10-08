package ru.fsp.talent.assessment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.beans.factory.annotation.Autowired
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.DbTestBase
import java.util.UUID

class AssessmentControllerTest : DbTestBase() {
    @Autowired
    private lateinit var contracts: Contracts

    @Test
    fun `taxonomy exposes the frozen policy without answer keys`() {
        val response = request(method = HttpMethod.GET, path = "/api/v1/taxonomy")

        assertEquals(200, response.statusCode.value())
        val data = response.body!!.path("data")
        assertEquals("backend-python-v1", data.path("specialization_id").asText())
        assertEquals(2, data.path("grades").size())
        assertEquals(24, data.path("assessment_policy").path("maximum_score").asInt())
        contracts.validate("Taxonomy", objectMapper.convertValue(data, Map::class.java).entries.associate { it.key.toString() to it.value })
    }

    @Test
    fun `create and get attempt issue twelve public tasks without answer keys`() {
        val candidate = seedActor()
        val created = request(
            actor = candidate,
            method = HttpMethod.POST,
            path = "/api/v1/attempts",
            body = mapOf(
                "specialization_id" to "backend-python-v1",
                "requested_grade" to "junior",
                "policy_id" to "backend-python-policy-v1",
                "policy_version" to "1.0.0",
            ),
            idempotencyKey = UUID.randomUUID().toString(),
        )

        assertEquals(201, created.statusCode.value())
        val attempt = created.body!!.path("data")
        assertEquals(12, attempt.path("tasks").size())
        assertEquals(true, attempt.path("demo").asBoolean())
        assertEquals("in_progress", attempt.path("status").asText())
        assertEquals(
            setOf("python-basics", "http-api", "sql-transactions", "reliability"),
            attempt.path("tasks").map { it.path("competency_block").asText() }.toSet(),
        )
        attempt.path("tasks").groupBy { it.path("competency_block").asText() }.values.forEach { assertEquals(3, it.size) }
        attempt.path("tasks").forEach { task ->
            assertFalse(task.has("answer_keys"))
            assertFalse(task.has("correct_option_id"))
            assertEquals(2, task.path("subquestions").size())
            task.path("subquestions").forEach { assertEquals(4, it.path("options").size()) }
        }

        val fetched = request(
            actor = candidate,
            method = HttpMethod.GET,
            path = "/api/v1/attempts/${attempt.path("id").asText()}",
        )
        assertEquals(200, fetched.statusCode.value())
        assertEquals(attempt.path("id").asText(), fetched.body!!.path("data").path("id").asText())
        assertNotNull(fetched.body!!.path("data").get("saved_answers"))
        assertTrue(fetched.body!!.path("data").path("result").isNull)
    }

    @Test
    fun `attempt history returns the paginated envelope and validates the requested limit`() {
        val candidate = seedActor()
        val own = request(actor = candidate, method = HttpMethod.GET, path = "/api/v1/me/attempts")
        assertEquals(200, own.statusCode.value())
        assertTrue(own.body!!.path("data").path("items").isArray)
        assertTrue(own.body!!.path("data").path("next_cursor").isNull)

        val invalidLimit = request(actor = candidate, method = HttpMethod.GET, path = "/api/v1/me/attempts?limit=101")
        assertEquals(400, invalidLimit.statusCode.value())
    }

    @Test
    fun `autosave replaces the answer snapshot and submit returns a score without keys`() {
        val candidate = seedActor()
        val created = request(
            actor = candidate,
            method = HttpMethod.POST,
            path = "/api/v1/attempts",
            body = mapOf(
                "specialization_id" to "backend-python-v1",
                "requested_grade" to "junior",
                "policy_id" to "backend-python-policy-v1",
                "policy_version" to "1.0.0",
            ),
            idempotencyKey = UUID.randomUUID().toString(),
        )
        val attempt = created.body!!.path("data")
        val answers = attempt.path("tasks").flatMap { task ->
            task.path("subquestions").map { question ->
                mapOf(
                    "task_id" to task.path("task_id").asText(),
                    "subquestion_id" to question.path("subquestion_id").asText(),
                    "selected_option_id" to question.path("options").first().path("id").asText(),
                )
            }
        }
        val attemptPath = "/api/v1/attempts/${attempt.path("id").asText()}"
        val saved = request(
            actor = candidate,
            method = HttpMethod.PUT,
            path = "$attemptPath/answers",
            body = mapOf("expected_version" to 1, "answers" to answers),
            idempotencyKey = UUID.randomUUID().toString(),
        )
        assertEquals(200, saved.statusCode.value())
        assertEquals(24, saved.body!!.path("data").path("saved_answers").size())
        assertEquals(2, saved.body!!.path("data").path("version").asInt())

        val submitted = request(
            actor = candidate,
            method = HttpMethod.POST,
            path = "$attemptPath/submit",
            body = mapOf("expected_version" to 2),
            idempotencyKey = UUID.randomUUID().toString(),
        )
        assertEquals(200, submitted.statusCode.value())
        val result = submitted.body!!.path("data")
        assertEquals("evaluated", result.path("status").asText())
        assertTrue(result.path("result").path("total_score").asInt() in 0..24)
        assertEquals(4, result.path("result").path("block_scores").size())
        assertFalse(result.path("result").has("answer_keys"))
    }
}
