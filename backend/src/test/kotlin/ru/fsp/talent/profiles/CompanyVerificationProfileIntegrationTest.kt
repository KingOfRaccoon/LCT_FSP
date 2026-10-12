package ru.fsp.talent.profiles

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import java.util.UUID

class CompanyVerificationProfileIntegrationTest : DbTestBase() {
    @Test
    fun `requisites validate and company changes invalidate only relevant verification facts`() {
        val employer = seedActor(role = "employer")
        fun patch(version: Int, fields: Map<String, Any?>) = request(employer, HttpMethod.PATCH, "/api/v1/me/company",
            fields + ("expected_version" to version), UUID.randomUUID().toString())
        assertEquals(400, patch(1, mapOf("inn" to "7707083894")).statusCode.value())
        val saved = patch(1, mapOf("inn" to "7707083893", "legal_name" to "Синтетическая компания", "website" to "https://example.test"))
        assertEquals(200, saved.statusCode.value(), saved.body.toString())
        assertEquals("7707083893", saved.body!!.path("data").path("inn").asText())
        val requested = request(employer, HttpMethod.POST, "/api/v1/me/company/verification/requests",
            mapOf("expected_company_version" to 2), UUID.randomUUID().toString())
        assertEquals(201, requested.statusCode.value(), requested.body.toString())
        val requestId = requested.body!!.path("data").path("current_request").path("id").asText()
        assertEquals(200, patch(2, mapOf("description" to "Уточнили описание")).statusCode.value())
        val unchanged = request(employer, HttpMethod.GET, "/api/v1/me/company/verification")
        assertTrue(unchanged.body!!.path("data").path("owner").path("summary").path("criteria_current").asBoolean(), unchanged.body.toString())
        assertEquals(200, patch(3, mapOf("website" to "https://changed.example.test")).statusCode.value())
        val changed = request(employer, HttpMethod.GET, "/api/v1/me/company/verification")
        assertFalse(changed.body!!.path("data").path("owner").path("summary").path("criteria_current").asBoolean(), changed.body.toString())
        assertEquals("superseded", jdbc.queryForObject("SELECT status FROM company_verification_requests WHERE id=?::uuid", String::class.java, requestId))
        val public = changed.body!!.path("data").path("public")
        assertFalse(public.has("inn")); assertFalse(public.has("snapshot")); assertFalse(public.has("review_notes"))
        assertFalse(public.path("claims_legal_authority").asBoolean())
    }

    @Test
    fun `operations permissions are explicit verified grants and cannot be self provisioned through company patch`() {
        val employer = seedActor(role = "employer")
        val before = request(employer, HttpMethod.GET, "/api/v1/auth/me")
        assertEquals(200, before.statusCode.value(), before.body.toString())
        assertEquals(0, before.body!!.path("data").path("operations_permissions").size())
        val illicit = request(employer, HttpMethod.PATCH, "/api/v1/me/company", mapOf("expected_version" to 1, "operations_permissions" to listOf("company_review")), UUID.randomUUID().toString())
        assertEquals(400, illicit.statusCode.value())
        jdbc.update("INSERT INTO operations_grants(user_id,permission) VALUES (?::uuid,'monitor_read')", employer.id)
        val granted = request(employer, HttpMethod.GET, "/api/v1/auth/me")
        assertEquals("monitor_read", granted.body!!.path("data").path("operations_permissions").path(0).asText())
        jdbc.update("UPDATE operations_grants SET active=false WHERE user_id=?::uuid", employer.id)
        assertEquals(0, request(employer, HttpMethod.GET, "/api/v1/auth/me").body!!.path("data").path("operations_permissions").size())
    }
}
