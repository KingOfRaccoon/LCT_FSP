package ru.fsp.talent.privacy

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

class PrivacyIntegrationTest : DbTestBase() {
    @Test
    fun `own hidden preview redacts future public fields but leaves own originals unchanged`() {
        val candidate = seedActor("candidate")
        val employer = seedActor("employer")
        val email = "private-${UUID.randomUUID()}@example.test"
        val phone = "+7 (921) 555-12-34"
        val original = mapOf(
            "display_name" to "Alice $email",
            "headline" to "Backend engineer; $phone",
            "summary" to "Launched 12 APIs in 2024; mailto:$email",
            "declared_grade" to "junior",
            "allowed_work_modes" to listOf("remote"),
            "cities" to listOf("Москва $email"),
            "max_weekly_hours" to 40,
            "skills" to listOf(mapOf("skill_id" to "python-basics", "provenance" to "candidate_declared")),
            "experience_entries" to listOf(mapOf(
                "id" to UUID.randomUUID().toString(), "company" to "Acme $email", "role" to "Lead $phone",
                "description" to "Delivered 12 features on 2026-10-08", "start_month" to "2024-01", "end_month" to null,
                "skill_ids" to listOf("python-basics"), "provenance" to "test_verified",
            )),
            "project_entries" to listOf(mapOf(
                "id" to UUID.randomUUID().toString(), "title" to "Project $email", "description" to "Call tel:$phone",
                "url" to "https://portfolio.example/?contact=${email.replace("@", "%40")}",
                "skill_ids" to listOf("http-api"), "provenance" to "fsp_verified",
            )),
        )
        jdbc.update("UPDATE candidate_profiles SET data=?::jsonb WHERE user_id=?::uuid", objectMapper.writeValueAsString(original), candidate.id)
        val versionBefore = jdbc.queryForObject("SELECT version FROM candidate_profiles WHERE user_id=?::uuid", Int::class.java, candidate.id)

        val preview = request(candidate, HttpMethod.GET, "/api/v1/me/public-preview")
        assertEquals(200, preview.statusCode.value())
        assertEquals("private, no-store", preview.headers.getFirst("Cache-Control"))
        assertEquals("hidden", preview.body!!.path("data").path("visibility").asText())
        assertEquals("Alice [контакт скрыт]", preview.body!!.path("data").path("candidate").path("display_name").asText())
        assertFalse(preview.body.toString().contains(email))
        assertFalse(preview.body.toString().contains("+7 (921) 555-12-34"))
        assertFalse(preview.body!!.path("data").path("candidate").path("cities").toString().contains(email))
        assertEquals("candidate_declared", preview.body!!.path("data").path("candidate").path("experience_entries").get(0).path("provenance").asText())
        assertTrue(preview.body!!.path("data").path("candidate").path("project_entries").get(0).path("url").isNull)
        assertEquals(404, request(employer, HttpMethod.GET, "/api/v1/candidates/${candidate.id}").statusCode.value())
        assertEquals(403, request(employer, HttpMethod.GET, "/api/v1/me/public-preview").statusCode.value())

        val own = request(candidate, HttpMethod.GET, "/api/v1/me/profile")
        assertEquals(email, own.body!!.path("data").path("display_name").asText().substringAfter("Alice "))
        assertTrue(own.body.toString().contains(phone))
        assertTrue(own.body!!.path("data").path("cities").get(0).asText().contains(email))
        assertTrue(own.body!!.path("data").path("experience_entries").get(0).path("company").asText().contains(email))
        assertTrue(own.body!!.path("data").path("project_entries").get(0).path("title").asText().contains(email))
        assertTrue(own.body!!.path("data").path("project_entries").get(0).path("url").asText().contains("%40"))
        assertEquals(versionBefore, jdbc.queryForObject("SELECT version FROM candidate_profiles WHERE user_id=?::uuid", Int::class.java, candidate.id))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM contact_grants WHERE candidate_id=?::uuid", Int::class.java, candidate.id))

        jdbc.update("UPDATE candidate_profiles SET visibility='public' WHERE user_id=?::uuid", candidate.id)
        val publicProfile = request(employer, HttpMethod.GET, "/api/v1/candidates/${candidate.id}")
        assertEquals(200, publicProfile.statusCode.value())
        assertFalse(publicProfile.body.toString().contains(email))
        assertFalse(publicProfile.body.toString().contains("+7 (921) 555-12-34"))
        val publicPdf = rawPdf(employer, candidate.id)
        assertEquals(200, publicPdf.statusCode.value())
        val publicPdfText = Loader.loadPDF(publicPdf.body!!).use { PDFTextStripper().getText(it) }
        assertFalse(publicPdfText.contains(email))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM contact_access_events WHERE candidate_id=?::uuid", Int::class.java, candidate.id))
    }

    @Test
    fun `only successful granted contact and PDF reads are journaled and revoke blocks later events`() {
        val (company, candidate, email) = fixture()
        val pdfWithoutGrant = rawPdf(company, candidate.id)
        assertEquals(200, pdfWithoutGrant.statusCode.value())
        assertFalse(Loader.loadPDF(pdfWithoutGrant.body!!).use { PDFTextStripper().getText(it) }.contains(email))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM contact_access_events WHERE candidate_id=?::uuid", Int::class.java, candidate.id))
        val invitation = request(company, HttpMethod.POST, "/api/v1/invitations", invitationBody(candidate.id), UUID.randomUUID().toString())
        assertEquals(201, invitation.statusCode.value(), invitation.body.toString())
        val inviteId = invitation.body!!.path("data").path("id").asText()
        val accepted = request(candidate, HttpMethod.POST, "/api/v1/invitations/$inviteId/decision", mapOf("expected_version" to 1, "action" to "accept"), UUID.randomUUID().toString())
        assertEquals(200, accepted.statusCode.value(), accepted.body.toString())
        val grantId = accepted.body!!.path("data").path("contact_grant_id").asText()

        assertEquals(200, request(company, HttpMethod.GET, "/api/v1/candidates/${candidate.id}/contacts").statusCode.value())
        assertEquals(404, request(seedActor("employer"), HttpMethod.GET, "/api/v1/candidates/${candidate.id}/contacts").statusCode.value())
        val concurrentReads = (1..4).map {
            CompletableFuture.supplyAsync { request(company, HttpMethod.GET, "/api/v1/candidates/${candidate.id}/contacts") }
        }.map { it.join() }
        assertTrue(concurrentReads.all { it.statusCode.value() == 200 })
        val pdfWithContacts = rawPdf(company, candidate.id)
        assertEquals(200, pdfWithContacts.statusCode.value())
        val pdfText = Loader.loadPDF(pdfWithContacts.body!!).use { PDFTextStripper().getText(it) }
        assertTrue(pdfText.contains(email))
        val count = jdbc.queryForObject("SELECT count(*) FROM contact_access_events WHERE candidate_id=?::uuid", Int::class.java, candidate.id)
        assertEquals(6, count)
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM contact_access_events WHERE candidate_id=?::uuid AND kind='contact_view'", Int::class.java, candidate.id))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM contact_access_events WHERE candidate_id=?::uuid AND kind='profile_export'", Int::class.java, candidate.id))
        assertFalse(jdbc.queryForObject("SELECT to_jsonb(e)::text FROM contact_access_events e WHERE candidate_id=?::uuid LIMIT 1", String::class.java, candidate.id)!!.contains(email))

        val ownEvents = request(candidate, HttpMethod.GET, "/api/v1/me/contact-access-events")
        assertEquals(200, ownEvents.statusCode.value())
        assertEquals("private, no-store", ownEvents.headers.getFirst("Cache-Control"))
        assertEquals(6, ownEvents.body!!.path("data").path("items").size())
        assertFalse(ownEvents.body.toString().contains("actor_user_id"))
        assertFalse(ownEvents.body.toString().contains(candidate.id))
        assertTrue(ownEvents.body.toString().contains("Example employer"))
        assertEquals(403, request(company, HttpMethod.GET, "/api/v1/me/contact-access-events").statusCode.value())
        val unrelatedCandidate = seedActor("candidate")
        assertEquals(0, request(unrelatedCandidate, HttpMethod.GET, "/api/v1/me/contact-access-events").body!!.path("data").path("items").size())

        assertEquals(200, request(candidate, HttpMethod.POST, "/api/v1/me/contact-grants/$grantId/revoke", emptyMap<String, Any?>(), UUID.randomUUID().toString()).statusCode.value())
        assertEquals(404, request(company, HttpMethod.GET, "/api/v1/candidates/${candidate.id}/contacts").statusCode.value())
        val afterRevoke = rawPdf(company, candidate.id)
        assertEquals(200, afterRevoke.statusCode.value())
        val revokedPdfText = Loader.loadPDF(afterRevoke.body!!).use { PDFTextStripper().getText(it) }
        assertFalse(revokedPdfText.contains(email))
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM contact_access_events WHERE candidate_id=?::uuid", Int::class.java, candidate.id))
    }

    private fun fixture(): Triple<TestActor, TestActor, String> {
        val company = seedActor("employer")
        val candidate = seedActor("candidate")
        val email = "contact-${UUID.randomUUID()}@example.test"
        jdbc.update("UPDATE companies SET data=?::jsonb WHERE id=?::uuid", """{"name":"Example employer"}""", company.companyId)
        jdbc.update(
            "UPDATE candidate_profiles SET visibility='public', data=?::jsonb, contacts=?::jsonb WHERE user_id=?::uuid",
            """{"display_name":"Visible candidate","declared_grade":"junior","allowed_work_modes":["remote"],"cities":[],"max_weekly_hours":40,"skills":[]}""",
            objectMapper.writeValueAsString(mapOf("channels" to listOf(mapOf("type" to "email", "value" to email, "enabled" to true)))), candidate.id,
        )
        return Triple(company, candidate, email)
    }

    private fun invitationBody(candidateId: String) = mapOf(
        "candidate_id" to candidateId, "need_id" to null,
        "salary" to mapOf("min_kopecks" to 100_000, "max_kopecks" to 300_000, "currency" to "RUB", "period" to "month", "basis" to "gross", "workload_type" to "full_time", "hours_per_week" to 40),
        "description" to "Backend role", "contact_method" to "platform", "company_comment" to null, "discussion_topics" to emptyList<String>(),
    )

    private fun rawPdf(actor: TestActor, candidateId: String) = http.exchange(
        "http://localhost:$port/api/v1/candidates/$candidateId/profile.pdf", HttpMethod.GET,
        HttpEntity<ByteArray>(HttpHeaders().apply {
            accept = listOf(MediaType.APPLICATION_PDF)
            set("Cookie", "${SessionSupport.COOKIE_NAME}=${actor.sessionCookie}")
        }), ByteArray::class.java,
    )
}
