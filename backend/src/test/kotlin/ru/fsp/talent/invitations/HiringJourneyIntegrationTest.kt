package ru.fsp.talent.invitations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.TestActor
import java.util.UUID

class HiringJourneyIntegrationTest : DbTestBase() {
    @Test
    fun `accepted participants can read the shared next step`() {
        val fixture = acceptedInvitation()

        val response = request(fixture.candidate, HttpMethod.GET, nextStepPath(fixture.invitationId))

        assertEquals(200, response.statusCode.value(), response.body.toString())
        val data = response.body!!.path("data")
        assertEquals(fixture.invitationId, data.path("invitation_id").asText())
        assertEquals(1, data.path("invitation_version").asInt())
        assertTrue(data.path("hiring_plan").isNull)
        assertTrue(data.path("step").isNull)
    }

    @Test
    fun `invitation owners can create and read the shared next step`() {
        val fixture = acceptedInvitation()
        val body = mapOf(
            "expected_version" to 0,
            "action" to "Schedule an introductory call",
            "responsible_party" to "company",
            "due_at" to "2026-10-12T10:30:00+03:00",
            "completed" to false,
        )
        val key = UUID.randomUUID().toString()

        val written = request(fixture.company, HttpMethod.PUT, nextStepPath(fixture.invitationId), body, key)
        assertEquals(200, written.statusCode.value(), written.body.toString())
        assertEquals(1, written.body!!.path("data").path("step").path("version").asInt())
        assertTrue(written.body!!.path("data").path("can_update").asBoolean())

        val replay = request(fixture.company, HttpMethod.PUT, nextStepPath(fixture.invitationId), body, key)
        assertEquals(200, replay.statusCode.value(), replay.body.toString())
        assertEquals(written.body!!.path("data"), replay.body!!.path("data"))

        val candidateRead = request(fixture.candidate, HttpMethod.GET, nextStepPath(fixture.invitationId))
        assertFalse(candidateRead.body!!.path("data").path("can_update").asBoolean())
        assertEquals("Schedule an introductory call", candidateRead.body!!.path("data").path("step").path("action").asText())
    }

    @Test
    fun `candidate can read but cannot update and revoked grant leaves a candidate archive`() {
        val fixture = acceptedInvitation()
        val body = nextStepBody(0, "Company will send interview options")
        val candidateWrite = request(
            fixture.candidate,
            HttpMethod.PUT,
            nextStepPath(fixture.invitationId),
            body,
            UUID.randomUUID().toString(),
        )
        assertEquals(404, candidateWrite.statusCode.value())
        val writeKey = UUID.randomUUID().toString()
        val written = request(
            fixture.company,
            HttpMethod.PUT,
            nextStepPath(fixture.invitationId),
            body,
            writeKey,
        )
        assertEquals(200, written.statusCode.value(), written.body.toString())

        val grantId = jdbc.queryForObject(
            "SELECT contact_grant_id::text FROM invitations WHERE id = ?::uuid",
            String::class.java,
            fixture.invitationId,
        )!!
        val revoked = request(
            fixture.candidate,
            HttpMethod.POST,
            "/api/v1/me/contact-grants/$grantId/revoke",
            emptyMap<String, Any?>(),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, revoked.statusCode.value(), revoked.body.toString())
        jdbc.update("UPDATE users SET email_verified_at = NULL WHERE id = ?::uuid", fixture.candidate.id)

        val archive = request(fixture.candidate, HttpMethod.GET, nextStepPath(fixture.invitationId))
        assertEquals(200, archive.statusCode.value())
        assertTrue(archive.body!!.path("data").path("archived").asBoolean())
        assertFalse(archive.body!!.path("data").path("can_update").asBoolean())
        assertEquals("Company will send interview options", archive.body!!.path("data").path("step").path("action").asText())
        assertEquals(404, request(fixture.company, HttpMethod.GET, nextStepPath(fixture.invitationId)).statusCode.value())

        val replayAfterRevoke = request(
            fixture.company,
            HttpMethod.PUT,
            nextStepPath(fixture.invitationId),
            body,
            writeKey,
        )
        assertEquals(404, replayAfterRevoke.statusCode.value())
    }

    @Test
    fun `new grant for another accepted invitation does not reopen old next step`() {
        val fixture = acceptedInvitation()
        val body = nextStepBody(0, "Original invitation step")
        assertEquals(
            200,
            request(fixture.company, HttpMethod.PUT, nextStepPath(fixture.invitationId), body, UUID.randomUUID().toString()).statusCode.value(),
        )
        val oldGrantId = jdbc.queryForObject(
            "SELECT contact_grant_id::text FROM invitations WHERE id = ?::uuid",
            String::class.java,
            fixture.invitationId,
        )!!
        assertEquals(200, request(
            fixture.candidate,
            HttpMethod.POST,
            "/api/v1/me/contact-grants/$oldGrantId/revoke",
            emptyMap<String, Any?>(),
            UUID.randomUUID().toString(),
        ).statusCode.value())

        val replacementId = createAndAccept(fixture.company, fixture.candidate)
        val newGrantId = jdbc.queryForObject(
            "SELECT contact_grant_id::text FROM invitations WHERE id = ?::uuid",
            String::class.java,
            replacementId,
        )!!
        assertTrue(oldGrantId != newGrantId)
        assertEquals(200, request(fixture.candidate, HttpMethod.GET, nextStepPath(fixture.invitationId)).statusCode.value())
        assertTrue(request(fixture.candidate, HttpMethod.GET, nextStepPath(fixture.invitationId)).body!!.path("data").path("archived").asBoolean())
        assertEquals(404, request(fixture.company, HttpMethod.GET, nextStepPath(fixture.invitationId)).statusCode.value())
        assertEquals(200, request(fixture.company, HttpMethod.GET, nextStepPath(replacementId)).statusCode.value())
    }

    @Test
    fun `stale next-step version conflicts and writes leave invitation state and snapshot unchanged`() {
        val fixture = acceptedInvitation()
        val before = jdbc.queryForMap(
            "SELECT state, version, snapshot::text AS snapshot FROM invitations WHERE id = ?::uuid",
            fixture.invitationId,
        )
        val firstBody = nextStepBody(0, "First action")
        val firstKey = UUID.randomUUID().toString()
        val first = request(fixture.company, HttpMethod.PUT, nextStepPath(fixture.invitationId), firstBody, firstKey)
        assertEquals(200, first.statusCode.value(), first.body.toString())
        val replay = request(fixture.company, HttpMethod.PUT, nextStepPath(fixture.invitationId), firstBody, firstKey)
        assertEquals(200, replay.statusCode.value(), replay.body.toString())
        assertEquals(first.body!!.path("data"), replay.body!!.path("data"))
        val changedRetry = request(
            fixture.company,
            HttpMethod.PUT,
            nextStepPath(fixture.invitationId),
            nextStepBody(0, "Changed retry body"),
            firstKey,
        )
        assertEquals(409, changedRetry.statusCode.value())
        assertEquals("IDEMPOTENCY_CONFLICT", changedRetry.body!!.path("error").path("code").asText())

        val stale = request(
            fixture.company,
            HttpMethod.PUT,
            nextStepPath(fixture.invitationId),
            nextStepBody(0, "Stale action"),
            UUID.randomUUID().toString(),
        )
        assertEquals(409, stale.statusCode.value(), stale.body.toString())
        assertEquals("VERSION_CONFLICT", stale.body!!.path("error").path("code").asText())
        val after = jdbc.queryForMap(
            "SELECT state, version, snapshot::text AS snapshot FROM invitations WHERE id = ?::uuid",
            fixture.invitationId,
        )
        assertEquals(before["state"], after["state"])
        assertEquals(before["version"], after["version"])
        assertEquals(before["snapshot"], after["snapshot"])
    }

    @Test
    fun `hiring plan remains snapshot-bound when an invitation is superseded`() {
        val company = seedActor("employer")
        val candidate = seedActor("candidate")
        makePublic(candidate)
        val plan = hiringPlan()
        val invitationId = createInvitation(company, candidate, plan)

        val preserved = supersede(company, invitationId, 1, null, includePlan = false)
        assertEquals(plan, objectMapper.convertValue(preserved.body!!.path("data").path("hiring_plan"), Map::class.java))

        val cleared = supersede(company, invitationId, 2, null, includePlan = true)
        assertTrue(cleared.body!!.path("data").path("hiring_plan").isNull)
        val oldSnapshot = jdbc.queryForObject(
            "SELECT snapshot -> 'hiring_plan' FROM invitation_versions WHERE invitation_id = ?::uuid AND version = 1",
            String::class.java,
            invitationId,
        )
        assertTrue(oldSnapshot!!.contains("API design"))
    }

    @Test
    fun `next step is hidden before acceptance and from other participants`() {
        val company = seedActor("employer")
        val candidate = seedActor("candidate")
        val otherCompany = seedActor("employer")
        val otherCandidate = seedActor("candidate")
        makePublic(candidate)
        val pendingId = createInvitation(company, candidate)
        assertEquals(404, request(company, HttpMethod.GET, nextStepPath(pendingId)).statusCode.value())
        assertEquals(404, request(candidate, HttpMethod.GET, nextStepPath(pendingId)).statusCode.value())

        accept(candidate, pendingId)
        assertEquals(404, request(otherCompany, HttpMethod.GET, nextStepPath(pendingId)).statusCode.value())
        assertEquals(404, request(otherCandidate, HttpMethod.GET, nextStepPath(pendingId)).statusCode.value())
        assertEquals(404, request(otherCompany, HttpMethod.PUT, nextStepPath(pendingId), nextStepBody(0, "Forbidden"), UUID.randomUUID().toString()).statusCode.value())
    }

    @Test
    fun `unverified owning employer cannot update a next step`() {
        val fixture = acceptedInvitation()
        jdbc.update("UPDATE users SET email_verified_at = NULL WHERE id = ?::uuid", fixture.company.id)

        val response = request(
            fixture.company,
            HttpMethod.PUT,
            nextStepPath(fixture.invitationId),
            nextStepBody(0, "Schedule the call"),
            UUID.randomUUID().toString(),
        )

        assertEquals(422, response.statusCode.value())
        assertEquals("EMAIL_UNCONFIRMED", response.body!!.path("error").path("code").asText())
    }

    @Test
    fun `out-of-range next-step version is rejected and maximum version cannot overflow`() {
        val fixture = acceptedInvitation()
        val firstWrite = request(
            fixture.company,
            HttpMethod.PUT,
            nextStepPath(fixture.invitationId),
            nextStepBody(0, "Version one"),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, firstWrite.statusCode.value(), firstWrite.body.toString())

        val wrappedVersion = nextStepBody(4_294_967_297L, "Must not wrap to version one")
        val invalid = request(
            fixture.company,
            HttpMethod.PUT,
            nextStepPath(fixture.invitationId),
            wrappedVersion,
            UUID.randomUUID().toString(),
        )
        assertEquals(400, invalid.statusCode.value(), invalid.body.toString())
        assertEquals(1, nextStepVersion(fixture.invitationId))

        jdbc.update(
            "UPDATE invitation_next_steps SET version = ? WHERE invitation_id = ?::uuid",
            Int.MAX_VALUE,
            fixture.invitationId,
        )
        val exhausted = request(
            fixture.company,
            HttpMethod.PUT,
            nextStepPath(fixture.invitationId),
            nextStepBody(Int.MAX_VALUE, "Must not overflow the version"),
            UUID.randomUUID().toString(),
        )
        assertEquals(409, exhausted.statusCode.value(), exhausted.body.toString())
        assertEquals("INVALID_STATE", exhausted.body!!.path("error").path("code").asText())
        assertEquals(Int.MAX_VALUE, nextStepVersion(fixture.invitationId))
    }

    @Test
    fun `blank plan stages and malformed due timestamps are rejected`() {
        val company = seedActor("employer")
        val candidate = seedActor("candidate")
        makePublic(candidate)
        val invalidPlan = mapOf(
            "stages" to listOf(mapOf("title" to "   ", "effort_minutes" to 30, "criteria" to "Must explain the design")),
            "response_time_days" to 3,
        )
        val createBody = mutableMapOf<String, Any?>(
            "candidate_id" to candidate.id,
            "need_id" to null,
            "salary" to salary(),
            "description" to "Backend role",
            "contact_method" to "platform",
            "company_comment" to null,
            "discussion_topics" to emptyList<String>(),
            "hiring_plan" to invalidPlan,
        )
        val invalidCreate = request(company, HttpMethod.POST, "/api/v1/invitations", createBody, UUID.randomUUID().toString())
        assertEquals(400, invalidCreate.statusCode.value(), invalidCreate.body.toString())

        val fixture = acceptedInvitation()
        val malformedDate = nextStepBody(0, "Schedule the call") + ("due_at" to "next Tuesday")
        val invalidWrite = request(
            fixture.company,
            HttpMethod.PUT,
            nextStepPath(fixture.invitationId),
            malformedDate,
            UUID.randomUUID().toString(),
        )
        assertEquals(400, invalidWrite.statusCode.value(), invalidWrite.body.toString())
    }

    private fun acceptedInvitation(): Fixture {
        val company = seedActor("employer")
        val candidate = seedActor("candidate")
        makePublic(candidate)
        val invitationId = createInvitation(company, candidate)
        accept(candidate, invitationId)
        return Fixture(company, candidate, invitationId)
    }

    private fun createAndAccept(company: TestActor, candidate: TestActor): String {
        val id = createInvitation(company, candidate)
        accept(candidate, id)
        return id
    }

    private fun createInvitation(company: TestActor, candidate: TestActor, plan: Map<String, Any?>? = null): String {
        val body = mutableMapOf<String, Any?>(
            "candidate_id" to candidate.id,
            "need_id" to null,
            "salary" to salary(),
            "description" to "Backend role",
            "contact_method" to "platform",
            "company_comment" to null,
            "discussion_topics" to emptyList<String>(),
        )
        if (plan != null) body["hiring_plan"] = plan
        val created = request(
            company,
            HttpMethod.POST,
            "/api/v1/invitations",
            body,
            UUID.randomUUID().toString(),
        )
        assertEquals(201, created.statusCode.value(), created.body.toString())
        return created.body!!.path("data").path("id").asText()
    }

    private fun accept(candidate: TestActor, invitationId: String) {
        val accepted = request(
            candidate,
            HttpMethod.POST,
            "/api/v1/invitations/$invitationId/decision",
            mapOf("expected_version" to 1, "action" to "accept"),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, accepted.statusCode.value(), accepted.body.toString())
    }

    private fun supersede(
        company: TestActor,
        invitationId: String,
        version: Int,
        plan: Map<String, Any?>?,
        includePlan: Boolean,
    ) = request(
        company,
        HttpMethod.POST,
        "/api/v1/invitations/$invitationId/supersede",
        buildMap {
            put("expected_version", version)
            put("salary", salary())
            put("description", "Updated backend role")
            put("contact_method", "platform")
            put("company_comment", null)
            put("discussion_topics", emptyList<String>())
            if (includePlan) put("hiring_plan", plan)
        },
        UUID.randomUUID().toString(),
    ).also { assertEquals(200, it.statusCode.value(), it.body.toString()) }

    private fun hiringPlan() = mapOf(
        "stages" to listOf(mapOf("title" to "Architecture discussion", "effort_minutes" to 45, "criteria" to "API design")),
        "response_time_days" to 5,
    )

    private fun nextStepBody(version: Int, action: String) = mapOf(
        "expected_version" to version,
        "action" to action,
        "responsible_party" to "company",
        "due_at" to null,
        "completed" to false,
    )

    private fun nextStepBody(version: Long, action: String) = mapOf(
        "expected_version" to version,
        "action" to action,
        "responsible_party" to "company",
        "due_at" to null,
        "completed" to false,
    )

    private fun nextStepVersion(invitationId: String) = jdbc.queryForObject(
        "SELECT version FROM invitation_next_steps WHERE invitation_id = ?::uuid",
        Int::class.java,
        invitationId,
    )!!

    private fun makePublic(candidate: TestActor) {
        jdbc.update(
            "UPDATE candidate_profiles SET visibility = 'public', data = ?::jsonb WHERE user_id = ?::uuid",
            """{"display_name":"Visible candidate","declared_grade":"junior","allowed_work_modes":["remote"],"cities":[],"max_weekly_hours":40,"skills":[]}""",
            candidate.id,
        )
    }

    private fun salary() = mapOf(
        "min_kopecks" to 100_000,
        "max_kopecks" to 300_000,
        "currency" to "RUB",
        "period" to "month",
        "basis" to "gross",
        "workload_type" to "full_time",
        "hours_per_week" to 40,
    )

    private fun nextStepPath(invitationId: String) = "/api/v1/invitations/$invitationId/next-step"

    private data class Fixture(val company: TestActor, val candidate: TestActor, val invitationId: String)
}
