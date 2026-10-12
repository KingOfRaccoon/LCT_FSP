package ru.fsp.talent.privacy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import java.util.UUID

class ConsentProfileIntegrationTest : DbTestBase() {
    @Test
    fun `consent documents and state are versioned and isolated to the signed in actor`() {
        val candidate = seedActor(defaultConsents = false)
        val other = seedActor(defaultConsents = false)

        val initial = request(candidate, HttpMethod.GET, "/api/v1/me/consents")
        assertEquals(200, initial.statusCode.value())
        val documents = initial.body!!.path("data").path("documents")
        assertEquals(setOf("processing", "publication"), documents.map { it.path("scope").asText() }.toSet())
        assertTrue(documents.all { it.path("version").asText() == "1.0" })
        assertTrue(documents.all { it.path("text").asText().isNotBlank() })
        assertTrue(documents.none {
            val text = it.path("text").asText().lowercase()
            text.contains("соответствует 152") || text.contains("гарантирует соблюдение")
        })
        assertTrue(initial.body!!.path("data").path("states").all { !it.path("accepted").asBoolean() })
        assertEquals(0, initial.body!!.path("data").path("events").size())

        val accepted = consentAccept(candidate, "processing")
        assertEquals(200, accepted.statusCode.value(), accepted.body.toString())
        assertTrue(accepted.body!!.path("data").path("states").any {
            it.path("scope").asText() == "processing" && it.path("accepted").asBoolean()
        })
        val replayKey = UUID.randomUUID().toString()
        val repeated = request(candidate, HttpMethod.POST, "/api/v1/me/consents", mapOf("scope" to "publication", "version" to "1.0", "action" to "accept"), replayKey)
        val replay = request(candidate, HttpMethod.POST, "/api/v1/me/consents", mapOf("scope" to "publication", "version" to "1.0", "action" to "accept"), replayKey)
        assertEquals(200, repeated.statusCode.value(), repeated.body.toString())
        assertEquals(repeated.body, replay.body)

        val own = request(candidate, HttpMethod.GET, "/api/v1/me/consents").body!!.path("data")
        val foreign = request(other, HttpMethod.GET, "/api/v1/me/consents").body!!.path("data")
        assertEquals(2, own.path("events").size())
        assertEquals(2, own.path("states").count { it.path("accepted").asBoolean() })
        assertTrue(foreign.path("events").isEmpty)
        assertTrue(foreign.path("states").all { !it.path("accepted").asBoolean() })

        val unsupportedVersion = request(candidate, HttpMethod.POST, "/api/v1/me/consents", mapOf("scope" to "processing", "version" to "0.9", "action" to "accept"), UUID.randomUUID().toString())
        assertNotEquals(200, unsupportedVersion.statusCode.value())
    }

    @Test
    fun `profile edits assessment and publication require their current consent scopes`() {
        val candidate = seedActor(defaultConsents = false)
        val patch = mapOf(
            "expected_version" to 1,
            "display_name" to "Кандидат",
            "declared_grade" to "junior",
            "allowed_work_modes" to listOf("remote"),
            "max_weekly_hours" to 40,
            "industry" to "Fintech",
        )
        val blockedPatch = request(candidate, HttpMethod.PATCH, "/api/v1/me/profile", patch, UUID.randomUUID().toString())
        assertEquals(403, blockedPatch.statusCode.value(), blockedPatch.body.toString())
        val blockedAssessment = createAttempt(candidate)
        assertEquals(403, blockedAssessment.statusCode.value(), blockedAssessment.body.toString())
        val blockedPublication = request(candidate, HttpMethod.PATCH, "/api/v1/me/visibility", mapOf("expected_version" to 1, "visibility" to "public"), UUID.randomUUID().toString())
        assertEquals(403, blockedPublication.statusCode.value(), blockedPublication.body.toString())

        assertEquals(200, consentAccept(candidate, "processing").statusCode.value())
        val profile = request(candidate, HttpMethod.PATCH, "/api/v1/me/profile", patch, UUID.randomUUID().toString())
        assertEquals(200, profile.statusCode.value(), profile.body.toString())
        assertEquals("Fintech", profile.body!!.path("data").path("industry").asText())
        assertEquals(201, createAttempt(candidate).statusCode.value())

        assertEquals(200, consentAccept(candidate, "publication").statusCode.value())
        val visible = request(candidate, HttpMethod.PATCH, "/api/v1/me/visibility", mapOf("expected_version" to 2, "visibility" to "public"), UUID.randomUUID().toString())
        assertEquals(200, visible.statusCode.value(), visible.body.toString())
        assertEquals("public", visible.body!!.path("data").path("visibility").asText())
        assertEquals(200, request(candidate, HttpMethod.GET, "/api/v1/candidates/${candidate.id}").statusCode.value())

        val revoked = request(candidate, HttpMethod.POST, "/api/v1/me/consents", mapOf("scope" to "publication", "version" to "1.0", "action" to "revoke"), UUID.randomUUID().toString())
        assertEquals(200, revoked.statusCode.value(), revoked.body.toString())
        assertFalse(revoked.body!!.path("data").path("states").any {
            it.path("scope").asText() == "publication" && it.path("accepted").asBoolean()
        })
        assertEquals("hidden", request(candidate, HttpMethod.GET, "/api/v1/me/profile").body!!.path("data").path("visibility").asText())
        assertEquals(404, request(candidate, HttpMethod.GET, "/api/v1/candidates/${candidate.id}").statusCode.value())
        val cannotRepublish = request(candidate, HttpMethod.PATCH, "/api/v1/me/visibility", mapOf("expected_version" to 4, "visibility" to "public"), UUID.randomUUID().toString())
        assertEquals(403, cannotRepublish.statusCode.value())
    }

    @Test
    fun `processing revocation hides candidate and blocks edits until the same version is accepted again`() {
        val candidate = seedActor(defaultConsents = false)
        consentAccept(candidate, "processing")
        consentAccept(candidate, "publication")
        val completed = request(
            candidate,
            HttpMethod.PATCH,
            "/api/v1/me/profile",
            mapOf("expected_version" to 1, "display_name" to "Кандидат", "declared_grade" to "junior", "allowed_work_modes" to listOf("remote"), "max_weekly_hours" to 40),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, completed.statusCode.value(), completed.body.toString())
        val visible = request(candidate, HttpMethod.PATCH, "/api/v1/me/visibility", mapOf("expected_version" to 2, "visibility" to "public"), UUID.randomUUID().toString())
        assertEquals(200, visible.statusCode.value(), visible.body.toString())
        assertEquals(200, request(candidate, HttpMethod.GET, "/api/v1/candidates/${candidate.id}").statusCode.value())

        val revoked = request(candidate, HttpMethod.POST, "/api/v1/me/consents", mapOf("scope" to "processing", "version" to "1.0", "action" to "revoke"), UUID.randomUUID().toString())
        assertEquals(200, revoked.statusCode.value(), revoked.body.toString())
        assertEquals("hidden", request(candidate, HttpMethod.GET, "/api/v1/me/profile").body!!.path("data").path("visibility").asText())
        assertEquals(404, request(candidate, HttpMethod.GET, "/api/v1/candidates/${candidate.id}").statusCode.value())
        val blockedPatch = request(candidate, HttpMethod.PATCH, "/api/v1/me/profile", mapOf("expected_version" to 4, "industry" to "Fintech"), UUID.randomUUID().toString())
        assertEquals(403, blockedPatch.statusCode.value())
        val blockedPublish = request(candidate, HttpMethod.PATCH, "/api/v1/me/visibility", mapOf("expected_version" to 4, "visibility" to "public"), UUID.randomUUID().toString())
        assertEquals(403, blockedPublish.statusCode.value())

        assertEquals(200, consentAccept(candidate, "processing").statusCode.value())
        val edited = request(candidate, HttpMethod.PATCH, "/api/v1/me/profile", mapOf("expected_version" to 4, "industry" to "Fintech"), UUID.randomUUID().toString())
        assertEquals(200, edited.statusCode.value(), edited.body.toString())
    }

    @Test
    fun `candidate and company profile fields preserve omissions and validate bounds`() {
        val candidate = seedActor(defaultConsents = false)
        consentAccept(candidate, "processing")
        val saved = request(
            candidate,
            HttpMethod.PATCH,
            "/api/v1/me/profile",
            mapOf(
                "expected_version" to 1,
                "industry" to "Fintech",
                "role_preferences" to listOf("Backend engineer", "Platform engineer"),
                "soft_skills" to listOf("Mentoring", "Clear writing"),
            ),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, saved.statusCode.value(), saved.body.toString())
        val cleared = request(
            candidate,
            HttpMethod.PATCH,
            "/api/v1/me/profile",
            mapOf("expected_version" to 2, "industry" to null, "role_preferences" to emptyList<String>()),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, cleared.statusCode.value(), cleared.body.toString())
        val data = cleared.body!!.path("data")
        assertTrue(data.path("industry").isNull)
        assertEquals(0, data.path("role_preferences").size())
        assertEquals(2, data.path("soft_skills").size())
        assertRejectedProfile(candidate, mapOf("role_preferences" to (1..11).map { "Role $it" }))
        assertRejectedProfile(candidate, mapOf("soft_skills" to (1..11).map { "Skill $it" }))
        assertRejectedProfile(candidate, mapOf("role_preferences" to listOf("x".repeat(161))))
        assertRejectedProfile(candidate, mapOf("industry" to "x".repeat(161)))

        val employer = seedActor(role = "employer", defaultConsents = false)
        consentAccept(employer, "processing")
        val company = request(
            employer,
            HttpMethod.PATCH,
            "/api/v1/me/company",
            mapOf("expected_version" to 1, "industry" to "Fintech", "contact_email" to "hr@example.test", "contact_phone" to "+74950000000"),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, company.statusCode.value(), company.body.toString())
        assertEquals("Fintech", company.body!!.path("data").path("industry").asText())
        assertEquals("hr@example.test", company.body!!.path("data").path("contact_email").asText())
        val preservedCompany = request(employer, HttpMethod.PATCH, "/api/v1/me/company", mapOf("expected_version" to 2, "name" to "Acme"), UUID.randomUUID().toString())
        assertEquals(200, preservedCompany.statusCode.value())
        assertEquals("Fintech", preservedCompany.body!!.path("data").path("industry").asText())
        assertEquals("hr@example.test", preservedCompany.body!!.path("data").path("contact_email").asText())
        val invalidCompany = request(employer, HttpMethod.PATCH, "/api/v1/me/company", mapOf("expected_version" to 3, "contact_phone" to "x".repeat(65)), UUID.randomUUID().toString())
        assertEquals(400, invalidCompany.statusCode.value())
    }

    @Test
    fun `legacy public visibility is not exposed until the user accepts current consents`() {
        val candidate = seedActor(defaultConsents = false)
        jdbc.update("UPDATE candidate_profiles SET visibility = 'public' WHERE user_id = ?::uuid", candidate.id)

        val privateProfile = request(candidate, HttpMethod.GET, "/api/v1/me/profile")
        assertEquals("hidden", privateProfile.body!!.path("data").path("visibility").asText())
        assertEquals(404, request(candidate, HttpMethod.GET, "/api/v1/candidates/${candidate.id}").statusCode.value())

        assertEquals(200, consentAccept(candidate, "processing").statusCode.value())
        assertEquals(404, request(candidate, HttpMethod.GET, "/api/v1/candidates/${candidate.id}").statusCode.value())
        assertEquals(200, consentAccept(candidate, "publication").statusCode.value())
        assertEquals(200, request(candidate, HttpMethod.GET, "/api/v1/candidates/${candidate.id}").statusCode.value())
        assertEquals("public", request(candidate, HttpMethod.GET, "/api/v1/me/profile").body!!.path("data").path("visibility").asText())
    }

    private fun createAttempt(actor: ru.fsp.talent.core.TestActor) = request(
        actor,
        HttpMethod.POST,
        "/api/v1/attempts",
        mapOf(
            "specialization_id" to "backend-python-v1",
            "requested_grade" to "junior",
            "policy_id" to "backend-python-policy-v1",
            "policy_version" to "1.0.0",
        ),
        UUID.randomUUID().toString(),
    )

    private fun assertRejectedProfile(actor: ru.fsp.talent.core.TestActor, fields: Map<String, Any?>) {
        val response = request(
            actor,
            HttpMethod.PATCH,
            "/api/v1/me/profile",
            mapOf("expected_version" to 3) + fields,
            UUID.randomUUID().toString(),
        )
        assertEquals(400, response.statusCode.value(), response.body.toString())
    }
}
