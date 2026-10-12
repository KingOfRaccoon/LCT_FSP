package ru.fsp.talent.matching

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import java.util.UUID

class UnconfirmedCandidateNeedSearchTest : DbTestBase() {
    @Test
    fun `ordinary profile api exposes an unconfirmed public candidate in the current specialization`() {
        val candidate = seedActor("candidate")
        val company = seedActor("employer")
        val profile = request(candidate, HttpMethod.GET, "/api/v1/me/profile").body!!.path("data")
        val patched = request(candidate, HttpMethod.PATCH, "/api/v1/me/profile", mapOf(
            "expected_version" to profile.path("version").asInt(),
            "display_name" to "Unconfirmed API Candidate",
            "declared_grade" to "junior",
            "allowed_work_modes" to listOf("remote"),
            "cities" to emptyList<String>(),
            "max_weekly_hours" to 40,
            "skills" to listOf("python-basics"),
        ), UUID.randomUUID().toString())
        assertEquals(200, patched.statusCode.value(), patched.body.toString())
        assertEquals("backend-python-v1", patched.body!!.path("data").path("specialization_id").asText())
        val published = request(candidate, HttpMethod.PATCH, "/api/v1/me/visibility", mapOf(
            "expected_version" to patched.body!!.path("data").path("version").asInt(),
            "visibility" to "public",
        ), UUID.randomUUID().toString())
        assertEquals(200, published.statusCode.value(), published.body.toString())
        val created = request(company, HttpMethod.POST, "/api/v1/needs", mapOf(
            "title" to "Python role",
            "description" to "Work on Python APIs",
            "specialization_id" to "backend-python-v1",
            "target_grade" to "junior",
            "work_mode" to "remote",
            "city" to null,
            "workload_hours_per_week" to 40,
            "salary" to mapOf("min_kopecks" to 10_000_000, "max_kopecks" to 20_000_000,
                "currency" to "RUB", "period" to "month", "basis" to "gross",
                "workload_type" to "full_time", "hours_per_week" to 40),
            "requirements" to listOf(mapOf("skill_id" to "python-basics", "importance" to "required")),
        ), UUID.randomUUID().toString())
        assertEquals(201, created.statusCode.value(), created.body.toString())
        val needId = created.body!!.path("data").path("id").asText()
        val confirmed = request(company, HttpMethod.POST, "/api/v1/needs/$needId/competencies/confirm", mapOf(
            "expected_version" to 1,
            "confirmed_skill_ids" to listOf("python-basics"),
        ), UUID.randomUUID().toString())
        assertEquals(200, confirmed.statusCode.value(), confirmed.body.toString())
        val confirmationIds = mutableSetOf<String>()
        val confirmedIds = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val search = request(company, HttpMethod.POST, "/api/v1/matches/search", mapOf(
                "need_id" to needId,
                "limit" to 50,
                "cursor" to cursor,
                "strict_confirmed_only" to false,
            ), UUID.randomUUID().toString())
            assertEquals(200, search.statusCode.value(), search.body.toString())
            val data = search.body!!.path("data")
            val groups = data.path("groups")
            confirmationIds += groups.path("needs_confirmation").map { it.path("candidate").path("id").asText() }
            confirmedIds += groups.path("confirmed").map { it.path("candidate").path("id").asText() }
            cursor = data.path("next_cursor").takeUnless { it.isNull }?.asText()
        } while (cursor != null)
        assertTrue(candidate.id in confirmationIds)
        assertFalse(candidate.id in confirmedIds)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM qualifications WHERE user_id=?::uuid", Int::class.java, candidate.id))
    }
}
