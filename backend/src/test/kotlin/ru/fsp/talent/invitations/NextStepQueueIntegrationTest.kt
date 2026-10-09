package ru.fsp.talent.invitations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.TestActor
import java.time.Instant
import java.util.UUID

class NextStepQueueIntegrationTest : DbTestBase() {
    @Test
    fun `both participants read current shared steps in a paged queue`() {
        val fixture = acceptedWithStep("company", "2026-10-12T10:00:00Z")

        val companyResponse = request(fixture.company, HttpMethod.GET, queuePath())
        assertEquals(200, companyResponse.statusCode.value(), companyResponse.body.toString())
        assertEquals("private, no-store", companyResponse.headers.getFirst(HttpHeaders.CACHE_CONTROL))
        val companyPage = companyResponse.body!!.path("data")
        assertEquals(5, companyPage.path("limit").asInt())
        assertTrue(companyPage.path("next_cursor").isNull)
        assertEquals(1, companyPage.path("items").size())
        assertQueueItem(companyPage.path("items").first(), fixture, true)

        val candidateResponse = request(fixture.candidate, HttpMethod.GET, queuePath())
        assertEquals(200, candidateResponse.statusCode.value(), candidateResponse.body.toString())
        assertQueueItem(candidateResponse.body!!.path("data").path("items").first(), fixture, false)
    }

    @Test
    fun `queue order groups my actions then sorts due updated and invitation with null due last`() {
        val company = seedActor("employer")
        val candidate = seedActor("candidate")
        makePublic(candidate)
        val rows = listOf(
            createStep(company, candidate, "candidate", null, "2026-10-01T00:00:00Z"),
            createStep(company, candidate, "company", "2026-10-15T00:00:00Z", "2026-10-01T00:00:00Z"),
            createStep(company, candidate, "company", "2026-10-10T00:00:00Z", "2026-10-03T00:00:00Z"),
            createStep(company, candidate, "company", "2026-10-10T00:00:00Z", "2026-10-02T00:00:00Z"),
            createStep(company, candidate, "company", "2026-10-10T00:00:00Z", "2026-10-02T00:00:00Z"),
        )

        val response = request(company, HttpMethod.GET, queuePath(limit = 100))

        assertEquals(200, response.statusCode.value(), response.body.toString())
        val items = response.body!!.path("data").path("items")
        assertEquals(rows.subList(3, 5).map { it.invitationId }.sorted() + listOf(rows[2].invitationId, rows[1].invitationId, rows[0].invitationId),
            (0 until items.size()).map { items[it].path("invitation_id").asText() })
        assertEquals(listOf(true, true, true, true, false), (0 until items.size()).map { items[it].path("is_my_action").asBoolean() })
    }

    @Test
    fun `cursor pages deterministically and is bound to viewer and role`() {
        val fixture = acceptedWithStep("company", "2026-10-10T00:00:00Z")
        val second = createStep(fixture.company, fixture.candidate, "company", "2026-10-11T00:00:00Z", "2026-10-02T00:00:00Z")
        val third = createStep(fixture.company, fixture.candidate, "company", "2026-10-12T00:00:00Z", "2026-10-03T00:00:00Z")
        val waiting = createStep(fixture.company, fixture.candidate, "candidate", "2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z")

        val first = request(fixture.company, HttpMethod.GET, queuePath(limit = 2))
        assertEquals(200, first.statusCode.value(), first.body.toString())
        val firstData = first.body!!.path("data")
        assertEquals(listOf(fixture.invitationId, second.invitationId), (0 until 2).map { firstData.path("items")[it].path("invitation_id").asText() })
        val cursor = firstData.path("next_cursor").asText()
        assertTrue(cursor.isNotBlank())

        val next = request(fixture.company, HttpMethod.GET, queuePath(limit = 2, cursor = cursor))
        assertEquals(200, next.statusCode.value(), next.body.toString())
        assertEquals(listOf(third.invitationId, waiting.invitationId), (0 until next.body!!.path("data").path("items").size()).map {
            next.body!!.path("data").path("items")[it].path("invitation_id").asText()
        })
        assertTrue(next.body!!.path("data").path("next_cursor").isNull)

        val other = seedActor("employer")
        assertEquals(400, request(other, HttpMethod.GET, queuePath(limit = 2, cursor = cursor)).statusCode.value())
        assertEquals(400, request(fixture.company, HttpMethod.GET, queuePath(cursor = "not-a-cursor")).statusCode.value())
        assertEquals(400, request(fixture.company, HttpMethod.GET, queuePath(cursor = "x".repeat(513))).statusCode.value())
    }

    @Test
    fun `queue rejects invalid limits and employer without verified owner access`() {
        val owner = seedActor("employer")
        assertEquals(400, request(owner, HttpMethod.GET, queuePath(limit = 0)).statusCode.value())
        assertEquals(400, request(owner, HttpMethod.GET, queuePath(limit = 101)).statusCode.value())

        val unverified = seedActor("employer", verified = false)
        val unverifiedResponse = request(unverified, HttpMethod.GET, queuePath())
        assertEquals(422, unverifiedResponse.statusCode.value())
        assertEquals("EMAIL_UNCONFIRMED", unverifiedResponse.body!!.path("error").path("code").asText())

        val nonOwner = seedActor("employer")
        jdbc.update("UPDATE memberships SET role = 'member' WHERE user_id = ?::uuid AND company_id = ?::uuid", nonOwner.id, nonOwner.companyId)
        assertEquals(403, request(nonOwner, HttpMethod.GET, queuePath()).statusCode.value())
    }

    @Test
    fun `queue excludes incomplete and stale grant rows but preserves hidden candidate step`() {
        val company = seedActor("employer")
        val missingCandidate = publicCandidate()
        val missingStep = createAccepted(company, missingCandidate)
        val pendingCandidate = publicCandidate()
        val pendingId = createInvitation(company, pendingCandidate)
        val declinedCandidate = publicCandidate()
        val declinedId = createInvitation(company, declinedCandidate)
        val declined = request(declinedCandidate, HttpMethod.POST, "/api/v1/invitations/$declinedId/decision",
            mapOf("expected_version" to 1, "action" to "decline"), UUID.randomUUID().toString())
        assertEquals(200, declined.statusCode.value(), declined.body.toString())
        val completedCandidate = publicCandidate()
        val completed = createStep(company, completedCandidate, "company", null, "2026-10-01T00:00:00Z", completed = true)
        val archivedCandidate = publicCandidate()
        val archived = createStep(company, archivedCandidate, "company", null, "2026-10-02T00:00:00Z")
        val grantId = jdbc.queryForObject("SELECT contact_grant_id::text FROM invitations WHERE id = ?::uuid", String::class.java, archived.invitationId)!!
        assertEquals(200, request(archivedCandidate, HttpMethod.POST, "/api/v1/me/contact-grants/$grantId/revoke", emptyMap<String, Any?>(), UUID.randomUUID().toString()).statusCode.value())
        createAndAccept(company, archivedCandidate) // A new pair grant does not restore the archived invitation.
        val hiddenCandidate = publicCandidate()
        val hidden = createStep(company, hiddenCandidate, "candidate", null, "2026-10-03T00:00:00Z")
        jdbc.update("UPDATE candidate_profiles SET visibility = 'hidden' WHERE user_id = ?::uuid", hiddenCandidate.id)

        val response = request(company, HttpMethod.GET, queuePath(limit = 100))

        assertEquals(200, response.statusCode.value(), response.body.toString())
        val items = response.body!!.path("data").path("items")
        assertEquals(listOf(hidden.invitationId), (0 until items.size()).map { items[it].path("invitation_id").asText() })
        assertEquals("hidden", items.first().path("candidate_visibility").asText())
        assertTrue(items.first().path("candidate_display_name").isNull)
        assertFalse(items.first().path("step").path("completed").asBoolean())
        assertTrue(missingStep != pendingId && declinedId != pendingId && completed.invitationId != hidden.invitationId)
    }

    @Test
    fun `deleted candidate is excluded and public name is redacted and bounded`() {
        val fixture = acceptedWithStep("company", null)
        jdbc.update(
            "UPDATE candidate_profiles SET visibility = 'public', data = ?::jsonb WHERE user_id = ?::uuid",
            objectMapper.writeValueAsString(mapOf("display_name" to "Contact: test@example.test" + " C".repeat(100))),
            fixture.candidate.id,
        )
        val visible = request(fixture.company, HttpMethod.GET, queuePath())
        assertEquals(200, visible.statusCode.value())
        val item = visible.body!!.path("data").path("items").first()
        assertEquals("public", item.path("candidate_visibility").asText())
        assertTrue(item.path("candidate_display_name").asText().length <= 120)
        assertFalse(item.path("candidate_display_name").asText().contains("test@example.test"))
        assertTrue(item.path("step").path("due_at").isNull)

        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?::uuid", fixture.candidate.id)
        assertEquals(0, request(fixture.company, HttpMethod.GET, queuePath()).body!!.path("data").path("items").size())
    }

    private fun assertQueueItem(item: com.fasterxml.jackson.databind.JsonNode, fixture: Fixture, employerAction: Boolean) {
        assertEquals(7, item.size())
        assertEquals(6, item.path("step").size())
        assertEquals(fixture.invitationId, item.path("invitation_id").asText())
        assertEquals(1, item.path("invitation_version").asInt())
        assertEquals("Example company", item.path("company_name").asText())
        assertEquals("Visible candidate", item.path("candidate_display_name").asText())
        assertEquals("public", item.path("candidate_visibility").asText())
        assertEquals(employerAction, item.path("is_my_action").asBoolean())
        assertEquals("Schedule a call", item.path("step").path("action").asText())
        assertEquals("company", item.path("step").path("responsible_party").asText())
        assertEquals(Instant.parse("2026-10-12T10:00:00Z").toString(), item.path("step").path("due_at").asText())
    }

    private fun acceptedWithStep(responsibleParty: String, dueAt: String?): Fixture {
        val company = seedActor("employer")
        val candidate = publicCandidate()
        jdbc.update("UPDATE companies SET data = '{\"name\":\"Example company\"}'::jsonb WHERE id = ?::uuid", company.companyId)
        val invitationId = createAccepted(company, candidate)
        addStep(invitationId, "Schedule a call", responsibleParty, dueAt, "2026-10-01T00:00:00Z", false)
        return Fixture(company, candidate, invitationId)
    }

    private fun createStep(company: TestActor, candidate: TestActor, party: String, dueAt: String?, updatedAt: String, completed: Boolean = false): Fixture {
        val invitationId = createAccepted(company, candidate)
        addStep(invitationId, "Action for $party", party, dueAt, updatedAt, completed)
        return Fixture(company, candidate, invitationId)
    }

    private fun addStep(invitationId: String, action: String, party: String, dueAt: String?, updatedAt: String, completed: Boolean) {
        jdbc.update(
            """INSERT INTO invitation_next_steps(invitation_id, version, action, responsible_party, due_at, completed, updated_at)
               VALUES (?::uuid, 1, ?, ?, ?::timestamptz, ?, ?::timestamptz)""".trimIndent(),
            invitationId, action, party, dueAt, completed, updatedAt,
        )
    }

    private fun createAccepted(company: TestActor, candidate: TestActor): String {
        val id = createInvitation(company, candidate)
        val accepted = request(candidate, HttpMethod.POST, "/api/v1/invitations/$id/decision", mapOf("expected_version" to 1, "action" to "accept"), UUID.randomUUID().toString())
        assertEquals(200, accepted.statusCode.value(), accepted.body.toString())
        return id
    }

    private fun createAndAccept(company: TestActor, candidate: TestActor) = createAccepted(company, candidate)

    private fun createInvitation(company: TestActor, candidate: TestActor): String {
        val body = mapOf(
            "candidate_id" to candidate.id,
            "need_id" to null,
            "salary" to mapOf("min_kopecks" to 100_000, "max_kopecks" to 300_000, "currency" to "RUB", "period" to "month", "basis" to "gross", "workload_type" to "full_time", "hours_per_week" to 40),
            "description" to "Backend role",
            "contact_method" to "platform",
            "company_comment" to null,
            "discussion_topics" to emptyList<String>(),
        )
        val response = request(company, HttpMethod.POST, "/api/v1/invitations", body, UUID.randomUUID().toString())
        assertEquals(201, response.statusCode.value(), response.body.toString())
        return response.body!!.path("data").path("id").asText()
    }

    private fun makePublic(candidate: TestActor) {
        jdbc.update(
            "UPDATE candidate_profiles SET visibility = 'public', data = ?::jsonb WHERE user_id = ?::uuid",
            """{"display_name":"Visible candidate","declared_grade":"junior","allowed_work_modes":["remote"],"cities":[],"max_weekly_hours":40,"skills":[]}""",
            candidate.id,
        )
    }

    private fun publicCandidate(): TestActor = seedActor("candidate").also(::makePublic)

    private fun queuePath(limit: Int? = null, cursor: String? = null): String = buildString {
        append("/api/v1/me/next-steps")
        val params = buildList {
            limit?.let { add("limit=$it") }
            cursor?.let { add("cursor=$it") }
        }
        if (params.isNotEmpty()) append("?").append(params.joinToString("&"))
    }

    private data class Fixture(val company: TestActor, val candidate: TestActor, val invitationId: String)
}
