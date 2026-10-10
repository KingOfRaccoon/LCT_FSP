package ru.fsp.talent.matching

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.TestActor
import java.util.UUID

class NeedInsightsHttpIntegrationTest : DbTestBase() {
    @Autowired
    private lateinit var matchingService: MatchingService

    private val salaryFixtureIds = mutableListOf<String>()

    @AfterEach
    fun removeSalaryExpectationsCreatedByThisTest() {
        salaryFixtureIds.forEach { id ->
            jdbc.update("UPDATE candidate_profiles SET data = data - 'expected_salary' WHERE user_id=?::uuid", id)
        }
    }

    @Test
    fun `owner can read insights for an existing draft need`() {
        val employer = seedActor("employer")
        val needId = createNeed(employer, needBody(hours = 39, basis = "net", workloadType = "part_time"))

        val insights = request(employer, HttpMethod.GET, "/api/v1/needs/$needId/insights?expected_version=1")

        assertEquals(200, insights.statusCode.value(), insights.body.toString())
        val data = insights.body!!.path("data")
        assertEquals(needId, data.path("need_id").asText())
        assertEquals(1, data.path("need_version").asInt())
        assertEquals("current_visible_platform_profiles", data.path("scope").asText())
        assertNotNull(data.path("generated_at").takeUnless { it.isMissingNode || it.isNull })
        assertNotNull(data.path("policy_version").takeUnless { it.isMissingNode || it.isNull })
    }

    @Test
    fun `insights return not found for a need owned by another employer`() {
        val owner = seedActor("employer")
        val otherEmployer = seedActor("employer")
        val needId = createNeed(owner)

        val insights = request(otherEmployer, HttpMethod.GET, "/api/v1/needs/$needId/insights?expected_version=1")

        assertEquals(404, insights.statusCode.value(), insights.body.toString())
    }

    @Test
    fun `insights use the current public consent-gated pool and reveal no candidate identity`() {
        val employer = seedActor("employer")
        val needId = createNeed(employer, needBody(hours = 39, basis = "net", workloadType = "part_time"))
        val baseline = request(employer, HttpMethod.GET, "/api/v1/needs/$needId/insights?expected_version=1").body!!.path("data")
        val visible = publicCandidate("Private Candidate Name")
        val hidden = seedActor("candidate")
        val revoked = publicCandidate("Revoked Candidate")
        val revokedConsent = request(revoked, HttpMethod.POST, "/api/v1/me/consents", mapOf(
            "scope" to "publication", "version" to "1.0", "action" to "revoke",
        ), UUID.randomUUID().toString())
        assertEquals(200, revokedConsent.statusCode.value(), revokedConsent.body.toString())
        val unverified = seedActor("candidate", verified = false, defaultConsents = false)
        val noConsent = seedActor("candidate", defaultConsents = false)

        val response = request(employer, HttpMethod.GET, "/api/v1/needs/$needId/insights?expected_version=1")

        assertEquals(200, response.statusCode.value(), response.body.toString())
        val data = response.body!!.path("data")
        assertEquals(1, data.path("counts").path("profile_pool").asInt() - baseline.path("counts").path("profile_pool").asInt())
        assertEquals(1, data.path("counts").path("needs_confirmation").asInt() - baseline.path("counts").path("needs_confirmation").asInt())
        assertFalse(data.path("salary").path("summary_available").asBoolean())
        assertTrue(data.path("salary").path("unknown_count").isNull)
        assertTrue(data.path("salary").path("compatible_count").isNull)
        assertTrue(data.path("salary").path("low_sample").asBoolean())
        assertTrue(data.path("salary").path("median_min_kopecks").isNull)
        assertTrue(data.path("salary").path("median_max_kopecks").isNull)
        assertFalse(data.toString().contains(visible.id))
        assertFalse(data.toString().contains(visible.email))
        assertFalse(data.toString().contains("Private Candidate Name"))
        assertFalse(data.toString().contains(hidden.id))
        assertFalse(data.toString().contains(revoked.id))
        assertFalse(data.toString().contains(unverified.id))
        assertFalse(data.toString().contains(noConsent.id))
    }

    @Test
    fun `insights reject stale versions and archived needs explicitly`() {
        val employer = seedActor("employer")
        val needId = createNeed(employer)

        val stale = request(employer, HttpMethod.GET, "/api/v1/needs/$needId/insights?expected_version=2")
        assertEquals(409, stale.statusCode.value(), stale.body.toString())
        assertEquals("VERSION_CONFLICT", stale.body!!.path("error").path("code").asText())

        val archived = request(employer, HttpMethod.PATCH, "/api/v1/needs/$needId", mapOf(
            "expected_version" to 1,
            "status" to "archived",
        ), UUID.randomUUID().toString())
        assertEquals(200, archived.statusCode.value(), archived.body.toString())
        val insights = request(employer, HttpMethod.GET, "/api/v1/needs/$needId/insights?expected_version=2")
        assertEquals(409, insights.statusCode.value(), insights.body.toString())
        assertEquals("INVALID_STATE", insights.body!!.path("error").path("code").asText())
    }

    @Test
    fun `salary insights suppress tiny cohort aggregates and arbitrary need-range counts`() {
        val employer = seedActor("employer")
        val (hours, basis) = unusedSalaryDimensions()
        val expectation = mapOf(
            "min_kopecks" to 12_000_000,
            "max_kopecks" to 13_000_000,
            "currency" to "RUB",
            "period" to "month",
            "basis" to basis,
            "workload_type" to "part_time",
            "hours_per_week" to hours,
        )
        publicCandidate("Private salary", expectedSalary = expectation)
        val overlappingNeed = createNeed(employer, needBody(hours, basis, "part_time", 12_000_000, 13_000_000))
        val nonOverlappingNeed = createNeed(employer, needBody(hours, basis, "part_time", 80_000_000, 90_000_000))

        val overlapping = request(employer, HttpMethod.GET, "/api/v1/needs/$overlappingNeed/insights?expected_version=1")
        val nonOverlapping = request(employer, HttpMethod.GET, "/api/v1/needs/$nonOverlappingNeed/insights?expected_version=1")

        assertEquals(200, overlapping.statusCode.value(), overlapping.body.toString())
        assertEquals(200, nonOverlapping.statusCode.value(), nonOverlapping.body.toString())
        val firstSalary = overlapping.body!!.path("data").path("salary")
        val secondSalary = nonOverlapping.body!!.path("data").path("salary")
        assertEquals("fixed_visible_specialization_grade_cohort", firstSalary.path("scope").asText())
        assertFalse(firstSalary.path("summary_available").asBoolean())
        assertTrue(firstSalary.path("low_sample").asBoolean())
        listOf("known_count", "compatible_count", "unknown_count", "incompatible_count", "within_range_count",
            "median_min_kopecks", "median_max_kopecks").forEach { key ->
            assertTrue(firstSalary.path(key).isNull, "$key must be suppressed below the privacy threshold")
        }
        assertTrue(secondSalary.path("within_range_count").isNull, "arbitrary salary ranges must not expose a count")
        assertTrue(firstSalary.path("within_range_count").isNull, "arbitrary salary ranges must not expose a count")
        assertFalse(firstSalary.path("range_count_available").asBoolean())
        assertEquals("privacy_protected", firstSalary.path("range_count_suppression_reason").asText())
    }

    @Test
    fun `salary medians require five comparable profiles and use coarse fixed bins`() {
        val employer = seedActor("employer")
        val (hours, basis) = unusedSalaryDimensions()
        val needId = createNeed(employer, needBody(hours, basis, "part_time"))
        val minima = listOf(11_234_567L, 12_345_678L, 13_456_789L, 14_567_890L, 15_678_901L)
        minima.forEachIndexed { index, minimum ->
            publicCandidate("Salary cohort $index", expectedSalary = mapOf(
                "min_kopecks" to minimum,
                "max_kopecks" to minimum + 1_000_000,
                "currency" to "RUB",
                "period" to "month",
                "basis" to basis,
                "workload_type" to "part_time",
                "hours_per_week" to hours,
            ))
        }

        val response = request(employer, HttpMethod.GET, "/api/v1/needs/$needId/insights?expected_version=1")

        assertEquals(200, response.statusCode.value(), response.body.toString())
        val salary = response.body!!.path("data").path("salary")
        assertTrue(salary.path("summary_available").asBoolean())
        assertTrue(salary.path("compatible_count").asInt() >= 5)
        assertEquals("fixed_visible_specialization_grade_cohort", salary.path("scope").asText())
        assertTrue(salary.path("median_min_kopecks").asLong() % 1_000_000L == 0L)
        assertTrue(salary.path("median_max_kopecks").asLong() % 1_000_000L == 0L)
        assertTrue(salary.path("within_range_count").isNull)
        assertFalse(salary.path("range_count_available").asBoolean())
    }

    private fun unusedSalaryDimensions(): Pair<Int, String> {
        val cohort = matchingService.insightProfiles().filter { profile ->
            profile["specialization_id"] == MatchingPolicy.SPECIALIZATION_ID &&
                (profile["verified_grade"] == "junior" ||
                    (profile["verified_grade"] == null && profile["declared_grade"] == "junior"))
        }
        for (hours in 1..39) {
            for (basis in listOf("gross", "net")) {
                val existing = cohort.count { profile ->
                    val data = profile["profile_data"] as? Map<*, *> ?: return@count false
                    val salary = data["expected_salary"] as? Map<*, *> ?: return@count false
                    salary["currency"] == "RUB" && salary["period"] == "month" &&
                        salary["basis"] == basis && salary["workload_type"] == "part_time" &&
                        (salary["hours_per_week"] as? Number)?.toInt() == hours
                }
                if (existing == 0) {
                    assertEquals(0, existing, "selected dimension must have an empty baseline compatible cohort")
                    return hours to basis
                }
            }
        }
        error("No unused part-time salary dimension is available in the current visible junior cohort")
    }

    @Test
    fun `insights distinguish declared-only and absent skill signals and keep relaxation counterfactual`() {
        val employer = seedActor("employer")
        val needId = createNeed(employer)
        val activated = request(employer, HttpMethod.POST, "/api/v1/needs/$needId/competencies/confirm", mapOf(
            "expected_version" to 1,
            "confirmed_skill_ids" to listOf("python-basics"),
        ), UUID.randomUUID().toString())
        assertEquals(200, activated.statusCode.value(), activated.body.toString())
        val baselineInsights = request(employer, HttpMethod.GET, "/api/v1/needs/$needId/insights?expected_version=2")
        assertEquals(200, baselineInsights.statusCode.value(), baselineInsights.body.toString())
        val baselineCounts = baselineInsights.body!!.path("data").path("counts")
        val baselineSearch = request(employer, HttpMethod.POST, "/api/v1/matches/search", mapOf(
            "need_id" to needId,
            "limit" to 50,
            "cursor" to null,
            "strict_confirmed_only" to false,
        ), UUID.randomUUID().toString())
        assertEquals(200, baselineSearch.statusCode.value(), baselineSearch.body.toString())
        val declared = publicCandidate("Declared only", skills = listOf("python-basics"))
        publicCandidate("No skill signal")
        qualifiedPublicCandidate("Verified grade, unrelated skill")

        val response = request(employer, HttpMethod.GET, "/api/v1/needs/$needId/insights?expected_version=2")

        assertEquals(200, response.statusCode.value(), response.body.toString())
        val requirement = response.body!!.path("data").path("requirements").first()
        val baselineRequirement = baselineInsights.body!!.path("data").path("requirements").first()
        assertEquals(0, requirement.path("test_confirmed_count").asInt() - baselineRequirement.path("test_confirmed_count").asInt())
        assertEquals(1, requirement.path("declared_only_count").asInt() - baselineRequirement.path("declared_only_count").asInt())
        assertEquals(2, requirement.path("no_skill_signal_count").asInt() - baselineRequirement.path("no_skill_signal_count").asInt())
        assertEquals(1, requirement.path("additional_confirmed_matches_if_relaxed").asInt() - baselineRequirement.path("additional_confirmed_matches_if_relaxed").asInt())
        assertFalse(response.body!!.path("data").toString().contains(declared.id))
        val search = request(employer, HttpMethod.POST, "/api/v1/matches/search", mapOf(
            "need_id" to needId,
            "limit" to 50,
            "cursor" to null,
            "strict_confirmed_only" to false,
        ), UUID.randomUUID().toString())
        assertEquals(200, search.statusCode.value(), search.body.toString())
        val currentInsights = request(employer, HttpMethod.GET, "/api/v1/needs/$needId/insights?expected_version=2")
        assertEquals(200, currentInsights.statusCode.value(), currentInsights.body.toString())
        assertEquals(search.body!!.path("data").path("group_counts").path("confirmed").asInt() - baselineSearch.body!!.path("data").path("group_counts").path("confirmed").asInt(),
            currentInsights.body!!.path("data").path("counts").path("confirmed").asInt() - baselineCounts.path("confirmed").asInt())
        assertEquals(search.body!!.path("data").path("group_counts").path("needs_clarification").asInt() - baselineSearch.body!!.path("data").path("group_counts").path("needs_clarification").asInt(),
            currentInsights.body!!.path("data").path("counts").path("needs_clarification").asInt() - baselineCounts.path("needs_clarification").asInt())
        assertEquals(search.body!!.path("data").path("group_counts").path("needs_confirmation").asInt() - baselineSearch.body!!.path("data").path("group_counts").path("needs_confirmation").asInt(),
            currentInsights.body!!.path("data").path("counts").path("needs_confirmation").asInt() - baselineCounts.path("needs_confirmation").asInt())
    }

    private fun createNeed(employer: TestActor, body: Map<String, Any?> = needBody()): String {
        val created = request(employer, HttpMethod.POST, "/api/v1/needs", body, UUID.randomUUID().toString())
        assertEquals(201, created.statusCode.value(), created.body.toString())
        return created.body!!.path("data").path("id").asText()
    }

    private fun publicCandidate(
        name: String,
        skills: List<String> = emptyList(),
        expectedSalary: Map<String, Any?>? = null,
    ): TestActor {
        val candidate = seedActor("candidate")
        val profile = request(candidate, HttpMethod.PATCH, "/api/v1/me/profile", mapOf(
            "expected_version" to 1,
            "display_name" to name,
            "declared_grade" to "junior",
            "allowed_work_modes" to listOf("remote"),
            "max_weekly_hours" to 40,
            "skills" to skills,
            "expected_salary" to expectedSalary,
        ), UUID.randomUUID().toString())
        assertEquals(200, profile.statusCode.value(), profile.body.toString())
        if (expectedSalary != null) salaryFixtureIds += candidate.id
        val published = request(candidate, HttpMethod.PATCH, "/api/v1/me/visibility", mapOf(
            "expected_version" to profile.body!!.path("data").path("version").asInt(),
            "visibility" to "public",
        ), UUID.randomUUID().toString())
        assertEquals(200, published.statusCode.value(), published.body.toString())
        return candidate
    }

    private fun qualifiedPublicCandidate(name: String): TestActor {
        val candidate = publicCandidate(name)
        jdbc.update(
            """INSERT INTO qualifications(id, user_id, specialization_id, grade, data)
               VALUES (?::uuid, ?::uuid, ?, 'junior', ?::jsonb)""".trimIndent(),
            UUID.randomUUID().toString(),
            candidate.id,
            MatchingPolicy.SPECIALIZATION_ID,
            objectMapper.writeValueAsString(mapOf(
                "competency_summary" to listOf(mapOf("skill_id" to "sql-basics", "status" to "verified")),
            )),
        )
        return candidate
    }

    private fun needBody(
        hours: Int = 40,
        basis: String = "gross",
        workloadType: String = "full_time",
        minSalary: Long = 10_000_000,
        maxSalary: Long = 20_000_000,
    ) = mapOf(
        "title" to "Python role",
        "description" to "Work on Python APIs",
        "specialization_id" to MatchingPolicy.SPECIALIZATION_ID,
        "target_grade" to "junior",
        "work_mode" to "remote",
        "city" to null,
        "workload_hours_per_week" to hours,
        "salary" to mapOf(
            "min_kopecks" to minSalary,
            "max_kopecks" to maxSalary,
            "currency" to "RUB",
            "period" to "month",
            "basis" to basis,
            "workload_type" to workloadType,
            "hours_per_week" to hours,
        ),
        "requirements" to listOf(mapOf("skill_id" to "python-basics", "importance" to "required")),
    )
}
