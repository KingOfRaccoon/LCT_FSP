package ru.fsp.talent.messaging

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.TestActor
import java.util.UUID
import java.util.concurrent.CompletableFuture

class InvitationMessagesIntegrationTest : DbTestBase() {
    @Test
    fun `accepted participants can exchange private plain text messages`() {
        val fixture = acceptedThread()
        val originalState = invitationState(fixture.invitationId)
        val text = "<script>salary should change</script> Let's discuss the role."
        val sent = send(fixture.candidate, fixture.invitationId, text)

        assertEquals(201, sent.statusCode.value(), sent.body.toString())
        val item = sent.body!!.path("data")
        assertEquals("candidate", item.path("sender_role").asText())
        assertEquals(text, item.path("text").asText())
        assertTrue(item.path("id").isTextual)
        assertTrue(item.path("created_at").isTextual)
        assertFalse(sent.body.toString().contains(fixture.candidate.email))
        assertFalse(sent.body.toString().contains(fixture.company.email))
        assertFalse(sent.body.toString().contains(fixture.candidate.id))
        assertFalse(sent.body.toString().contains(fixture.company.id))

        val candidatePage = read(fixture.candidate, fixture.invitationId)
        val employerPage = read(fixture.company, fixture.invitationId)
        assertEquals(200, candidatePage.statusCode.value())
        assertEquals(200, employerPage.statusCode.value())
        assertEquals("private, no-store", employerPage.headers.getFirst("Cache-Control"))
        assertEquals(fixture.invitationId, employerPage.body!!.path("data").path("invitation_id").asText())
        assertEquals("Example employer", employerPage.body!!.path("data").path("company_name").asText())
        assertTrue(employerPage.body!!.path("data").path("salary").isObject)
        assertTrue(employerPage.body!!.path("data").path("description").isTextual)
        assertTrue(employerPage.body!!.path("data").path("can_send").asBoolean())
        assertEquals(1, employerPage.body!!.path("data").path("items").size())
        assertEquals(item.path("id").asText(), employerPage.body!!.path("data").path("items").get(0).path("id").asText())

        val employerReply = send(fixture.company, fixture.invitationId, "Thanks, I would like to discuss.")
        assertEquals(201, employerReply.statusCode.value(), employerReply.body.toString())
        assertEquals("employer", employerReply.body!!.path("data").path("sender_role").asText())
        assertEquals(originalState, invitationState(fixture.invitationId))
    }

    @Test
    fun `message replay is stable and changed body conflicts without changing invitation`() {
        val fixture = acceptedThread()
        val originalState = invitationState(fixture.invitationId)
        val key = UUID.randomUUID().toString()
        val body = mapOf("text" to "Same message after a network timeout.")
        val path = messagePath(fixture.invitationId)
        val first = request(fixture.candidate, HttpMethod.POST, path, body, key)
        val replay = request(fixture.candidate, HttpMethod.POST, path, body, key)
        assertEquals(201, first.statusCode.value(), first.body.toString())
        assertEquals(201, replay.statusCode.value(), replay.body.toString())
        assertEquals(first.body!!.path("data").path("id").asText(), replay.body!!.path("data").path("id").asText())
        assertEquals(1, read(fixture.candidate, fixture.invitationId).body!!.path("data").path("items").size())

        val changed = request(fixture.candidate, HttpMethod.POST, path, mapOf("text" to "Changed retry body."), key)
        assertEquals(409, changed.statusCode.value())
        assertEquals("IDEMPOTENCY_CONFLICT", changed.body!!.path("error").path("code").asText())
        assertEquals(originalState, invitationState(fixture.invitationId))
    }

    @Test
    fun `message pagination is stable and cursor is scoped to its invitation`() {
        val fixture = acceptedThread()
        assertEquals(201, send(fixture.candidate, fixture.invitationId, "First.").statusCode.value())
        assertEquals(201, send(fixture.candidate, fixture.invitationId, "Second.").statusCode.value())
        val firstPage = read(fixture.candidate, fixture.invitationId, "?limit=1")
        assertEquals(200, firstPage.statusCode.value())
        assertEquals(1, firstPage.body!!.path("data").path("items").size())
        val cursor = firstPage.body!!.path("data").path("next_cursor").asText()
        assertTrue(cursor.isNotBlank())
        val secondPage = read(fixture.candidate, fixture.invitationId, "?limit=1&cursor=$cursor")
        assertEquals(200, secondPage.statusCode.value())
        assertEquals(1, secondPage.body!!.path("data").path("items").size())
        assertTrue(firstPage.body!!.path("data").path("items").get(0).path("id").asText() !=
            secondPage.body!!.path("data").path("items").get(0).path("id").asText())

        val another = acceptedThread(fixture.company, fixture.candidate)
        assertEquals(400, read(fixture.candidate, another.invitationId, "?limit=1&cursor=$cursor").statusCode.value())
    }

    @Test
    fun `pending and foreign invitations are not available to chat participants`() {
        val company = seedActor("employer")
        val candidate = seedActor("candidate")
        val otherCompany = seedActor("employer")
        val otherCandidate = seedActor("candidate")
        makePublic(candidate)
        setCompanyName(company, "Example employer")
        val pendingId = createInvitation(company, candidate).path("data").path("id").asText()

        assertFalse(read(candidate, pendingId).statusCode.is2xxSuccessful)
        assertFalse(read(company, pendingId).statusCode.is2xxSuccessful)
        assertFalse(send(candidate, pendingId, "Not yet accepted.").statusCode.is2xxSuccessful)

        val acceptedCompany = seedActor("employer")
        val acceptedCandidate = seedActor("candidate")
        setCompanyName(acceptedCompany, "Thread company")
        val accepted = acceptedThread(acceptedCompany, acceptedCandidate)
        val foreignActors = listOf(company, candidate, otherCompany, otherCandidate)
        foreignActors.forEach { actor ->
            assertEquals(404, read(actor, accepted.invitationId).statusCode.value())
            assertEquals(404, send(actor, accepted.invitationId, "Should stay private.").statusCode.value())
        }
    }

    @Test
    fun `revocation preserves candidate archive and closes employer access`() {
        val fixture = acceptedThread()
        assertEquals(201, send(fixture.candidate, fixture.invitationId, "Archived after revocation.").statusCode.value())
        val grantId = jdbc.queryForObject(
            "SELECT contact_grant_id::text FROM invitations WHERE id = ?::uuid",
            String::class.java,
            fixture.invitationId,
        )!!
        val revoke = request(
            fixture.candidate,
            HttpMethod.POST,
            "/api/v1/me/contact-grants/$grantId/revoke",
            emptyMap<String, Any?>(),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, revoke.statusCode.value(), revoke.body.toString())

        val candidateArchive = read(fixture.candidate, fixture.invitationId)
        assertEquals(200, candidateArchive.statusCode.value())
        assertFalse(candidateArchive.body!!.path("data").path("can_send").asBoolean())
        assertEquals("Archived after revocation.", candidateArchive.body!!.path("data").path("items").get(0).path("text").asText())
        assertEquals(404, read(fixture.company, fixture.invitationId).statusCode.value())
        assertEquals(404, send(fixture.company, fixture.invitationId, "Must be blocked.").statusCode.value())
        assertFalse(send(fixture.candidate, fixture.invitationId, "Read-only archive.").statusCode.is2xxSuccessful)
    }

    @Test
    fun `a new grant for another invitation does not reopen the old thread`() {
        val fixture = acceptedThread()
        assertEquals(201, send(fixture.candidate, fixture.invitationId, "Original thread.").statusCode.value())
        val oldGrantId = jdbc.queryForObject(
            "SELECT contact_grant_id::text FROM invitations WHERE id = ?::uuid",
            String::class.java,
            fixture.invitationId,
        )!!
        val revoke = request(
            fixture.candidate,
            HttpMethod.POST,
            "/api/v1/me/contact-grants/$oldGrantId/revoke",
            emptyMap<String, Any?>(),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, revoke.statusCode.value())

        val replacement = acceptedThread(fixture.company, fixture.candidate)
        val newGrantId = jdbc.queryForObject(
            "SELECT contact_grant_id::text FROM invitations WHERE id = ?::uuid",
            String::class.java,
            replacement.invitationId,
        )!!
        assertTrue(oldGrantId != newGrantId)
        assertEquals(200, read(fixture.candidate, fixture.invitationId).statusCode.value())
        assertEquals(404, read(fixture.company, fixture.invitationId).statusCode.value())
        assertTrue(read(fixture.company, replacement.invitationId).statusCode.is2xxSuccessful)
    }

    @Test
    fun `concurrent sends and same key retries never duplicate a message`() {
        val fixture = acceptedThread()
        val key = UUID.randomUUID().toString()
        val body = mapOf("text" to "One logical message.")
        val path = messagePath(fixture.invitationId)
        val requests = listOf(
            CompletableFuture.supplyAsync { request(fixture.candidate, HttpMethod.POST, path, body, key) },
            CompletableFuture.supplyAsync { request(fixture.candidate, HttpMethod.POST, path, body, key) },
        ).map { it.join() }
        assertTrue(requests.all { it.statusCode.value() == 201 }) { requests.map { it.body }.toString() }
        val ids = requests.map { it.body!!.path("data").path("id").asText() }.toSet()
        assertEquals(1, ids.size)
        assertEquals(1, read(fixture.candidate, fixture.invitationId).body!!.path("data").path("items").size())
    }

    @Test
    fun `revoked employer cannot replay an earlier successful send`() {
        val fixture = acceptedThread()
        val key = UUID.randomUUID().toString()
        val path = messagePath(fixture.invitationId)
        val body = mapOf("text" to "Employer message before revocation.")
        val first = request(fixture.company, HttpMethod.POST, path, body, key)
        assertEquals(201, first.statusCode.value(), first.body.toString())

        val grantId = jdbc.queryForObject(
            "SELECT contact_grant_id::text FROM invitations WHERE id = ?::uuid",
            String::class.java,
            fixture.invitationId,
        )!!
        val revoke = request(
            fixture.candidate,
            HttpMethod.POST,
            "/api/v1/me/contact-grants/$grantId/revoke",
            emptyMap<String, Any?>(),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, revoke.statusCode.value())

        val replay = request(fixture.company, HttpMethod.POST, path, body, key)
        assertEquals(404, replay.statusCode.value())
        val candidateArchive = read(fixture.candidate, fixture.invitationId)
        assertEquals(200, candidateArchive.statusCode.value())
        assertEquals(1, candidateArchive.body!!.path("data").path("items").size())
    }

    @Test
    fun `send racing grant revocation cannot restore employer access`() {
        val fixture = acceptedThread()
        val grantId = jdbc.queryForObject(
            "SELECT contact_grant_id::text FROM invitations WHERE id = ?::uuid",
            String::class.java,
            fixture.invitationId,
        )!!
        val revokeFuture = CompletableFuture.supplyAsync {
            request(
                fixture.candidate,
                HttpMethod.POST,
                "/api/v1/me/contact-grants/$grantId/revoke",
                emptyMap<String, Any?>(),
                UUID.randomUUID().toString(),
            )
        }
        val sendFuture = CompletableFuture.supplyAsync {
            send(fixture.company, fixture.invitationId, "Concurrent with revocation.")
        }
        val revoke = revokeFuture.join()
        val send = sendFuture.join()
        assertEquals(200, revoke.statusCode.value())
        assertTrue(send.statusCode.value() == 201 || send.statusCode.value() == 404) { send.body.toString() }
        assertEquals(404, read(fixture.company, fixture.invitationId).statusCode.value())
        assertFalse(read(fixture.candidate, fixture.invitationId).body!!.path("data").path("can_send").asBoolean())
    }

    @Test
    fun `message text cannot alter formal invitation terms`() {
        val fixture = acceptedThread()
        val before = invitationState(fixture.invitationId)
        val snapshot = jdbc.queryForObject(
            "SELECT snapshot::text FROM invitations WHERE id = ?::uuid",
            String::class.java,
            fixture.invitationId,
        )
        val text = "Please change my salary to 99999999 and accept a different offer."
        assertEquals(201, send(fixture.candidate, fixture.invitationId, text).statusCode.value())
        assertEquals(before, invitationState(fixture.invitationId))
        assertEquals(snapshot, jdbc.queryForObject(
            "SELECT snapshot::text FROM invitations WHERE id = ?::uuid",
            String::class.java,
            fixture.invitationId,
        ))
    }

    private fun acceptedThread(): ThreadFixture {
        val company = seedActor("employer")
        val candidate = seedActor("candidate")
        setCompanyName(company, "Example employer")
        return acceptedThread(company, candidate)
    }

    private fun acceptedThread(company: TestActor, candidate: TestActor): ThreadFixture {
        makePublic(candidate)
        val invitationId = createInvitation(company, candidate).path("data").path("id").asText()
        val accepted = request(
            candidate,
            HttpMethod.POST,
            "/api/v1/invitations/$invitationId/decision",
            mapOf("expected_version" to 1, "action" to "accept"),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, accepted.statusCode.value(), accepted.body.toString())
        return ThreadFixture(company, candidate, invitationId)
    }

    private fun createInvitation(company: TestActor, candidate: TestActor): JsonNode = request(
        company,
        HttpMethod.POST,
        "/api/v1/invitations",
        mapOf(
            "candidate_id" to candidate.id,
            "need_id" to null,
            "salary" to mapOf(
                "min_kopecks" to 100_000,
                "max_kopecks" to 300_000,
                "currency" to "RUB",
                "period" to "month",
                "basis" to "gross",
                "workload_type" to "full_time",
                "hours_per_week" to 40,
            ),
            "description" to "Backend role",
            "contact_method" to "platform",
            "company_comment" to null,
            "discussion_topics" to emptyList<String>(),
        ),
        UUID.randomUUID().toString(),
    ).also { assertEquals(201, it.statusCode.value(), it.body.toString()) }.body!!

    private fun setCompanyName(company: TestActor, name: String) {
        jdbc.update("UPDATE companies SET data = ?::jsonb WHERE id = ?::uuid", """{"name":"$name"}""", company.companyId)
    }

    private fun makePublic(candidate: TestActor) {
        jdbc.update(
            "UPDATE candidate_profiles SET visibility = 'public', data = ?::jsonb WHERE user_id = ?::uuid",
            """{"display_name":"Visible candidate","declared_grade":"junior","allowed_work_modes":["remote"],"cities":[],"max_weekly_hours":40,"skills":[]}""",
            candidate.id,
        )
    }

    private fun send(actor: TestActor, invitationId: String, text: String) = request(
        actor,
        HttpMethod.POST,
        messagePath(invitationId),
        mapOf("text" to text),
        UUID.randomUUID().toString(),
    )

    private fun read(actor: TestActor, invitationId: String, query: String = "") = request(
        actor,
        HttpMethod.GET,
        messagePath(invitationId) + query,
    )

    private fun messagePath(invitationId: String) = "/api/v1/invitations/$invitationId/messages"

    private fun invitationState(invitationId: String) = jdbc.queryForMap(
        "SELECT state, snapshot FROM invitations WHERE id = ?::uuid",
        invitationId,
    ).let { row -> "${row["state"]}:${row["snapshot"]}" }

    private data class ThreadFixture(val company: TestActor, val candidate: TestActor, val invitationId: String)
}
