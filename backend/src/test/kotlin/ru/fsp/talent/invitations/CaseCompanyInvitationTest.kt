package ru.fsp.talent.invitations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import java.util.UUID

class CaseCompanyInvitationTest : DbTestBase() {
    @Test
    fun `company contact details reach candidate as an immutable invitation snapshot`() {
        val employer = seedActor("employer")
        val candidate = seedActor()
        jdbc.update("UPDATE candidate_profiles SET visibility='public', data=?::jsonb WHERE user_id=?::uuid", objectMapper.writeValueAsString(mapOf(
            "display_name" to "Case Candidate", "specialization_id" to "backend-python-v1", "declared_grade" to "junior",
            "allowed_work_modes" to listOf("remote"), "max_weekly_hours" to 40, "skills" to emptyList<Any>(),
        )), candidate.id)
        val company = request(employer, HttpMethod.PATCH, "/api/v1/me/company", mapOf(
            "expected_version" to 1, "name" to "Case Company", "industry" to "Fintech",
            "contact_email" to "company@example.test", "contact_phone" to "+7 999 111 22 33",
        ), UUID.randomUUID().toString())
        assertEquals(200, company.statusCode.value(), company.body.toString())
        val created = request(employer, HttpMethod.POST, "/api/v1/invitations", mapOf(
            "candidate_id" to candidate.id, "need_id" to null, "description" to "Backend role", "contact_method" to "platform",
            "salary" to mapOf("min_kopecks" to 10000000, "max_kopecks" to 15000000, "currency" to "RUB", "period" to "month", "basis" to "gross", "workload_type" to "full_time", "hours_per_week" to 40),
            "company_comment" to null, "discussion_topics" to emptyList<String>(),
        ), UUID.randomUUID().toString())
        assertEquals(201, created.statusCode.value(), created.body.toString())
        val id = created.body!!.path("data").path("id").asText()
        jdbc.update("UPDATE companies SET data=jsonb_set(data,'{contact_email}',to_jsonb('changed@example.test'::text)) WHERE id=?::uuid", employer.companyId)
        val received = request(candidate, HttpMethod.GET, "/api/v1/invitations/$id")
        assertEquals(200, received.statusCode.value(), received.body.toString())
        assertEquals("Fintech", received.body!!.path("data").path("company_industry").asText())
        assertEquals("company@example.test", received.body!!.path("data").path("company_contact_email").asText())
        assertEquals("+7 999 111 22 33", received.body!!.path("data").path("company_contact_phone").asText())
    }
}
