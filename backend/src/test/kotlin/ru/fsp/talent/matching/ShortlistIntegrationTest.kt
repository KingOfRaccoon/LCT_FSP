package ru.fsp.talent.matching

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.TestActor
import java.util.UUID

class ShortlistIntegrationTest : DbTestBase() {
    @Test
    fun `shortlist preserves unrelated entries and hides currently private candidate`() {
        val company = seedActor("employer")
        val otherCompany = seedActor("employer")
        val first = publicCandidate("First")
        val second = publicCandidate("Second")
        val key = UUID.randomUUID().toString()
        val created = create(company, listOf(first.id, second.id), key)
        assertEquals(201, created.statusCode.value(), created.body.toString())
        val shortlistId = created.body!!.path("data").path("id").asText()
        assertEquals(setOf(first.id, second.id), ids(created.body!!))

        val replay = create(company, listOf(first.id, second.id), key)
        assertEquals(201, replay.statusCode.value())
        assertEquals(shortlistId, replay.body!!.path("data").path("id").asText())

        jdbc.update("UPDATE candidate_profiles SET visibility='hidden' WHERE user_id=?::uuid", first.id)
        val refreshed = request(company, HttpMethod.GET, "/api/v1/shortlists/$shortlistId")
        assertEquals(200, refreshed.statusCode.value())
        assertEquals(setOf(second.id), ids(refreshed.body!!))
        assertEquals(2, jdbc.queryForObject("SELECT jsonb_array_length(data->'candidate_ids') FROM shortlists WHERE id=?::uuid", Int::class.java, shortlistId))

        val restored = request(company, HttpMethod.PATCH, "/api/v1/shortlists/$shortlistId", mapOf("expected_version" to 1, "name" to "Updated shortlist"), UUID.randomUUID().toString())
        assertEquals(200, restored.statusCode.value())
        assertEquals("Updated shortlist", restored.body!!.path("data").path("name").asText())
        assertEquals(setOf(second.id), ids(restored.body!!))
        val stale = request(company, HttpMethod.PATCH, "/api/v1/shortlists/$shortlistId", mapOf("expected_version" to 1, "candidate_ids" to listOf(second.id)), UUID.randomUUID().toString())
        assertEquals(409, stale.statusCode.value())

        val removed = request(company, HttpMethod.PATCH, "/api/v1/shortlists/$shortlistId", mapOf("expected_version" to 2, "candidate_ids" to listOf(second.id)), UUID.randomUUID().toString())
        assertEquals(200, removed.statusCode.value())
        assertEquals(setOf(second.id), ids(removed.body!!))
        assertEquals(setOf(second.id), jdbc.queryForList("SELECT jsonb_array_elements_text(data->'candidate_ids') FROM shortlists WHERE id=?::uuid", String::class.java, shortlistId).toSet())

        val foreign = request(otherCompany, HttpMethod.GET, "/api/v1/shortlists/$shortlistId")
        assertEquals(404, foreign.statusCode.value())
        val candidateRead = request(first, HttpMethod.GET, "/api/v1/shortlists")
        assertEquals(403, candidateRead.statusCode.value())
    }

    @Test
    fun `adding hidden candidate is rejected and does not mutate existing shortlist`() {
        val company = seedActor("employer")
        val visible = publicCandidate("Visible")
        val hidden = publicCandidate("Hidden")
        val created = create(company, listOf(visible.id), UUID.randomUUID().toString())
        assertEquals(201, created.statusCode.value(), created.body.toString())
        val shortlistId = created.body!!.path("data").path("id").asText()
        jdbc.update("UPDATE candidate_profiles SET visibility='hidden' WHERE user_id=?::uuid", hidden.id)

        val patch = request(company, HttpMethod.PATCH, "/api/v1/shortlists/$shortlistId", mapOf("expected_version" to 1, "candidate_ids" to listOf(visible.id, hidden.id)), UUID.randomUUID().toString())
        assertEquals(404, patch.statusCode.value())
        val read = request(company, HttpMethod.GET, "/api/v1/shortlists/$shortlistId")
        assertEquals(setOf(visible.id), ids(read.body!!))
        assertEquals(1, read.body!!.path("data").path("version").asInt())
    }

    private fun create(company: TestActor, ids: List<String>, key: String) = request(
        company,
        HttpMethod.POST,
        "/api/v1/shortlists",
        mapOf("name" to "Candidates", "need_id" to null, "query_snapshot" to null, "candidate_ids" to ids),
        key,
    )

    private fun ids(body: com.fasterxml.jackson.databind.JsonNode): Set<String> = body.path("data").path("candidate_ids")
        .map { it.asText() }.toSet()

    private fun publicCandidate(name: String): TestActor = seedActor("candidate").also { candidate ->
        jdbc.update(
            "UPDATE candidate_profiles SET visibility='public', data=?::jsonb WHERE user_id=?::uuid",
            objectMapper.writeValueAsString(mapOf("display_name" to name)),
            candidate.id,
        )
    }
}
