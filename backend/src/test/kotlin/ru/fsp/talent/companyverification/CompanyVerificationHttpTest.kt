package ru.fsp.talent.companyverification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.test.context.TestPropertySource
import ru.fsp.talent.core.DbTestBase
import java.util.UUID

@TestPropertySource(properties = ["COMPANY_VERIFICATION_PROVIDER=manual", "DADATA_TOKEN="])
class CompanyVerificationHttpTest : DbTestBase() {
    @Test
    fun `employer can read company verification summary and anonymous caller is unauthorized`() {
        val employer = seedActor(role = "employer")

        val anonymous = request(
            method = HttpMethod.GET,
            path = "/api/v1/me/company/verification",
        )
        val own = request(
            actor = employer,
            method = HttpMethod.GET,
            path = "/api/v1/me/company/verification",
        )

        assertEquals(401, anonymous.statusCode.value(), anonymous.body.toString())
        assertEquals(200, own.statusCode.value(), own.body.toString())
        assertTrue(own.body!!.path("data").path("public").isObject, own.body.toString())
        assertTrue(own.body!!.path("data").path("owner").isObject, own.body.toString())
    }

    @Test
    fun `verified employer with processing consent can request company verification`() {
        val employer = seedActor(role = "employer")
        addVerificationProfile(employer)

        val response = request(
            actor = employer,
            method = HttpMethod.POST,
            path = "/api/v1/me/company/verification/requests",
            body = mapOf("expected_company_version" to 1),
            idempotencyKey = UUID.randomUUID().toString(),
        )

        assertEquals(201, response.statusCode.value(), response.body.toString())
        assertEquals("pending", response.body!!.path("data").path("current_request").path("status").asText())
        assertEquals("pending", response.body!!.path("data").path("public").path("status").asText())
        assertTrue(response.body!!.path("data").path("public").path("demo").asBoolean())
        assertTrue(response.body!!.path("data").path("public").path("claims_legal_authority").isBoolean)
        assertTrue(response.body!!.path("data").path("public").path("inn").isMissingNode)
    }

    @Test
    fun `company verification request requires verified employer and current processing consent`() {
        val unverified = seedActor(role = "employer", verified = false)
        val noConsent = seedActor(role = "employer", defaultConsents = false)
        addVerificationProfile(unverified)
        addVerificationProfile(noConsent)

        val unverifiedResponse = request(
            actor = unverified,
            method = HttpMethod.POST,
            path = "/api/v1/me/company/verification/requests",
            body = mapOf("expected_company_version" to 1),
            idempotencyKey = UUID.randomUUID().toString(),
        )
        val noConsentResponse = request(
            actor = noConsent,
            method = HttpMethod.POST,
            path = "/api/v1/me/company/verification/requests",
            body = mapOf("expected_company_version" to 1),
            idempotencyKey = UUID.randomUUID().toString(),
        )

        assertEquals(422, unverifiedResponse.statusCode.value(), unverifiedResponse.body.toString())
        assertEquals("EMAIL_UNCONFIRMED", unverifiedResponse.body!!.path("error").path("code").asText())
        assertEquals(403, noConsentResponse.statusCode.value(), noConsentResponse.body.toString())
        assertEquals("CONSENT_REQUIRED", noConsentResponse.body!!.path("error").path("code").asText())
    }

    @Test
    fun `company verification queue is forbidden without an explicit reviewer grant`() {
        val employer = seedActor(role = "employer")

        val response = request(
            actor = employer,
            method = HttpMethod.GET,
            path = "/api/v1/operations/company-verifications",
        )

        assertEquals(403, response.statusCode.value(), response.body.toString())
    }

    @Test
    fun `company verification review is forbidden without an explicit reviewer grant`() {
        val employer = seedActor(role = "employer")
        val requestId = UUID.randomUUID()

        val response = request(
            actor = employer,
            method = HttpMethod.POST,
            path = "/api/v1/operations/company-verifications/$requestId/review",
            body = mapOf(
                "expected_version" to 1,
                "outcome" to "approve",
                "evidence_reference" to "manual-source:registry-record-1",
                "registry_inn" to "7707083893",
                "registry_legal_name" to "ПАО Сбербанк",
                "registry_status" to "ACTIVE",
                "representative_reviewed" to true,
                "comment" to "Reviewed against documented evidence",
            ),
            idempotencyKey = UUID.randomUUID().toString(),
        )

        assertEquals(403, response.statusCode.value(), response.body.toString())
    }

    @Test
    fun `explicit reviewer grant permits manual review while public result stays demo`() {
        val employer = seedActor(role = "employer")
        addVerificationProfile(employer)
        val created = request(
            actor = employer,
            method = HttpMethod.POST,
            path = "/api/v1/me/company/verification/requests",
            body = mapOf("expected_company_version" to 1),
            idempotencyKey = UUID.randomUUID().toString(),
        )
        assertEquals(201, created.statusCode.value(), created.body.toString())
        val requestId = created.body!!.path("data").path("current_request").path("id").asText()
        val operator = seedActor(role = "candidate")
        jdbc.update(
            "INSERT INTO operations_grants(user_id, permission, granted_by) VALUES (?::uuid, 'company_review', ?::uuid)",
            operator.id,
            operator.id,
        )

        val queue = request(
            actor = operator,
            method = HttpMethod.GET,
            path = "/api/v1/operations/company-verifications",
        )
        val reviewed = request(
            actor = operator,
            method = HttpMethod.POST,
            path = "/api/v1/operations/company-verifications/$requestId/review",
            body = mapOf(
                "expected_version" to 1,
                "outcome" to "approve",
                "evidence_reference" to "manual-source:registry-record-1",
                "registry_inn" to "7707083893",
                "registry_legal_name" to "ПАО Сбербанк",
                "registry_status" to "ACTIVE",
                "representative_reviewed" to true,
                "comment" to "Reviewed against documented evidence",
            ),
            idempotencyKey = UUID.randomUUID().toString(),
        )

        assertEquals(200, queue.statusCode.value(), queue.body.toString())
        assertTrue(queue.body!!.path("data").path("items").any { it.path("id").asText() == requestId })
        assertEquals(200, reviewed.statusCode.value(), reviewed.body.toString())
        assertEquals("reviewed", reviewed.body!!.path("data").path("request").path("status").asText())
        assertTrue(reviewed.body!!.path("data").path("request").path("demo").asBoolean())
        val public = reviewed.body!!.path("data").path("public")
        assertEquals("reviewed", public.path("status").asText())
        assertFalse(public.path("claims_legal_authority").asBoolean())
        assertTrue(public.path("inn").isMissingNode)
        assertEquals("passed", public.path("checks").first { it.path("code").asText() == "representative" }.path("status").asText())
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM company_verification_reviews WHERE request_id = ?::uuid",
            Int::class.java,
            requestId,
        ))
    }

    @Test
    fun `manual registry source reports unavailable and same idempotency key replays without another check`() {
        val employer = seedActor(role = "employer")
        addVerificationProfile(employer)
        val created = createVerificationRequest(employer)
        val requestId = created.body!!.path("data").path("current_request").path("id").asText()
        val key = UUID.randomUUID().toString()
        val body = mapOf("expected_version" to 1)

        val first = request(
            actor = employer,
            method = HttpMethod.POST,
            path = "/api/v1/me/company/verification/requests/$requestId/registry-check",
            body = body,
            idempotencyKey = key,
        )
        val replay = request(
            actor = employer,
            method = HttpMethod.POST,
            path = "/api/v1/me/company/verification/requests/$requestId/registry-check",
            body = body,
            idempotencyKey = key,
        )

        assertEquals(200, first.statusCode.value(), first.body.toString())
        assertEquals("unavailable", first.body!!.path("data").path("current_request").path("status").asText())
        assertEquals("unavailable", first.body!!.path("data").path("current_request").path("source_check_status").asText())
        assertEquals("unavailable", first.body!!.path("data").path("public").path("status").asText())
        assertEquals(first.body, replay.body)
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM company_verification_source_checks WHERE request_id = ?::uuid",
            Int::class.java,
            requestId,
        ))
    }

    @Test
    fun `registry check requires idempotency key and rejects stale expected request version`() {
        val employer = seedActor(role = "employer")
        addVerificationProfile(employer)
        val created = createVerificationRequest(employer)
        val requestId = created.body!!.path("data").path("current_request").path("id").asText()

        val missingKey = request(
            actor = employer,
            method = HttpMethod.POST,
            path = "/api/v1/me/company/verification/requests/$requestId/registry-check",
            body = mapOf("expected_version" to 1),
        )
        val staleVersion = request(
            actor = employer,
            method = HttpMethod.POST,
            path = "/api/v1/me/company/verification/requests/$requestId/registry-check",
            body = mapOf("expected_version" to 2),
            idempotencyKey = UUID.randomUUID().toString(),
        )

        assertEquals(400, missingKey.statusCode.value(), missingKey.body.toString())
        assertEquals(409, staleVersion.statusCode.value(), staleVersion.body.toString())
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM company_verification_source_checks WHERE request_id = ?::uuid",
            Int::class.java,
            requestId,
        ))
    }

    @Test
    fun `non-owner company member cannot replay private mutation responses`() {
        val owner = seedActor(role = "employer")
        addVerificationProfile(owner)
        val createKey = UUID.randomUUID().toString()
        val created = request(
            actor = owner,
            method = HttpMethod.POST,
            path = "/api/v1/me/company/verification/requests",
            body = mapOf("expected_company_version" to 1),
            idempotencyKey = createKey,
        )
        assertEquals(201, created.statusCode.value(), created.body.toString())
        val requestId = created.body!!.path("data").path("current_request").path("id").asText()
        val registryKey = UUID.randomUUID().toString()
        val registryBody = mapOf("expected_version" to 1)
        val registryResponse = request(
            actor = owner,
            method = HttpMethod.POST,
            path = "/api/v1/me/company/verification/requests/$requestId/registry-check",
            body = registryBody,
            idempotencyKey = registryKey,
        )
        assertEquals(200, registryResponse.statusCode.value(), registryResponse.body.toString())

        val member = seedActor(role = "employer")
        jdbc.update("DELETE FROM memberships WHERE user_id = ?::uuid", member.id)
        jdbc.update(
            "INSERT INTO memberships(id, user_id, company_id, role, active) VALUES (?::uuid, ?::uuid, ?::uuid, 'recruiter', true)",
            UUID.randomUUID().toString(),
            member.id,
            owner.companyId,
        )
        val replayCreate = request(
            actor = member,
            method = HttpMethod.POST,
            path = "/api/v1/me/company/verification/requests",
            body = mapOf("expected_company_version" to 1),
            idempotencyKey = createKey,
        )
        val replayRegistry = request(
            actor = member,
            method = HttpMethod.POST,
            path = "/api/v1/me/company/verification/requests/$requestId/registry-check",
            body = registryBody,
            idempotencyKey = registryKey,
        )

        assertEquals(403, replayCreate.statusCode.value(), replayCreate.body.toString())
        assertEquals(403, replayRegistry.statusCode.value(), replayRegistry.body.toString())
        assertEquals("FORBIDDEN", replayCreate.body!!.path("error").path("code").asText())
        assertEquals("FORBIDDEN", replayRegistry.body!!.path("error").path("code").asText())
    }

    @Test
    fun `company owner cannot review its own request even with an operator grant`() {
        val employer = seedActor(role = "employer")
        addVerificationProfile(employer)
        val created = createVerificationRequest(employer)
        val requestId = created.body!!.path("data").path("current_request").path("id").asText()
        jdbc.update(
            "INSERT INTO operations_grants(user_id, permission, granted_by) VALUES (?::uuid, 'company_review', ?::uuid)",
            employer.id,
            employer.id,
        )

        val response = request(
            actor = employer,
            method = HttpMethod.POST,
            path = "/api/v1/operations/company-verifications/$requestId/review",
            body = mapOf(
                "expected_version" to 1,
                "outcome" to "approve",
                "evidence_reference" to "manual-source:registry-record-1",
                "registry_inn" to "7707083893",
                "registry_legal_name" to "ПАО Сбербанк",
                "registry_status" to "ACTIVE",
                "representative_reviewed" to true,
                "comment" to "Reviewed against documented evidence",
            ),
            idempotencyKey = UUID.randomUUID().toString(),
        )

        assertEquals(403, response.statusCode.value(), response.body.toString())
        assertEquals("OWN_COMPANY_REVIEW_FORBIDDEN", response.body!!.path("error").path("code").asText())
    }

    @Test
    fun `expired review loses current registry and legal name checks`() {
        val employer = seedActor(role = "employer")
        addVerificationProfile(employer)
        val created = createVerificationRequest(employer)
        val requestId = created.body!!.path("data").path("current_request").path("id").asText()
        val operator = seedActor(role = "candidate")
        jdbc.update(
            "INSERT INTO operations_grants(user_id, permission, granted_by) VALUES (?::uuid, 'company_review', ?::uuid)",
            operator.id,
            operator.id,
        )
        val review = request(
            actor = operator,
            method = HttpMethod.POST,
            path = "/api/v1/operations/company-verifications/$requestId/review",
            body = mapOf(
                "expected_version" to 1,
                "outcome" to "approve",
                "evidence_reference" to "manual-source:registry-record-1",
                "registry_inn" to "7707083893",
                "registry_legal_name" to "ПАО Сбербанк",
                "registry_status" to "ACTIVE",
                "representative_reviewed" to true,
                "comment" to "Reviewed against documented evidence",
            ),
            idempotencyKey = UUID.randomUUID().toString(),
        )
        assertEquals(200, review.statusCode.value(), review.body.toString())
        jdbc.update("UPDATE company_verification_requests SET expires_at = now() - interval '1 second' WHERE id = ?::uuid", requestId)

        val summary = request(
            actor = employer,
            method = HttpMethod.GET,
            path = "/api/v1/me/company/verification",
        )
        val public = summary.body!!.path("data").path("public")
        assertEquals("expired", public.path("status").asText())
        assertEquals("unknown", public.path("checks").first { it.path("code").asText() == "registry" }.path("status").asText())
        assertEquals("unknown", public.path("checks").first { it.path("code").asText() == "legal_name" }.path("status").asText())
        assertEquals("unknown", public.path("checks").first { it.path("code").asText() == "representative" }.path("status").asText())
    }

    private fun createVerificationRequest(actor: ru.fsp.talent.core.TestActor) = request(
        actor = actor,
        method = HttpMethod.POST,
        path = "/api/v1/me/company/verification/requests",
        body = mapOf("expected_company_version" to 1),
        idempotencyKey = UUID.randomUUID().toString(),
    )

    private fun addVerificationProfile(actor: ru.fsp.talent.core.TestActor) {
        jdbc.update(
            "UPDATE companies SET data = data || ?::jsonb WHERE id = ?::uuid",
            """{"inn":"7707083893","legal_name":"ПАО Сбербанк","website":"https://sberbank.ru"}""",
            actor.companyId,
        )
    }
}
