package ru.fsp.talent.qualifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class QualificationPortfolioControllerTest : DbTestBase() {
    @Test
    fun `portfolio is empty for a candidate without qualifications and lists only the released catalog`() {
        val candidate = seedActor()

        val response = request(candidate, HttpMethod.GET, "/api/v1/me/qualifications")

        assertEquals(200, response.statusCode.value())
        val data = response.body!!.path("data")
        assertTrue(data.path("items").isArray)
        assertEquals(0, data.path("items").size())
        val catalog = data.path("available_specializations")
        assertEquals(1, catalog.size())
        assertEquals("backend-python-v1", catalog[0].path("id").asText())
        assertEquals("Backend Python", catalog[0].path("label").asText())
        assertEquals(setOf("junior", "middle"), catalog[0].path("grades").map { it.asText() }.toSet())
        assertTrue(data.path("catalog_note").asText().isNotBlank())
    }

    @Test
    fun `portfolio exposes own published demo evidence with safe metadata and does not mutate profile or qualification`() {
        val candidate = seedActor()
        val other = seedActor()
        val assessedAt = Instant.parse("2026-10-07T10:15:30Z")
        val attemptId = insertEvaluatedAttempt(candidate.id, "backend-python-v1")
        insertQualification(candidate.id, "backend-python-v1", "middle", assessedAt, attemptId, """{"demo":true}""")
        insertQualification(other.id, "backend-python-v1", "junior", assessedAt, null, """{"demo":false}""")
        val beforeCount = jdbc.queryForObject(
            "SELECT count(*) FROM qualifications WHERE user_id = ?::uuid",
            Int::class.java,
            candidate.id,
        )
        val beforeProfile = jdbc.queryForMap(
            "SELECT version, updated_at FROM candidate_profiles WHERE user_id = ?::uuid",
            candidate.id,
        )

        val response = request(candidate, HttpMethod.GET, "/api/v1/me/qualifications")

        assertEquals(200, response.statusCode.value())
        val items = response.body!!.path("data").path("items")
        assertEquals(1, items.size())
        val item = items[0]
        assertEquals("backend-python-v1", item.path("specialization_id").asText())
        assertEquals("Backend Python", item.path("label").asText())
        assertEquals("middle", item.path("grade").asText())
        assertEquals(assessedAt.toString(), Instant.parse(item.path("assessed_at").asText()).toString())
        assertEquals("1.0.0", item.path("policy_version").asText())
        assertEquals("backend-python-v1.0", item.path("bank_version").asText())
        assertTrue(item.path("demo").asBoolean())
        assertEquals("platform_assessment", item.path("source").asText())
        assertEquals("published_demo", item.path("method_status").asText())
        assertFalse(item.has("evidence_attempt_id"))
        assertFalse(item.toString().contains(candidate.id))
        assertFalse(item.toString().contains(other.id))
        assertFalse(item.toString().contains(attemptId))
        assertFalse(item.toString().contains("answer"))
        assertFalse(item.toString().contains("rubric"))
        assertFalse(item.toString().contains("total_score"))
        assertEquals(beforeCount, jdbc.queryForObject(
            "SELECT count(*) FROM qualifications WHERE user_id = ?::uuid",
            Int::class.java,
            candidate.id,
        ))
        assertEquals(beforeProfile, jdbc.queryForMap(
            "SELECT version, updated_at FROM candidate_profiles WHERE user_id = ?::uuid",
            candidate.id,
        ))
    }

    @Test
    fun `future specialization with unavailable evidence method does not publish a grade or another candidates attempt`() {
        val candidate = seedActor()
        val other = seedActor()
        val otherAttemptId = insertEvaluatedAttempt(other.id, "mobile-kotlin-v2")
        insertQualification(
            candidate.id,
            "mobile-kotlin-v2",
            "middle",
            Instant.parse("2026-10-06T08:00:00Z"),
            otherAttemptId,
            """{"demo":false,"total_score":99}""",
        )

        val response = request(candidate, HttpMethod.GET, "/api/v1/me/qualifications")

        assertEquals(200, response.statusCode.value())
        val data = response.body!!.path("data")
        assertEquals(1, data.path("items").size())
        val item = data.path("items")[0]
        assertEquals("mobile-kotlin-v2", item.path("specialization_id").asText())
        assertTrue(item.path("grade").isNull)
        assertEquals("unavailable", item.path("method_status").asText())
        assertFalse(item.path("demo").asBoolean())
        assertFalse(item.has("evidence_attempt_id"))
        assertFalse(item.toString().contains(otherAttemptId))
        assertFalse(item.toString().contains("99"))
        assertFalse(item.toString().contains(other.id))
        assertEquals(1, data.path("available_specializations").size())
    }

    private fun insertEvaluatedAttempt(userId: String, specializationId: String): String {
        val id = UUID.randomUUID().toString()
        jdbc.update(
            """INSERT INTO attempts(id,user_id,specialization_id,requested_grade,status,deadline_at,evaluated_at,tasks,result)
               VALUES (?::uuid, ?::uuid, ?, 'junior', 'evaluated', now() + interval '1 day', now(),
                       '{"demo":true}'::jsonb, '{"status":"evaluated"}'::jsonb)""".trimIndent(),
            id,
            userId,
            specializationId,
        )
        return id
    }

    private fun insertQualification(
        userId: String,
        specializationId: String,
        grade: String,
        assessedAt: Instant,
        attemptId: String?,
        data: String,
    ) {
        jdbc.update(
            """INSERT INTO qualifications(
                   id,user_id,specialization_id,grade,evidence_attempt_id,assessed_at,policy_version,bank_version,data
               ) VALUES (?::uuid, ?::uuid, ?, ?, ?::uuid, ?, '1.0.0', 'backend-python-v1.0', ?::jsonb)""".trimIndent(),
            UUID.randomUUID().toString(),
            userId,
            specializationId,
            grade,
            attemptId,
            Timestamp.from(assessedAt),
            data,
        )
    }
}
