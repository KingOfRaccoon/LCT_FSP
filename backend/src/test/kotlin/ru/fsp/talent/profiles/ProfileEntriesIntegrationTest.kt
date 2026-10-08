package ru.fsp.talent.profiles

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import java.util.UUID

class ProfileEntriesIntegrationTest : DbTestBase() {
    @Test
    fun `old profiles read empty collections and profile patches preserve omitted data`() {
        val candidate = seedActor()
        val initial = request(candidate, HttpMethod.GET, "/api/v1/me/profile")
        assertEquals(200, initial.statusCode.value())
        assertEquals(0, initial.body!!.path("data").path("experience_entries").size())
        assertEquals(0, initial.body!!.path("data").path("project_entries").size())

        val experienceId = UUID.randomUUID().toString()
        val projectId = UUID.randomUUID().toString()
        val first = request(
            candidate,
            HttpMethod.PATCH,
            "/api/v1/me/profile",
            mapOf(
                "expected_version" to 1,
                "display_name" to "Кандидат",
                "experience_entries" to listOf(experience(experienceId)),
                "project_entries" to listOf(project(projectId)),
            ),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, first.statusCode.value(), first.body.toString())
        val data = first.body!!.path("data")
        assertEquals("Кандидат", data.path("display_name").asText())
        assertEquals("candidate_declared", data.path("experience_entries").get(0).path("provenance").asText())
        assertEquals("candidate_declared", data.path("project_entries").get(0).path("provenance").asText())
        assertFalse(data.path("experience_entries").get(0).has("verified_grade"))
        assertEquals("https://portfolio.invalid/work", data.path("project_entries").get(0).path("url").asText())

        val preserved = request(
            candidate,
            HttpMethod.PATCH,
            "/api/v1/me/profile",
            mapOf("expected_version" to 2, "headline" to "Backend"),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, preserved.statusCode.value())
        assertEquals("Кандидат", preserved.body!!.path("data").path("display_name").asText())
        assertEquals("Backend", preserved.body!!.path("data").path("headline").asText())
        assertEquals(experienceId, preserved.body!!.path("data").path("experience_entries").get(0).path("id").asText())
        assertEquals(projectId, preserved.body!!.path("data").path("project_entries").get(0).path("id").asText())

        val cleared = request(
            candidate,
            HttpMethod.PATCH,
            "/api/v1/me/profile",
            mapOf("expected_version" to 3, "experience_entries" to emptyList<Any>()),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, cleared.statusCode.value())
        assertEquals(0, cleared.body!!.path("data").path("experience_entries").size())
        assertEquals(1, cleared.body!!.path("data").path("project_entries").size())
    }

    @Test
    fun `profile entry schemas reject null spoofed provenance invalid months and project userinfo`() {
        val candidate = seedActor()
        assertProfilePatchRejected(candidate, mapOf("experience_entries" to null))
        assertProfilePatchRejected(candidate, mapOf("project_entries" to null))
        assertProfilePatchRejected(candidate, mapOf("experience_entries" to listOf(experience(UUID.randomUUID().toString(), end = "2020-01", start = "2020-02"))))
        assertProfilePatchRejected(candidate, mapOf("project_entries" to listOf(project(UUID.randomUUID().toString(), url = "https://user:pass@portfolio.invalid/"))))
        assertProfilePatchRejected(candidate, mapOf("project_entries" to listOf(project(UUID.randomUUID().toString()) + ("provenance" to "test_verified"))))
        val duplicateId = UUID.randomUUID().toString()
        assertProfilePatchRejected(candidate, mapOf("experience_entries" to listOf(experience(duplicateId), experience(duplicateId))))

        val accepted = request(
            candidate,
            HttpMethod.PATCH,
            "/api/v1/me/profile",
            mapOf("expected_version" to 1, "project_entries" to listOf(project(UUID.randomUUID().toString(), url = null))),
            UUID.randomUUID().toString(),
        )
        assertEquals(200, accepted.statusCode.value())
    }

    @Test
    fun `entry IDs are scoped to their owner and collection while duplicates within an array are rejected`() {
        val first = seedActor()
        val second = seedActor()
        val id = UUID.randomUUID().toString()
        val created = request(first, HttpMethod.PATCH, "/api/v1/me/profile", mapOf("expected_version" to 1, "experience_entries" to listOf(experience(id, description = "Первый профиль"))), UUID.randomUUID().toString())
        assertEquals(200, created.statusCode.value(), created.body.toString())

        val independent = request(second, HttpMethod.PATCH, "/api/v1/me/profile", mapOf(
            "expected_version" to 1,
            "experience_entries" to listOf(experience(id, description = "Второй профиль")),
            "project_entries" to listOf(project(id)),
        ), UUID.randomUUID().toString())
        assertEquals(200, independent.statusCode.value(), independent.body.toString())
        assertEquals("Второй профиль", independent.body!!.path("data").path("experience_entries").get(0).path("description").asText())
        assertEquals("Первый профиль", request(first, HttpMethod.GET, "/api/v1/me/profile").body!!.path("data").path("experience_entries").get(0).path("description").asText())

        val duplicate = request(second, HttpMethod.PATCH, "/api/v1/me/profile", mapOf("expected_version" to 2, "project_entries" to listOf(project(id), project(id) + ("title" to "Another project"))), UUID.randomUUID().toString())
        assertEquals(400, duplicate.statusCode.value())
    }

    @Test
    fun `stale profile version is rejected and employer cannot write candidate collections`() {
        val candidate = seedActor()
        val employer = seedActor(role = "employer")
        val first = request(candidate, HttpMethod.PATCH, "/api/v1/me/profile", mapOf("expected_version" to 1, "experience_entries" to emptyList<Any>()), UUID.randomUUID().toString())
        assertEquals(200, first.statusCode.value())
        val stale = request(candidate, HttpMethod.PATCH, "/api/v1/me/profile", mapOf("expected_version" to 1, "project_entries" to emptyList<Any>()), UUID.randomUUID().toString())
        assertEquals(409, stale.statusCode.value())
        assertEquals("VERSION_CONFLICT", stale.body!!.path("error").path("code").asText())

        val forbidden = request(employer, HttpMethod.PATCH, "/api/v1/me/profile", mapOf("expected_version" to 1, "experience_entries" to emptyList<Any>()), UUID.randomUUID().toString())
        assertEquals(403, forbidden.statusCode.value())
    }

    @Test
    fun `read helpers force candidate declared provenance for stored values`() {
        val id = UUID.randomUUID().toString()
        val mapped = CandidateProfileEntries.experience(mapOf("experience_entries" to listOf(experience(id) + ("provenance" to "test_verified"))))
        assertEquals(1, mapped.size)
        assertEquals("candidate_declared", mapped.single()["provenance"])
        assertTrue(CandidateProfileEntries.experience(emptyMap()).isEmpty())
    }

    private fun assertProfilePatchRejected(actor: ru.fsp.talent.core.TestActor, fields: Map<String, Any?>) {
        val response = request(
            actor,
            HttpMethod.PATCH,
            "/api/v1/me/profile",
            mapOf("expected_version" to 1) + fields,
            UUID.randomUUID().toString(),
        )
        assertEquals(400, response.statusCode.value(), response.body.toString())
        assertEquals("VALIDATION_ERROR", response.body!!.path("error").path("code").asText())
    }

    private fun experience(id: String, start: String = "2023-01", end: String? = null, description: String = "Разработка API"): Map<String, Any?> = mapOf(
        "id" to id,
        "company" to "Компания",
        "role" to "Backend developer",
        "description" to description,
        "start_month" to start,
        "end_month" to end,
        "skill_ids" to listOf("python-basics", "http-api"),
    )

    private fun project(id: String, url: String? = "https://portfolio.invalid/work"): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to "API project",
        "description" to "REST API",
        "url" to url,
        "skill_ids" to listOf("python-basics"),
    )
}
