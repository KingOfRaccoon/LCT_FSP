package ru.fsp.talent.recruiting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import java.util.UUID

class AtsExportCaseBehaviorTest : DbTestBase() {
    @Test
    fun `JSON export includes only enabled profile contact channels and logs the export`() {
        val employer = seedActor(role = "employer")
        val candidate = seedActor()
        grant(employer.companyId!!, candidate.id)
        jdbc.update(
            "UPDATE candidate_profiles SET contacts=?::jsonb, data=?::jsonb WHERE user_id=?::uuid",
            objectMapper.writeValueAsString(mapOf("channels" to listOf(
                mapOf("type" to "email", "value" to "disabled@example.test", "enabled" to false),
                mapOf("type" to "phone", "value" to "+79990000000", "enabled" to true),
            ))),
            objectMapper.writeValueAsString(mapOf("display_name" to "Кандидат", "headline" to "Backend разработчик")),
            candidate.id,
        )

        val response = request(employer, HttpMethod.GET, "/api/v1/recruiting/export/ats?format=json")

        assertEquals(200, response.statusCode.value(), response.body.toString())
        val export = response.body!!.path("data")
        assertEquals("json", export.path("format").asText())
        assertFalse(export.has("provider"))
        assertEquals(1, export.path("total_candidates").asInt())
        val item = export.path("candidates").first()
        assertNull(item.get("email"))
        assertEquals("+79990000000", item.path("phone").asText())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM contact_access_events WHERE candidate_id=?::uuid AND kind='profile_export'", Int::class.java, candidate.id))
    }

    @Test
    fun `export excludes candidates whose processing consent was revoked and rejects provider formats`() {
        val employer = seedActor(role = "employer")
        val candidate = seedActor()
        grant(employer.companyId!!, candidate.id)
        val revoke = request(candidate, HttpMethod.POST, "/api/v1/me/consents", mapOf(
            "scope" to "processing", "version" to "1.0", "action" to "revoke",
        ), UUID.randomUUID().toString())
        assertEquals(200, revoke.statusCode.value())

        val export = request(employer, HttpMethod.GET, "/api/v1/recruiting/export/ats?format=json")
        val provider = request(employer, HttpMethod.GET, "/api/v1/recruiting/export/ats?format=huntflow")

        assertEquals(200, export.statusCode.value(), export.body.toString())
        assertEquals(0, export.body!!.path("data").path("total_candidates").asInt())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM contact_access_events WHERE candidate_id=?::uuid AND kind='profile_export'", Int::class.java, candidate.id))
        assertEquals(400, provider.statusCode.value())
        assertTrue(provider.body!!.path("error").path("code").asText().isNotBlank())

        val removedTrustScore = request(null, HttpMethod.GET, "/api/v1/companies/${employer.companyId}/trust-score")
        assertEquals(404, removedTrustScore.statusCode.value())
    }

    private fun grant(companyId: String, candidateId: String) {
        val invitationId = UUID.randomUUID().toString()
        jdbc.update(
            "INSERT INTO invitations(id,company_id,candidate_id,version,state,expires_at) VALUES(?::uuid,?::uuid,?::uuid,1,'accepted',now()+interval '1 day')",
            invitationId, companyId, candidateId,
        )
        jdbc.update(
            "INSERT INTO contact_grants(id,company_id,candidate_id,basis_invitation_id,basis_version) VALUES(?::uuid,?::uuid,?::uuid,?::uuid,1)",
            UUID.randomUUID().toString(), companyId, candidateId, invitationId,
        )
    }
}
