package ru.fsp.talent.methodist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import java.util.UUID

class MethodistWorkbenchIntegrationTest : DbTestBase() {
    private val insertedAttemptIds = mutableListOf<String>()

    @AfterEach
    fun removeSyntheticMonitoringRows() {
        if (insertedAttemptIds.isNotEmpty()) {
            insertedAttemptIds.forEach { jdbc.update("DELETE FROM attempts WHERE id=?::uuid", it) }
            insertedAttemptIds.clear()
        }
    }

    @Test
    fun `methodist grant is required and unverified accounts cannot use the workbench`() {
        val candidate = seedActor("candidate")
        val unverified = seedActor("candidate", verified = false)

        assertEquals(403, request(candidate, HttpMethod.GET, "/api/v1/methodist/drafts").statusCode.value())
        grantMethodist(unverified.id)
        assertEquals(422, request(unverified, HttpMethod.GET, "/api/v1/methodist/drafts").statusCode.value())
    }

    @Test
    fun `authorized methodist creates revises reviews pilots and exports separately versioned artifacts`() {
        val methodist = seedActor("candidate")
        grantMethodist(methodist.id)

        val created = request(
            methodist, HttpMethod.POST, "/api/v1/methodist/drafts", draftPayload(), UUID.randomUUID().toString(),
        )
        assertEquals(201, created.statusCode.value())
        val draft = created.body!!.path("data")
        val draftId = draft.path("id").asText()
        assertEquals(1, draft.path("version").asInt())
        assertEquals("draft", draft.path("status").asText())
        assertTrue(draft.path("review").isNull)

        val changed = request(
            methodist, HttpMethod.PATCH, "/api/v1/methodist/drafts/$draftId",
            draftPayload() + ("expected_version" to 1) + ("question" to "Обновлённая формулировка?"),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, changed.statusCode.value())
        assertEquals(2, changed.body!!.path("data").path("version").asInt())

        val reviewed = request(
            methodist, HttpMethod.POST, "/api/v1/methodist/drafts/$draftId/review",
            mapOf(
                "expected_version" to 2,
                "outcome" to "approved",
                "marks" to mapOf("relevance" to true, "level" to true, "clarity" to true, "correctness" to true),
                "comment" to "Проверено по указанному источнику.",
            ), UUID.randomUUID().toString(),
        )
        assertEquals(200, reviewed.statusCode.value())
        assertEquals("approved", reviewed.body!!.path("data").path("status").asText())
        assertEquals(methodist.id, jdbc.queryForObject(
            "SELECT actor_id::text FROM methodist_draft_reviews WHERE draft_id=?::uuid ORDER BY created_at DESC LIMIT 1",
            String::class.java, draftId,
        ))

        val observations = (0 until 15).map { index ->
            mapOf("source" to if (index < 10) "synthetic" else "pilot_record", "correct" to (index < 7))
        }
        val pilot = request(
            methodist, HttpMethod.POST, "/api/v1/methodist/drafts/$draftId/pilot",
            mapOf("expected_version" to 2, "observations" to observations), UUID.randomUUID().toString(),
        )
        assertEquals(200, pilot.statusCode.value())
        assertEquals(15, pilot.body!!.path("data").path("accepted_count").asInt())

        val report = request(methodist, HttpMethod.GET, "/api/v1/methodist/drafts/$draftId/pilot-report")
        assertEquals(200, report.statusCode.value())
        assertEquals(15, report.body!!.path("data").path("sample_count").asInt())
        assertTrue(report.body!!.path("data").path("accuracy").isNull)
        assertEquals(0.7, report.body!!.path("data").path("accuracy_by_source").path("synthetic").asDouble(), 0.0001)
        assertTrue(report.body!!.path("data").path("accuracy_by_source").path("pilot_record").isNull)
        assertEquals(10, report.body!!.path("data").path("source_counts").path("synthetic").asInt())
        assertEquals(5, report.body!!.path("data").path("source_counts").path("pilot_record").asInt())
        assertFalse(report.body.toString().contains(methodist.id))

        val exported = request(methodist, HttpMethod.GET, "/api/v1/methodist/drafts/$draftId/export")
        assertEquals(200, exported.statusCode.value())
        val data = exported.body!!.path("data")
        assertEquals("draft-v1", data.path("candidate_form").path("format").asText())
        assertEquals("draft-v1", data.path("private_answer_key").path("format").asText())
        assertFalse(data.path("candidate_form").path("source").has("excerpt"))
        assertEquals("Документация описывает структуру данных dict.", data.path("private_answer_key").path("source_excerpt").asText())
        assertFalse(data.path("candidate_form").has("rubric"))
        assertEquals("Верный ответ — dict.fromkeys.", data.path("private_answer_key").path("rubric").asText())
        assertFalse(data.path("candidate_form").has("correct_option"))
        assertFalse(data.path("candidate_form").path("options").get(0).has("correct"))
        assertEquals(2, data.path("private_answer_key").path("correct_option").asInt())
        assertFalse(data.path("live_bank_published").asBoolean())

        val revised = request(
            methodist, HttpMethod.PATCH, "/api/v1/methodist/drafts/$draftId",
            draftPayload() + ("expected_version" to 2) + ("rubric" to "Рубрика версии 3."),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, revised.statusCode.value())
        assertEquals(3, revised.body!!.path("data").path("version").asInt())
        assertEquals("draft", revised.body!!.path("data").path("status").asText())
        val resetReport = request(methodist, HttpMethod.GET, "/api/v1/methodist/drafts/$draftId/pilot-report")
        assertEquals(0, resetReport.body!!.path("data").path("sample_count").asInt())
        assertTrue(resetReport.body!!.path("data").path("accuracy").isNull)
        assertFalse(resetReport.body!!.path("data").has("review"))
    }

    @Test
    fun `monitor exposes aggregate current-bank data without identities attempts or answer payloads`() {
        val methodist = seedActor("employer")
        grantMethodist(methodist.id)

        val response = request(methodist, HttpMethod.GET, "/api/v1/methodist/bank/monitor")

        assertEquals(200, response.statusCode.value())
        val body = response.body!!.path("data")
        assertTrue(body.path("families_total").asInt() > 0)
        assertTrue(body.path("coverage").isArray)
        assertTrue(body.path("difficulty_windows").isArray)
        assertFalse(body.toString().contains("user_id"))
        assertFalse(body.toString().contains("attempt_id"))
        assertFalse(body.has("answer_keys"))
        assertFalse(body.toString().contains(methodist.id))
    }

    @Test
    fun `bank monitor ignores old bank exposure and compares only adjacent adequate score windows`() {
        val methodist = seedActor("employer")
        grantMethodist(methodist.id)
        val candidate = seedActor("candidate")
        val before = request(methodist, HttpMethod.GET, "/api/v1/methodist/bank/monitor")
        assertEquals(200, before.statusCode.value())
        val familyBefore = before.body!!.path("data").path("repeated_use").first {
            it.path("family_id").asText() == "pyj-collections"
        }.path("exposure_count").asLong()

        seedEvaluatedAttempts(candidate.id, bankVersion = "obsolete-bank-v0", monthOffset = 3, totalScore = 0, count = 1)
        val oldBankAttemptId = jdbc.queryForObject(
            "SELECT id::text FROM attempts WHERE user_id=?::uuid AND bank_version='obsolete-bank-v0' ORDER BY evaluated_at DESC LIMIT 1",
            String::class.java, candidate.id,
        )!!
        jdbc.update(
            "INSERT INTO task_exposures(id,attempt_id,user_id,family_id,variant_id) VALUES (?::uuid,?::uuid,?::uuid,'pyj-collections','pyj-collections-A')",
            UUID.randomUUID().toString(), oldBankAttemptId, candidate.id,
        )
        seedEvaluatedAttempts(candidate.id, bankVersion = "backend-python-v1.0", monthOffset = 3, totalScore = 0, count = 10)
        seedEvaluatedAttempts(candidate.id, bankVersion = "backend-python-v1.0", monthOffset = 2, totalScore = 24, count = 10)
        seedEvaluatedAttempts(candidate.id, bankVersion = "backend-python-v1.0", monthOffset = 1, totalScore = 24, count = 10)

        val after = request(methodist, HttpMethod.GET, "/api/v1/methodist/bank/monitor")
        assertEquals(200, after.statusCode.value())
        val body = after.body!!.path("data")
        val familyAfter = body.path("repeated_use").first {
            it.path("family_id").asText() == "pyj-collections"
        }.path("exposure_count").asLong()
        assertEquals(familyBefore, familyAfter)

        val firstMonth = jdbc.queryForObject(
            "SELECT to_char(date_trunc('month',now()) - interval '3 months','YYYY-MM-DD')", String::class.java,
        )!!
        val secondMonth = jdbc.queryForObject(
            "SELECT to_char(date_trunc('month',now()) - interval '2 months','YYYY-MM-DD')", String::class.java,
        )!!
        val thirdMonth = jdbc.queryForObject(
            "SELECT to_char(date_trunc('month',now()) - interval '1 month','YYYY-MM-DD')", String::class.java,
        )!!
        val windows = body.path("difficulty_windows").filter { it.path("grade").asText() == "junior" }
            .associateBy { it.path("window").asText() }
        assertEquals(10, windows.getValue(firstMonth).path("sample_count").asInt())
        assertEquals(24.0, windows.getValue(secondMonth).path("mean_total_score").asDouble())
        assertEquals(24.0, windows.getValue(thirdMonth).path("mean_total_score").asDouble())
        assertFalse(windows.getValue(firstMonth).path("review_required").asBoolean())
        assertTrue(windows.getValue(secondMonth).path("review_required").asBoolean())
        assertFalse(windows.getValue(thirdMonth).path("review_required").asBoolean())
    }

    @Test
    fun `draft mutation rejects malformed options and stale versions`() {
        val methodist = seedActor("candidate")
        grantMethodist(methodist.id)

        val malformed = request(
            methodist, HttpMethod.POST, "/api/v1/methodist/drafts",
            draftPayload() + ("options" to listOf("A", "B", "C")), UUID.randomUUID().toString(),
        )
        assertEquals(400, malformed.statusCode.value())

        val draft = request(
            methodist, HttpMethod.POST, "/api/v1/methodist/drafts", draftPayload(), UUID.randomUUID().toString(),
        )
        val draftId = draft.body!!.path("data").path("id").asText()
        val stale = request(
            methodist, HttpMethod.PATCH, "/api/v1/methodist/drafts/$draftId",
            draftPayload() + ("expected_version" to 2), UUID.randomUUID().toString(),
        )
        assertEquals(409, stale.statusCode.value())
        assertEquals(1, stale.body!!.path("error").path("current_version").asInt())
    }

    private fun grantMethodist(userId: String) {
        jdbc.update(
            "INSERT INTO assessment_methodists(user_id, active) VALUES (?::uuid, true)",
            userId,
        )
    }

    private fun seedEvaluatedAttempts(
        userId: String,
        bankVersion: String,
        monthOffset: Int,
        totalScore: Int,
        count: Int,
    ) {
        val perBlock = totalScore / 4
        val result = mapOf(
            "total_score" to totalScore,
            "block_scores" to listOf("python-basics", "http-api", "sql-transactions", "reliability").map { competency ->
                mapOf("block_id" to competency, "score" to perBlock, "max_score" to 6)
            },
        )
        repeat(count) {
            val attemptId = UUID.randomUUID().toString()
            insertedAttemptIds += attemptId
            jdbc.update(
                """INSERT INTO attempts(id,user_id,requested_grade,status,bank_version,evaluated_at,deadline_at,result)
                   VALUES (?::uuid,?::uuid,'junior','evaluated',?,date_trunc('month',now()) - (? * interval '1 month'),now(),?::jsonb)""".trimIndent(),
                attemptId, userId, bankVersion, monthOffset, objectMapper.writeValueAsString(result),
            )
        }
    }

    private fun draftPayload(): Map<String, Any?> = mapOf(
        "source_title" to "Официальная документация Python",
        "source_reference" to "https://docs.python.org/3/library/collections.html",
        "source_excerpt" to "Документация описывает структуру данных dict.",
        "competency_id" to "python-basics",
        "grade" to "junior",
        "question" to "Как сохранить порядок первого появления элементов?",
        "options" to listOf("dict.fromkeys", "set", "sorted", "reverse"),
        "correct_option" to 2,
        "rubric" to "Верный ответ — dict.fromkeys.",
    )
}
