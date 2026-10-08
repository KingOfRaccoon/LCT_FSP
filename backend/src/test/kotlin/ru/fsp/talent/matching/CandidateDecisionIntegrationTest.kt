package ru.fsp.talent.matching

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.TestActor
import java.util.UUID

class CandidateDecisionIntegrationTest : DbTestBase() {
    @Autowired
    private lateinit var needs: NeedService

    @Test
    fun `compare uses fresh public candidates and returns private no-store explanations without private fields`() {
        val employer = owner()
        val needId = activeNeed(employer)
        val first = publicCandidate()
        val second = publicCandidate()
        val needVersion = jdbc.queryForObject("SELECT version FROM employer_needs WHERE id = ?::uuid", Int::class.java, needId)
        val qualificationGrades = jdbc.queryForList(
            "SELECT grade FROM qualifications WHERE user_id IN (?::uuid, ?::uuid) ORDER BY user_id",
            String::class.java,
            first.id,
            second.id,
        )

        val response = request(
            actor = employer,
            method = HttpMethod.POST,
            path = "/api/v1/candidates/compare",
            body = mapOf("need_id" to needId, "candidate_ids" to listOf(first.id, second.id)),
        )
        assertEquals(200, response.statusCode.value())
        assertEquals("private, no-store", response.headers.getFirst("Cache-Control"))
        val data = response.body!!.path("data")
        assertEquals(2, data.path("items").size())
        assertEquals(MatchingPolicy.POLICY_VERSION, data.path("policy_version").asText())
        data.path("items").forEach { item ->
            assertTrue(item.path("fit").has("checks"))
            assertFalse(item.path("fit").has("score"))
            assertTrue(item.path("interview_kit").path("common_questions").isArray)
            assertFalse(item.path("candidate").has("contacts"))
            assertFalse(item.path("candidate").has("email"))
            assertFalse(item.path("candidate").has("notes"))
            assertFalse(item.path("candidate").findValue("correct_option_id") != null)
            assertFalse(item.path("candidate").findValue("answer_key") != null)
        }
        assertEquals(needVersion, jdbc.queryForObject("SELECT version FROM employer_needs WHERE id = ?::uuid", Int::class.java, needId))
        assertEquals(qualificationGrades, jdbc.queryForList(
            "SELECT grade FROM qualifications WHERE user_id IN (?::uuid, ?::uuid) ORDER BY user_id",
            String::class.java,
            first.id,
            second.id,
        ))

        val review = request(
            actor = employer,
            method = HttpMethod.GET,
            path = "/api/v1/needs/$needId/candidate-review?candidate_id=${first.id}",
        )
        assertEquals(200, review.statusCode.value())
        assertEquals("private, no-store", review.headers.getFirst("Cache-Control"))

        jdbc.update("UPDATE candidate_profiles SET visibility = 'hidden' WHERE user_id = ?::uuid", second.id)
        val hiddenResponse = request(
            actor = employer,
            method = HttpMethod.POST,
            path = "/api/v1/candidates/compare",
            body = mapOf("need_id" to needId, "candidate_ids" to listOf(first.id, second.id)),
        )
        assertEquals(404, hiddenResponse.statusCode.value())
        assertEquals("NOT_FOUND", hiddenResponse.body!!.path("error").path("code").asText())
    }

    @Test
    fun `candidate review rejects another company need without revealing it`() {
        val employer = owner()
        val otherEmployer = owner()
        val foreignNeed = activeNeed(otherEmployer)
        val candidate = publicCandidate()
        val response = request(
            actor = employer,
            method = HttpMethod.GET,
            path = "/api/v1/needs/$foreignNeed/candidate-review?candidate_id=${candidate.id}",
        )
        assertEquals(404, response.statusCode.value())
    }

    @Test
    fun `candidate-authored phone in city is absent from remote and hybrid comparison and review`() {
        val employer = owner()
        val phone = "+7 (999) 123-45-67"
        val candidate = publicCandidate(cities = listOf(phone, "Moscow"), workModes = listOf("remote", "hybrid"))
        val second = publicCandidate()

        val remoteNeed = activeNeed(employer)
        val remoteCompare = request(
            actor = employer,
            method = HttpMethod.POST,
            path = "/api/v1/candidates/compare",
            body = mapOf("need_id" to remoteNeed, "candidate_ids" to listOf(candidate.id, second.id)),
        )
        assertEquals(200, remoteCompare.statusCode.value())
        assertFalse(remoteCompare.body!!.toString().contains(phone))
        val remoteReview = request(
            actor = employer,
            method = HttpMethod.GET,
            path = "/api/v1/needs/$remoteNeed/candidate-review?candidate_id=${candidate.id}",
        )
        assertEquals(200, remoteReview.statusCode.value())
        assertFalse(remoteReview.body!!.toString().contains(phone))

        val hybridNeed = activeNeed(employer, workMode = "hybrid", city = "Moscow")
        val hybridCompare = request(
            actor = employer,
            method = HttpMethod.POST,
            path = "/api/v1/candidates/compare",
            body = mapOf("need_id" to hybridNeed, "candidate_ids" to listOf(candidate.id, second.id)),
        )
        assertEquals(200, hybridCompare.statusCode.value())
        assertFalse(hybridCompare.body!!.toString().contains(phone))
        val hybridFit = hybridCompare.body!!.path("data").path("items").first().path("fit")
        val cityCheck = hybridFit.path("checks").first { it.path("type").asText() == "city" }
        assertTrue(cityCheck.path("observed").asText().contains("[контакт скрыт]"))
        val hybridReview = request(
            actor = employer,
            method = HttpMethod.GET,
            path = "/api/v1/needs/$hybridNeed/candidate-review?candidate_id=${candidate.id}",
        )
        assertEquals(200, hybridReview.statusCode.value())
        assertFalse(hybridReview.body!!.toString().contains(phone))
        val reviewCityCheck = hybridReview.body!!.path("data").path("fit").path("checks")
            .first { it.path("type").asText() == "city" }
        assertTrue(reviewCityCheck.path("observed").asText().contains("[контакт скрыт]"))
    }

    private fun publicCandidate(cities: List<String> = emptyList(), workModes: List<String> = listOf("remote")): TestActor {
        val candidate = seedActor(role = "candidate")
        val profile = mapOf(
            "display_name" to "Public candidate",
            "specialization_id" to MatchingPolicy.SPECIALIZATION_ID,
            "declared_grade" to "junior",
            "allowed_work_modes" to workModes,
            "cities" to cities,
            "max_weekly_hours" to 40,
            "skills" to listOf(mapOf("skill_id" to "python-basics", "provenance" to "candidate_declared")),
        )
        jdbc.update(
            "UPDATE candidate_profiles SET visibility = 'public', data = ?::jsonb WHERE user_id = ?::uuid",
            objectMapper.writeValueAsString(profile), candidate.id,
        )
        val qualification = mapOf("competency_summary" to listOf(mapOf("skill_id" to "python-basics", "status" to "verified")))
        jdbc.update(
            """INSERT INTO qualifications(id, user_id, specialization_id, grade, data)
               VALUES (?::uuid, ?::uuid, ?, 'junior', ?::jsonb)""".trimIndent(),
            UUID.randomUUID().toString(), candidate.id, MatchingPolicy.SPECIALIZATION_ID,
            objectMapper.writeValueAsString(qualification),
        )
        return candidate
    }

    private fun owner(): TestActor = seedActor(role = "employer")

    private fun activeNeed(owner: TestActor, workMode: String = "remote", city: String? = null): String {
        val actor = Actor(owner.id, owner.email, owner.role, owner.companyId, UUID.randomUUID().toString(), emailVerified = true)
        val created = needs.create(actor, mapOf(
            "title" to "Python backend developer",
            "description" to "Build backend services.",
            "specialization_id" to MatchingPolicy.SPECIALIZATION_ID,
            "target_grade" to "junior",
            "work_mode" to workMode,
            "city" to city,
            "workload_hours_per_week" to 40,
            "salary" to mapOf(
                "min_kopecks" to 10_000_000,
                "max_kopecks" to 20_000_000,
                "currency" to "RUB",
                "period" to "month",
                "basis" to "gross",
                "workload_type" to "full_time",
                "hours_per_week" to 40,
            ),
            "requirements" to listOf(mapOf("skill_id" to "python-basics", "importance" to "required")),
        ))
        needs.confirm(actor, created.getValue("id").toString(), mapOf(
            "expected_version" to 1,
            "confirmed_skill_ids" to listOf("python-basics"),
        ))
        return created.getValue("id").toString()
    }
}
