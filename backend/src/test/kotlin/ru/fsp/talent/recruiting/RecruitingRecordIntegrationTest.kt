package ru.fsp.talent.recruiting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.TestActor
import java.util.UUID

class RecruitingRecordIntegrationTest : DbTestBase() {
    @Test
    fun `record is company scoped versioned and idempotent`() {
        val companyA = seedActor("employer")
        val companyB = seedActor("employer")
        val candidate = publicCandidate("Candidate One")
        val key = UUID.randomUUID().toString()
        val body = recordBody(stage = "reviewing", note = "Private screening note")

        val created = put(companyA, candidate.id, body, key)
        val replay = put(companyA, candidate.id, body, key)
        assertEquals(200, created.statusCode.value(), created.body.toString())
        assertEquals(200, replay.statusCode.value())
        assertEquals(created.body!!.path("data").path("id").asText(), replay.body!!.path("data").path("id").asText())
        assertEquals(1, created.body!!.path("data").path("version").asInt())
        assertEquals("Candidate One", created.body!!.path("data").path("candidate_display_name").asText())
        assertEquals("public", created.body!!.path("data").path("candidate_visibility").asText())
        assertEquals("Private screening note", created.body!!.path("data").path("note").asText())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM recruiting_records WHERE company_id = ?::uuid", Int::class.java, companyA.companyId))

        val differentBodyWithSameKey = put(companyA, candidate.id, recordBody(stage = "closed", note = "Changed"), key)
        assertEquals(409, differentBodyWithSameKey.statusCode.value())
        assertEquals("IDEMPOTENCY_CONFLICT", differentBodyWithSameKey.body!!.path("error").path("code").asText())

        val update = put(companyA, candidate.id, recordBody(expectedVersion = 1, stage = "invited", note = null), UUID.randomUUID().toString())
        assertEquals(2, update.body!!.path("data").path("version").asInt())
        assertEquals("invited", update.body!!.path("data").path("stage").asText())
        assertTrue(update.body!!.path("data").path("note").isNull)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM invitations WHERE company_id=?::uuid AND candidate_id=?::uuid", Int::class.java, companyA.companyId, candidate.id))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM contact_grants WHERE company_id=?::uuid AND candidate_id=?::uuid", Int::class.java, companyA.companyId, candidate.id))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM qualifications WHERE user_id=?::uuid", Int::class.java, candidate.id))
        val stale = put(companyA, candidate.id, body, UUID.randomUUID().toString())
        assertEquals(409, stale.statusCode.value())
        assertEquals(2, stale.body!!.path("error").path("current_version").asInt())

        val ownList = request(companyA, HttpMethod.GET, "/api/v1/recruiting/records?limit=10")
        assertEquals(200, ownList.statusCode.value())
        assertEquals("private, no-store", ownList.headers.getFirst("Cache-Control"))
        assertEquals(1, ownList.body!!.path("data").path("items").size())
        assertTrue(ownList.body!!.path("data").path("items").get(0).path("note").isNull)

        val otherCompanyList = request(companyB, HttpMethod.GET, "/api/v1/recruiting/records?candidate_id=${candidate.id}")
        assertEquals(200, otherCompanyList.statusCode.value())
        assertEquals(0, otherCompanyList.body!!.path("data").path("items").size())
        val candidateRead = request(candidate, HttpMethod.GET, "/api/v1/recruiting/records?candidate_id=${candidate.id}")
        assertEquals(403, candidateRead.statusCode.value())
    }

    @Test
    fun `hidden candidate record stays private without cached profile fields`() {
        val company = seedActor("employer")
        val otherCompany = seedActor("employer")
        val candidate = publicCandidate("Visible Name")
        val created = put(company, candidate.id, recordBody(note = "Keep only for this company"), UUID.randomUUID().toString())
        assertEquals(200, created.statusCode.value())

        jdbc.update("UPDATE candidate_profiles SET visibility='hidden' WHERE user_id=?::uuid", candidate.id)
        val refreshed = put(company, candidate.id, recordBody(expectedVersion = 1, stage = "closed", note = "Still private"), UUID.randomUUID().toString())
        assertEquals(200, refreshed.statusCode.value())
        val data = refreshed.body!!.path("data")
        assertEquals("hidden", data.path("candidate_visibility").asText())
        assertTrue(data.path("candidate_display_name").isNull)
        assertFalse(data.toString().contains("Visible Name"))
        assertEquals("Still private", data.path("note").asText())

        val otherList = request(otherCompany, HttpMethod.GET, "/api/v1/recruiting/records")
        assertEquals(0, otherList.body!!.path("data").path("items").size())
        val cannotCreateForHidden = put(otherCompany, candidate.id, recordBody(note = "Cross-company attempt"), UUID.randomUUID().toString())
        assertEquals(404, cannotCreateForHidden.statusCode.value())
        assertEquals("NOT_FOUND", cannotCreateForHidden.body!!.path("error").path("code").asText())
        val filtered = request(company, HttpMethod.GET, "/api/v1/recruiting/records?candidate_id=${candidate.id}")
        assertEquals("hidden", filtered.body!!.path("data").path("items").get(0).path("candidate_visibility").asText())
    }

    @Test
    fun `need is optional but must belong to session company`() {
        val company = seedActor("employer")
        val otherCompany = seedActor("employer")
        val candidate = publicCandidate("Need Candidate")
        val ownNeed = need(company, "Own role")
        val foreignNeed = need(otherCompany, "Other role")

        val linked = put(company, candidate.id, recordBody(needId = ownNeed), UUID.randomUUID().toString())
        assertEquals(200, linked.statusCode.value(), linked.body.toString())
        assertEquals(ownNeed, linked.body!!.path("data").path("need_id").asText())
        assertEquals("Own role", linked.body!!.path("data").path("need_title").asText())

        val foreign = put(company, candidate.id, recordBody(expectedVersion = 1, needId = foreignNeed), UUID.randomUUID().toString())
        assertEquals(404, foreign.statusCode.value())
        assertEquals("NOT_FOUND", foreign.body!!.path("error").path("code").asText())
        val preserved = request(company, HttpMethod.GET, "/api/v1/recruiting/records?candidate_id=${candidate.id}")
        assertEquals(ownNeed, preserved.body!!.path("data").path("items").get(0).path("need_id").asText())
    }

    @Test
    fun `list pagination and bounds follow contract`() {
        val company = seedActor("employer")
        val candidates = (1..3).map { publicCandidate("Candidate $it") }
        candidates.forEachIndexed { index, candidate ->
            val at = "2026-10-07T10:00:0${index}Z"
            put(company, candidate.id, recordBody(note = "Note $index"), UUID.randomUUID().toString())
            jdbc.update("UPDATE recruiting_records SET updated_at=?::timestamptz WHERE company_id=?::uuid AND candidate_id=?::uuid", at, company.companyId, candidate.id)
        }
        val first = request(company, HttpMethod.GET, "/api/v1/recruiting/records?limit=1")
        assertEquals(200, first.statusCode.value())
        assertEquals("Note 2", first.body!!.path("data").path("items").get(0).path("note").asText())
        val cursor = first.body!!.path("data").path("next_cursor").asText()
        assertTrue(cursor.isNotBlank())
        val second = request(company, HttpMethod.GET, "/api/v1/recruiting/records?limit=1&cursor=$cursor")
        assertEquals(200, second.statusCode.value())
        assertEquals("Note 1", second.body!!.path("data").path("items").get(0).path("note").asText())
        assertEquals(400, request(company, HttpMethod.GET, "/api/v1/recruiting/records?limit=101").statusCode.value())
    }

    private fun publicCandidate(name: String): TestActor = seedActor("candidate").also { candidate ->
        jdbc.update(
            "UPDATE candidate_profiles SET visibility='public', data=?::jsonb WHERE user_id=?::uuid",
            objectMapper.writeValueAsString(mapOf("display_name" to name, "summary" to "Public summary")),
            candidate.id,
        )
    }

    private fun need(owner: TestActor, title: String): String = UUID.randomUUID().toString().also { id ->
        jdbc.update(
            "INSERT INTO employer_needs(id, company_id, data) VALUES (?::uuid, ?::uuid, ?::jsonb)",
            id, owner.companyId, objectMapper.writeValueAsString(mapOf("title" to title)),
        )
    }

    private fun put(owner: TestActor, candidateId: String, body: Map<String, Any?>, key: String) = request(
        owner, HttpMethod.PUT, "/api/v1/recruiting/records/$candidateId", body, key,
    )

    private fun recordBody(
        expectedVersion: Int = 0,
        needId: String? = null,
        stage: String = "reviewing",
        note: String? = "Private screening note",
    ) = mapOf("expected_version" to expectedVersion, "need_id" to needId, "stage" to stage, "note" to note)
}
