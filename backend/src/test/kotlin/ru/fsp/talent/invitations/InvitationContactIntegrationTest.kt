package ru.fsp.talent.invitations

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.SessionSupport
import ru.fsp.talent.core.TestActor
import java.util.UUID
import java.util.concurrent.CompletableFuture

class InvitationContactIntegrationTest : DbTestBase() {
    @Test
    fun `public profile omits contacts and is unavailable after hiding`() {
        val (company, candidate, email) = fixture()
        val path = "/api/v1/candidates/${candidate.id}"
        val visible = request(company, HttpMethod.GET, path)
        assertEquals(200, visible.statusCode.value())
        assertEquals(candidate.id, visible.body!!.path("data").path("id").asText())
        assertFalse(visible.body.toString().contains(email))
        assertFalse(visible.body!!.path("data").has("contacts"))
        assertEquals(401, request(method = HttpMethod.GET, path = path).statusCode.value())
        jdbc.update("UPDATE candidate_profiles SET visibility='hidden' WHERE user_id=?::uuid", candidate.id)
        assertEquals(404, request(company, HttpMethod.GET, path).statusCode.value())
    }

    @Test
    fun `accept grants contacts and revoke blocks contact route and a later PDF`() {
        val (company, candidate, candidateEmail) = fixture()
        val created = createInvitation(company, candidate)
        val id = created.path("data").path("id").asText()

        assertEquals(404, request(company, HttpMethod.GET, "/api/v1/candidates/${candidate.id}/contacts").statusCode.value())
        val accepted = request(
            candidate,
            HttpMethod.POST,
            "/api/v1/invitations/$id/decision",
            mapOf("expected_version" to 1, "action" to "accept"),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, accepted.statusCode.value())
        assertTrue(accepted.body!!.path("data").path("contact_grant_id").isTextual)

        val contactRead = request(company, HttpMethod.GET, "/api/v1/candidates/${candidate.id}/contacts")
        assertEquals(200, contactRead.statusCode.value())
        assertEquals(candidateEmail, contactRead.body!!.path("data").path("channels").get(0).path("value").asText())
        assertEquals(1, contactRead.body!!.path("data").path("channels").size())

        val grantId = accepted.body!!.path("data").path("contact_grant_id").asText()
        assertEquals(
            200,
            request(actor = candidate, method = HttpMethod.POST, path = "/api/v1/me/contact-grants/$grantId/revoke", idempotencyKey = UUID.randomUUID().toString())
                .statusCode.value(),
        )
        assertEquals(404, request(company, HttpMethod.GET, "/api/v1/candidates/${candidate.id}/contacts").statusCode.value())

        val pdf = rawPdf(company, candidate.id)
        assertEquals(200, pdf.statusCode.value())
        assertEquals("private, no-store", pdf.headers.getFirst("Cache-Control"))
        val pdfText = Loader.loadPDF(pdf.body!!).use { PDFTextStripper().getText(it) }
        assertFalse(pdfText.contains(candidateEmail))

        jdbc.update("UPDATE candidate_profiles SET visibility = 'hidden' WHERE user_id = ?::uuid", candidate.id)
        val ownPdf = rawOwnPdf(candidate)
        assertEquals(200, ownPdf.statusCode.value())
        val ownText = Loader.loadPDF(ownPdf.body!!).use { PDFTextStripper().getText(it) }
        assertTrue(ownText.contains(candidateEmail))
    }

    @Test
    fun `superseded invitation cannot be accepted at the older version`() {
        val (company, candidate) = fixture()
        val created = createInvitation(company, candidate)
        val id = created.path("data").path("id").asText()
        val superseded = request(
            company,
            HttpMethod.POST,
            "/api/v1/invitations/$id/supersede",
            mapOf(
                "expected_version" to 1,
                "salary" to salary(),
                "description" to "Updated conditions",
                "contact_method" to "platform",
                "company_comment" to null,
                "discussion_topics" to emptyList<String>(),
            ),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, superseded.statusCode.value())
        assertEquals(2, superseded.body!!.path("data").path("version").asInt())

        val stale = request(
            candidate,
            HttpMethod.POST,
            "/api/v1/invitations/$id/decision",
            mapOf("expected_version" to 1, "action" to "accept"),
            UUID.randomUUID().toString(),
        )
        assertEquals(409, stale.statusCode.value())
        assertEquals("VERSION_CONFLICT", stale.body!!.path("error").path("code").asText())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM contact_grants WHERE candidate_id = ?::uuid", Int::class.java, candidate.id))
    }

    @Test
    fun `accepted new terms need a new response and reuse the active pair grant`() {
        val (company, candidate) = fixture()
        val first = createInvitation(company, candidate)
        val firstId = first.path("data").path("id").asText()
        val acceptedFirst = request(
            candidate,
            HttpMethod.POST,
            "/api/v1/invitations/$firstId/decision",
            mapOf("expected_version" to 1, "action" to "accept"),
            UUID.randomUUID().toString(),
        )
        val priorGrant = acceptedFirst.body!!.path("data").path("contact_grant_id").asText()

        val newTerms = createInvitation(company, candidate)
        assertEquals("pending", newTerms.path("data").path("state").asText())
        val pendingGrant = newTerms.path("data").path("contact_grant_id")
        assertTrue(pendingGrant.isMissingNode || pendingGrant.isNull)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM contact_grants WHERE candidate_id = ?::uuid AND revoked_at IS NULL", Int::class.java, candidate.id))

        val acceptedNewTerms = request(
            candidate,
            HttpMethod.POST,
            "/api/v1/invitations/${newTerms.path("data").path("id").asText()}/decision",
            mapOf("expected_version" to 1, "action" to "accept"),
            UUID.randomUUID().toString(),
        )
        assertEquals(priorGrant, acceptedNewTerms.body!!.path("data").path("contact_grant_id").asText())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM contact_grants WHERE candidate_id = ?::uuid AND revoked_at IS NULL", Int::class.java, candidate.id))
    }

    @Test
    fun `candidate accept and company withdraw serialize to one terminal state`() {
        val (company, candidate) = fixture()
        val id = createInvitation(company, candidate).path("data").path("id").asText()
        val accept = CompletableFuture.supplyAsync {
            request(candidate, HttpMethod.POST, "/api/v1/invitations/$id/decision", mapOf("expected_version" to 1, "action" to "accept"), UUID.randomUUID().toString())
        }
        val withdraw = CompletableFuture.supplyAsync {
            request(company, HttpMethod.POST, "/api/v1/invitations/$id/withdraw", mapOf("expected_version" to 1), UUID.randomUUID().toString())
        }
        val results = listOf(accept.join(), withdraw.join())
        assertEquals(1, results.count { it.statusCode.value() == 200 })
        assertEquals(1, results.count { it.statusCode.value() == 409 })
        val state = jdbc.queryForObject("SELECT state FROM invitations WHERE id = ?::uuid", String::class.java, id)
        val grants = jdbc.queryForObject("SELECT count(*) FROM contact_grants WHERE candidate_id = ?::uuid", Int::class.java, candidate.id)
        assertTrue(state == "accepted" || state == "withdrawn")
        assertEquals(if (state == "accepted") 1 else 0, grants)
    }

    @Test
    fun `invitation key replays the result and rejects a different body`() {
        val (company, candidate) = fixture()
        val key = UUID.randomUUID().toString()
        val body = invitationBody(candidate.id)
        val first = request(company, HttpMethod.POST, "/api/v1/invitations", body, key)
        val replay = request(company, HttpMethod.POST, "/api/v1/invitations", body, key)
        assertEquals(201, first.statusCode.value())
        assertEquals(201, replay.statusCode.value())
        assertEquals(first.body!!.path("data").path("id").asText(), replay.body!!.path("data").path("id").asText())

        val changed = body + ("description" to "Different offer")
        val conflict = request(company, HttpMethod.POST, "/api/v1/invitations", changed, key)
        assertEquals(409, conflict.statusCode.value())
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.body!!.path("error").path("code").asText())
    }

    @Test
    fun `decline cooldown blocks a new direct invitation after a need linked one`() {
        val (company, candidate) = fixture()
        val needId = UUID.randomUUID().toString()
        jdbc.update(
            """INSERT INTO employer_needs(id, company_id, status, data)
               VALUES (?::uuid, ?::uuid, 'active', ?::jsonb)""",
            needId,
            company.companyId,
            """{"target_grade":"junior","workload_hours_per_week":40,"requirements":[]}""",
        )
        val linked = createInvitation(company, candidate, needId)
        val invitationId = linked.path("data").path("id").asText()
        val decline = request(
            candidate,
            HttpMethod.POST,
            "/api/v1/invitations/$invitationId/decision",
            mapOf("expected_version" to 1, "action" to "decline"),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, decline.statusCode.value())

        val next = request(company, HttpMethod.POST, "/api/v1/invitations", invitationBody(candidate.id), UUID.randomUUID().toString())
        assertEquals(409, next.statusCode.value())
        assertEquals("INVITATION_COOLDOWN", next.body!!.path("error").path("code").asText())
        assertTrue(next.body!!.path("error").path("eligible_at").isTextual)
    }

    private fun fixture(): Triple<TestActor, TestActor, String> {
        val company = seedActor("employer")
        val candidate = seedActor("candidate")
        val email = "visible-${UUID.randomUUID()}@example.test"
        jdbc.update("UPDATE companies SET data = ?::jsonb WHERE id = ?::uuid", """{"name":"Example employer"}""", company.companyId)
        jdbc.update(
            """UPDATE candidate_profiles SET visibility = 'public', data = ?::jsonb, contacts = ?::jsonb WHERE user_id = ?::uuid""",
            """{"display_name":"Visible candidate","declared_grade":"junior","allowed_work_modes":["remote"],"cities":[],"max_weekly_hours":40,"skills":[]}""",
            """{"channels":[{"type":"email","value":"$email","enabled":true},{"type":"phone","value":"+70000000000","enabled":false}]}""",
            candidate.id,
        )
        return Triple(company, candidate, email)
    }

    private fun createInvitation(company: TestActor, candidate: TestActor, needId: String? = null) = request(
        company,
        HttpMethod.POST,
        "/api/v1/invitations",
        invitationBody(candidate.id, needId),
        UUID.randomUUID().toString(),
    ).also { assertEquals(201, it.statusCode.value(), it.body.toString()) }.body!!

    private fun invitationBody(candidateId: String, needId: String? = null) = mapOf(
        "candidate_id" to candidateId,
        "need_id" to needId,
        "salary" to salary(),
        "description" to "Backend role",
        "contact_method" to "platform",
        "company_comment" to null,
        "discussion_topics" to emptyList<String>(),
    )

    private fun salary() = mapOf(
        "min_kopecks" to 100_000,
        "max_kopecks" to 300_000,
        "currency" to "RUB",
        "period" to "month",
        "basis" to "gross",
        "workload_type" to "full_time",
        "hours_per_week" to 40,
    )

    private fun rawPdf(actor: TestActor, candidateId: String) = http.exchange(
        "http://localhost:$port/api/v1/candidates/$candidateId/profile.pdf",
        HttpMethod.GET,
        HttpEntity<ByteArray>(HttpHeaders().apply {
            accept = listOf(MediaType.APPLICATION_PDF)
            set("Cookie", "${SessionSupport.COOKIE_NAME}=${actor.sessionCookie}")
        }),
        ByteArray::class.java,
    )

    private fun rawOwnPdf(actor: TestActor) = http.exchange(
        "http://localhost:$port/api/v1/me/profile.pdf",
        HttpMethod.GET,
        HttpEntity<ByteArray>(HttpHeaders().apply {
            accept = listOf(MediaType.APPLICATION_PDF)
            set("Cookie", "${SessionSupport.COOKIE_NAME}=${actor.sessionCookie}")
        }),
        ByteArray::class.java,
    )
}
