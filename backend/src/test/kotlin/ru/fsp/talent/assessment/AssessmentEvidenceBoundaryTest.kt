package ru.fsp.talent.assessment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase

class AssessmentEvidenceBoundaryTest : DbTestBase() {
    @Test
    fun `removed radar route cannot expose a hidden candidate without authentication`() {
        val candidate = seedActor(defaultConsents = false)
        jdbc.update("UPDATE candidate_profiles SET visibility='hidden', data=?::jsonb WHERE user_id=?::uuid",
            """{"display_name":"Private Review Candidate","declared_grade":"middle"}""", candidate.id)
        val response = request(null, HttpMethod.GET, "/api/v1/candidates/${candidate.id}/competency-radar")
        assertEquals(404, response.statusCode.value(), response.body.toString())
        assertFalse(response.body.toString().contains("Private Review Candidate"))
    }

    @Test
    fun `uncalibrated metrics are not published as empirical validation`() {
        val response = request(null, HttpMethod.GET, "/api/v1/assessment/validation-metrics")
        assertEquals(404, response.statusCode.value(), response.body.toString())
        assertFalse(response.body!!.has("data"))
    }

    @Test
    fun `unimplemented integrity monitoring cannot acknowledge arbitrary attempts`() {
        val candidate = seedActor()
        val response = request(candidate, HttpMethod.POST,
            "/api/v1/attempts/11111111-1111-4111-8111-111111111112/telemetry",
            mapOf("tab_switches" to 0, "paste_count" to 0))
        assertEquals(404, response.statusCode.value(), response.body.toString())
        assertFalse(response.body!!.has("data"))
    }
}
