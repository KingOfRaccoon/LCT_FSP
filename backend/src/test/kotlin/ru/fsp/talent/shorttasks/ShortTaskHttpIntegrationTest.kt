package ru.fsp.talent.shorttasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.TestActor
import ru.fsp.talent.matching.MatchingPolicy
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.UUID

class ShortTaskHttpIntegrationTest : DbTestBase() {
    @Test
    fun `task lists are paged private and role-scoped`() {
        val company = seedActor("employer")
        val candidate = publicCandidate("Linus")
        val companyPage = request(company, HttpMethod.GET, "/api/v1/short-tasks?limit=20")
        val candidatePage = request(candidate, HttpMethod.GET, "/api/v1/short-tasks?limit=20")
        assertEquals(200, companyPage.statusCode.value(), companyPage.body.toString())
        assertEquals("private, no-store", companyPage.headers.getFirst("Cache-Control"))
        assertEquals(200, candidatePage.statusCode.value(), candidatePage.body.toString())
        assertEquals("private, no-store", candidatePage.headers.getFirst("Cache-Control"))
        assertTrue(companyPage.body!!.path("data").path("items").isArray)
        assertTrue(candidatePage.body!!.path("data").path("items").isArray)
    }

    @Test
    fun `company task snapshots its need and cannot be read by another company`() {
        val company = seedActor("employer")
        val foreignCompany = seedActor("employer")
        val candidate = publicCandidate("Ada")
        val hidden = publicCandidate("Hidden")
        val visibleWithoutConsent = publicCandidate("No consent", defaultConsents = false)
        jdbc.update("UPDATE candidate_profiles SET visibility='hidden' WHERE user_id=?::uuid", hidden.id)
        val need = activeNeed(company)
        val beforeQualifications = jdbc.queryForObject("SELECT count(*) FROM qualifications WHERE user_id=?::uuid", Long::class.java, candidate.id)
        val beforeGrants = jdbc.queryForObject("SELECT count(*) FROM contact_grants WHERE candidate_id=?::uuid", Long::class.java, candidate.id)

        val created = request(company, HttpMethod.POST, "/api/v1/short-tasks", exactTaskBody(need, candidate, "secret-answer-74"), UUID.randomUUID().toString())
        assertEquals(201, created.statusCode.value(), created.body.toString())
        val taskId = created.body!!.path("data").path("id").asText()
        assertTrue(created.body!!.path("data").path("accepted_at").isNull)
        assertTrue(taskId.isNotBlank())
        assertFalse(created.body!!.toString().contains("secret-answer-74"))
        assertEquals(need.path("version").asInt(), created.body!!.path("data").path("need_version").asInt())

        val foreignRead = request(foreignCompany, HttpMethod.GET, "/api/v1/short-tasks/$taskId")
        assertEquals(404, foreignRead.statusCode.value())
        val hiddenOffer = request(company, HttpMethod.POST, "/api/v1/short-tasks", exactTaskBody(need, hidden, "hidden-key"), UUID.randomUUID().toString())
        assertEquals(404, hiddenOffer.statusCode.value())
        val unconsentedOffer = request(company, HttpMethod.POST, "/api/v1/short-tasks", exactTaskBody(need, visibleWithoutConsent, "unconsented-key"), UUID.randomUUID().toString())
        assertEquals(404, unconsentedOffer.statusCode.value())

        val changedNeed = request(company, HttpMethod.PATCH, "/api/v1/needs/${need.path("id").asText()}", mapOf(
            "expected_version" to need.path("version").asInt(),
            "title" to "Changed after offer",
        ), UUID.randomUUID().toString())
        assertEquals(200, changedNeed.statusCode.value(), changedNeed.body.toString())
        val reread = request(company, HttpMethod.GET, "/api/v1/short-tasks/$taskId")
        assertEquals(200, reread.statusCode.value(), reread.body.toString())
        assertEquals(need.path("version").asInt(), reread.body!!.path("data").path("need_version").asInt())
        assertEquals("Backend developer", reread.body!!.path("data").path("need_title").asText())
        assertFalse(reread.body!!.toString().contains("secret-answer-74"))
        assertEquals(beforeQualifications, jdbc.queryForObject("SELECT count(*) FROM qualifications WHERE user_id=?::uuid", Long::class.java, candidate.id))
        assertEquals(beforeGrants, jdbc.queryForObject("SELECT count(*) FROM contact_grants WHERE candidate_id=?::uuid", Long::class.java, candidate.id))
    }

    @Test
    fun `candidate separately accepts sharing submits and can revoke company solution access`() {
        val company = seedActor("employer")
        val candidate = publicCandidate("Grace")
        val need = activeNeed(company)
        val created = request(company, HttpMethod.POST, "/api/v1/short-tasks", exactTaskBody(need, candidate, "needle-answer"), UUID.randomUUID().toString())
        assertEquals(201, created.statusCode.value(), created.body.toString())
        val taskId = created.body!!.path("data").path("id").asText()

        val accepted = request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$taskId/accept", mapOf(
            "expected_version" to 1,
            "sharing_notice_version" to created.body!!.path("data").path("sharing_notice").path("version").asText(),
        ), UUID.randomUUID().toString())
        assertEquals(200, accepted.statusCode.value(), accepted.body.toString())
        assertTrue(accepted.body!!.path("data").path("accepted_at").asText().isNotBlank())
        val otherCandidate = publicCandidate("Other")
        assertEquals(404, request(otherCandidate, HttpMethod.GET, "/api/v1/short-tasks/$taskId").statusCode.value())

        val solution = "My email is ${candidate.email}; call +1-202-555-0199"
        val submitted = request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$taskId/submit", mapOf(
            "expected_version" to 2,
            "solution" to solution,
        ), UUID.randomUUID().toString())
        assertEquals(200, submitted.statusCode.value(), submitted.body.toString())
        assertTrue(submitted.body!!.toString().contains(candidate.email))
        assertFalse(submitted.body!!.toString().contains("needle-answer"))

        val companyProjection = request(company, HttpMethod.GET, "/api/v1/short-tasks/$taskId")
        assertEquals(200, companyProjection.statusCode.value(), companyProjection.body.toString())
        assertFalse(companyProjection.body!!.toString().contains(candidate.email))
        assertFalse(companyProjection.body!!.toString().contains("+1-202-555-0199"))
        assertTrue(companyProjection.body!!.path("data").path("solution").asText().contains("[контакт скрыт]"))

        val revokeKey = UUID.randomUUID().toString()
        val revoked = request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$taskId/revoke-sharing", mapOf("expected_version" to 3), revokeKey)
        assertEquals(200, revoked.statusCode.value(), revoked.body.toString())
        val replay = request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$taskId/revoke-sharing", mapOf("expected_version" to 3), revokeKey)
        assertEquals(200, replay.statusCode.value(), replay.body.toString())
        assertTrue(replay.body!!.toString().contains(solution))
        assertTrue(replay.body!!.toString().contains(candidate.email))
        val hiddenAfterRevoke = request(company, HttpMethod.GET, "/api/v1/short-tasks/$taskId")
        assertEquals(200, hiddenAfterRevoke.statusCode.value())
        assertTrue(hiddenAfterRevoke.body!!.path("data").path("solution").isMissingNode || hiddenAfterRevoke.body!!.path("data").path("solution").isNull)
        assertFalse(hiddenAfterRevoke.body!!.toString().contains(solution))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM contact_grants WHERE candidate_id=?::uuid AND revoked_at IS NULL", Long::class.java, candidate.id))
    }

    @Test
    fun `decline and stale transition are safe`() {
        val company = seedActor("employer")
        val candidate = publicCandidate("Linus")
        val need = activeNeed(company)
        val taskBody = mapOf<String, Any?>(
            "need_id" to need.path("id").asText(),
            "candidate_id" to candidate.id,
            "expected_need_version" to need.path("version").asInt(),
            "title" to "Explain a cache design",
            "purpose" to "Discuss approach",
            "instructions" to "Describe tradeoffs and failure cases.",
            "estimated_minutes" to 20,
            "due_at" to Instant.now().plusSeconds(86_400).toString(),
            "evaluation_mode" to "rubric",
            "rubric" to listOf(mapOf("id" to "correctness", "label" to "Correctness", "max_points" to 3), mapOf("id" to "tradeoffs", "label" to "Tradeoffs", "max_points" to 2)),
        )
        val created = request(company, HttpMethod.POST, "/api/v1/short-tasks", taskBody, UUID.randomUUID().toString())
        assertEquals(201, created.statusCode.value(), created.body.toString())
        val taskId = created.body!!.path("data").path("id").asText()

        val declined = request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$taskId/decline", mapOf("expected_version" to 1), UUID.randomUUID().toString())
        assertEquals(200, declined.statusCode.value(), declined.body.toString())
        assertEquals("declined", declined.body!!.path("data").path("status").asText())
        val staleAccept = request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$taskId/accept", mapOf(
            "expected_version" to 1,
            "sharing_notice_version" to created.body!!.path("data").path("sharing_notice").path("version").asText(),
        ), UUID.randomUUID().toString())
        assertEquals(409, staleAccept.statusCode.value())
    }

    @Test
    fun `employer review is rubric-bounded and does not change qualification or contact grant`() {
        val company = seedActor("employer")
        val candidate = publicCandidate("Linus")
        val need = activeNeed(company)
        val beforeQualifications = jdbc.queryForObject("SELECT count(*) FROM qualifications WHERE user_id=?::uuid", Long::class.java, candidate.id)
        val beforeGrants = jdbc.queryForObject("SELECT count(*) FROM contact_grants WHERE candidate_id=?::uuid", Long::class.java, candidate.id)
        val taskBody = mapOf<String, Any?>(
            "need_id" to need.path("id").asText(), "candidate_id" to candidate.id,
            "expected_need_version" to need.path("version").asInt(), "title" to "Explain a cache design",
            "purpose" to "Discuss approach", "instructions" to "Describe tradeoffs and failure cases.",
            "estimated_minutes" to 20, "due_at" to Instant.now().plusSeconds(86_400).toString(),
            "evaluation_mode" to "rubric",
            "rubric" to listOf(mapOf("id" to "correctness", "label" to "Correctness", "max_points" to 3), mapOf("id" to "tradeoffs", "label" to "Tradeoffs", "max_points" to 2)),
        )
        val created = request(company, HttpMethod.POST, "/api/v1/short-tasks", taskBody, UUID.randomUUID().toString())
        assertEquals(201, created.statusCode.value(), created.body.toString())
        val taskId = created.body!!.path("data").path("id").asText()
        assertEquals("short-task-sharing-v1", created.body!!.path("data").path("sharing_notice").path("version").asText())

        val accepted = request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$taskId/accept", mapOf(
            "expected_version" to 1, "sharing_notice_version" to created.body!!.path("data").path("sharing_notice").path("version").asText(),
        ), UUID.randomUUID().toString())
        assertEquals(200, accepted.statusCode.value(), accepted.body.toString())
        val submitted = request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$taskId/submit", mapOf(
            "expected_version" to 2, "solution" to "A bounded cache with explicit invalidation.",
        ), UUID.randomUUID().toString())
        assertEquals(200, submitted.statusCode.value(), submitted.body.toString())
        assertTrue(submitted.body!!.path("data").path("automatic_result").isNull)

        val reviewed = request(company, HttpMethod.POST, "/api/v1/short-tasks/$taskId/review", mapOf(
            "expected_version" to 3,
            "criteria" to listOf(mapOf("id" to "correctness", "score" to 3), mapOf("id" to "tradeoffs", "score" to 1)),
            "feedback" to "Good invalidation explanation; discuss cache stampedes.",
            "source" to "employer",
        ), UUID.randomUUID().toString())
        assertEquals(200, reviewed.statusCode.value(), reviewed.body.toString())
        assertEquals("reviewed", reviewed.body!!.path("data").path("status").asText())
        assertEquals(3, reviewed.body!!.path("data").path("employer_review").path("criteria")[0].path("score").asInt())
        assertEquals(beforeQualifications, jdbc.queryForObject("SELECT count(*) FROM qualifications WHERE user_id=?::uuid", Long::class.java, candidate.id))
        assertEquals(beforeGrants, jdbc.queryForObject("SELECT count(*) FROM contact_grants WHERE candidate_id=?::uuid", Long::class.java, candidate.id))
    }

    @Test
    fun `company can withdraw its own offered task`() {
        val company = seedActor("employer")
        val candidate = publicCandidate("Candidate")
        val need = activeNeed(company)
        val created = request(company, HttpMethod.POST, "/api/v1/short-tasks", exactTaskBody(need, candidate, "withdraw-key"), UUID.randomUUID().toString())
        assertEquals(201, created.statusCode.value(), created.body.toString())
        val taskId = created.body!!.path("data").path("id").asText()
        assertTrue(created.body!!.path("data").path("accepted_at").isNull)

        val withdrawn = request(company, HttpMethod.POST, "/api/v1/short-tasks/$taskId/withdraw", mapOf("expected_version" to 1), UUID.randomUUID().toString())
        assertEquals(200, withdrawn.statusCode.value(), withdrawn.body.toString())
        assertEquals("withdrawn", withdrawn.body!!.path("data").path("status").asText())
        assertTrue(withdrawn.body!!.path("data").path("accepted_at").isNull)
        assertEquals(409, request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$taskId/revoke-sharing", mapOf("expected_version" to 2), UUID.randomUUID().toString()).statusCode.value())
        assertEquals(404, request(seedActor("employer"), HttpMethod.POST, "/api/v1/short-tasks/$taskId/withdraw", mapOf("expected_version" to 1), UUID.randomUUID().toString()).statusCode.value())

        val acceptedTask = request(company, HttpMethod.POST, "/api/v1/short-tasks", exactTaskBody(need, candidate, "accepted-withdraw-key"), UUID.randomUUID().toString())
        assertEquals(201, acceptedTask.statusCode.value(), acceptedTask.body.toString())
        val acceptedTaskId = acceptedTask.body!!.path("data").path("id").asText()
        val accepted = request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$acceptedTaskId/accept", mapOf(
            "expected_version" to 1, "sharing_notice_version" to acceptedTask.body!!.path("data").path("sharing_notice").path("version").asText(),
        ), UUID.randomUUID().toString())
        assertEquals(200, accepted.statusCode.value(), accepted.body.toString())
        val acceptedAt = accepted.body!!.path("data").path("accepted_at").asText()
        assertTrue(acceptedAt.isNotBlank())
        val companyWithdraw = request(company, HttpMethod.POST, "/api/v1/short-tasks/$acceptedTaskId/withdraw", mapOf("expected_version" to 2), UUID.randomUUID().toString())
        assertEquals(200, companyWithdraw.statusCode.value(), companyWithdraw.body.toString())
        assertEquals(acceptedAt, companyWithdraw.body!!.path("data").path("accepted_at").asText())
        val revokeAfterWithdraw = request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$acceptedTaskId/revoke-sharing", mapOf("expected_version" to 3), UUID.randomUUID().toString())
        assertEquals(200, revokeAfterWithdraw.statusCode.value(), revokeAfterWithdraw.body.toString())
        assertEquals("withdrawn", revokeAfterWithdraw.body!!.path("data").path("status").asText())
        assertEquals(acceptedAt, revokeAfterWithdraw.body!!.path("data").path("accepted_at").asText())
    }

    @Test
    fun `company cannot read automatic or manual results after candidate revokes sharing`() {
        val company = seedActor("employer")
        val candidate = publicCandidate("Candidate")
        val need = activeNeed(company)
        val body = mapOf<String, Any?>(
            "need_id" to need.path("id").asText(), "candidate_id" to candidate.id,
            "expected_need_version" to need.path("version").asInt(), "title" to "Bounded design task",
            "purpose" to "Discuss the approach", "instructions" to "Describe a safe cache design.",
            "estimated_minutes" to 20, "due_at" to Instant.now().plusSeconds(86_400).toString(),
            "evaluation_mode" to "rubric",
            "rubric" to listOf(mapOf("id" to "correctness", "label" to "Correctness", "max_points" to 3)),
        )
        val created = request(company, HttpMethod.POST, "/api/v1/short-tasks", body, UUID.randomUUID().toString())
        assertEquals(201, created.statusCode.value(), created.body.toString())
        val taskId = created.body!!.path("data").path("id").asText()
        val accepted = request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$taskId/accept", mapOf(
            "expected_version" to 1, "sharing_notice_version" to created.body!!.path("data").path("sharing_notice").path("version").asText(),
        ), UUID.randomUUID().toString())
        assertEquals(200, accepted.statusCode.value(), accepted.body.toString())
        val submitted = request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$taskId/submit", mapOf(
            "expected_version" to 2, "solution" to "Design answer with no contact data",
        ), UUID.randomUUID().toString())
        assertEquals(200, submitted.statusCode.value(), submitted.body.toString())
        val reviewBody = mapOf<String, Any?>(
            "expected_version" to 3, "criteria" to listOf(mapOf("id" to "correctness", "score" to 3)),
            "feedback" to "Clear answer", "source" to "employer",
        )
        val reviewKey = UUID.randomUUID().toString()
        val reviewed = request(company, HttpMethod.POST, "/api/v1/short-tasks/$taskId/review", reviewBody, reviewKey)
        assertEquals(200, reviewed.statusCode.value(), reviewed.body.toString())
        assertEquals("Clear answer", reviewed.body!!.path("data").path("employer_review").path("feedback").asText())

        val revoked = request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$taskId/revoke-sharing", mapOf("expected_version" to 4), UUID.randomUUID().toString())
        assertEquals(200, revoked.statusCode.value(), revoked.body.toString())
        val replayedReview = request(company, HttpMethod.POST, "/api/v1/short-tasks/$taskId/review", reviewBody, reviewKey)
        assertEquals(404, replayedReview.statusCode.value(), replayedReview.body.toString())
        assertFalse(replayedReview.body!!.toString().contains("Clear answer"))
        val employerRead = request(company, HttpMethod.GET, "/api/v1/short-tasks/$taskId")
        assertEquals(200, employerRead.statusCode.value(), employerRead.body.toString())
        assertTrue(employerRead.body!!.path("data").path("solution").isNull)
        assertTrue(employerRead.body!!.path("data").path("automatic_result").isNull)
        assertTrue(employerRead.body!!.path("data").path("employer_review").isNull)
        val candidateRead = request(candidate, HttpMethod.GET, "/api/v1/short-tasks/$taskId")
        assertEquals("Design answer with no contact data", candidateRead.body!!.path("data").path("solution").asText())
        assertEquals("Clear answer", candidateRead.body!!.path("data").path("employer_review").path("feedback").asText())
    }

    @Test
    fun `company list waits for queued sharing revocation and projects the committed row`() {
        val company = seedActor("employer")
        val candidate = publicCandidate("Candidate")
        val need = activeNeed(company)
        val created = request(company, HttpMethod.POST, "/api/v1/short-tasks", exactTaskBody(need, candidate, "match-this-answer"), UUID.randomUUID().toString())
        assertEquals(201, created.statusCode.value(), created.body.toString())
        val taskId = created.body!!.path("data").path("id").asText()
        assertEquals(200, request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$taskId/accept", mapOf(
            "expected_version" to 1, "sharing_notice_version" to created.body!!.path("data").path("sharing_notice").path("version").asText(),
        ), UUID.randomUUID().toString()).statusCode.value())
        val submitted = request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$taskId/submit", mapOf(
            "expected_version" to 2, "solution" to "match-this-answer",
        ), UUID.randomUUID().toString())
        assertEquals(200, submitted.statusCode.value(), submitted.body.toString())
        assertTrue(submitted.body!!.path("data").path("automatic_result").path("matched").asBoolean())

        val connection = requireNotNull(jdbc.dataSource).connection
        val executor = Executors.newFixedThreadPool(2)
        try {
            connection.autoCommit = false
            connection.prepareStatement("SELECT id FROM short_tasks WHERE id=?::uuid FOR UPDATE").use { statement ->
                statement.setString(1, taskId)
                statement.executeQuery().use { result -> check(result.next()) }
            }
            val revoke = executor.submit<org.springframework.http.ResponseEntity<com.fasterxml.jackson.databind.JsonNode>> {
                request(candidate, HttpMethod.POST, "/api/v1/short-tasks/$taskId/revoke-sharing", mapOf("expected_version" to 3), UUID.randomUUID().toString())
            }
            val lockDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var revokeWaiting = false
            while (System.nanoTime() < lockDeadline && !revokeWaiting) {
                revokeWaiting = (jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%short_tasks%'",
                    Int::class.java,
                ) ?: 0) > 0
                if (!revokeWaiting) Thread.sleep(10)
            }
            assertTrue(revokeWaiting, "candidate revoke did not reach its row lock")
            val list = executor.submit<org.springframework.http.ResponseEntity<com.fasterxml.jackson.databind.JsonNode>> {
                request(company, HttpMethod.GET, "/api/v1/short-tasks?limit=100")
            }
            assertThrows(TimeoutException::class.java) { list.get(150, TimeUnit.MILLISECONDS) }

            connection.commit()
            assertEquals(200, revoke.get(5, TimeUnit.SECONDS).statusCode.value())
            val listed = list.get(5, TimeUnit.SECONDS)
            assertEquals(200, listed.statusCode.value(), listed.body.toString())
            val task = listed.body!!.path("data").path("items").first { it.path("id").asText() == taskId }
            assertTrue(task.path("solution").isNull)
            assertTrue(task.path("automatic_result").isNull)
        } finally {
            runCatching { connection.rollback() }
            connection.close()
            executor.shutdownNow()
        }
    }

    private fun activeNeed(company: TestActor): com.fasterxml.jackson.databind.JsonNode {
        val created = request(company, HttpMethod.POST, "/api/v1/needs", needBody(), UUID.randomUUID().toString())
        assertEquals(201, created.statusCode.value(), created.body.toString())
        val needId = created.body!!.path("data").path("id").asText()
        val confirmed = request(company, HttpMethod.POST, "/api/v1/needs/$needId/competencies/confirm", mapOf(
            "expected_version" to 1,
            "confirmed_skill_ids" to listOf("python-basics"),
        ), UUID.randomUUID().toString())
        assertEquals(200, confirmed.statusCode.value(), confirmed.body.toString())
        return confirmed.body!!.path("data")
    }

    private fun exactTaskBody(need: com.fasterxml.jackson.databind.JsonNode, candidate: TestActor, key: String) = mapOf<String, Any?>(
        "need_id" to need.path("id").asText(),
        "candidate_id" to candidate.id,
        "expected_need_version" to need.path("version").asInt(),
        "title" to "Backend task",
        "purpose" to "Check a bounded API design",
        "instructions" to "Return the exact protocol value.",
        "estimated_minutes" to 15,
        "due_at" to Instant.now().plusSeconds(86_400).toString(),
        "evaluation_mode" to "exact",
        "expected_answer" to key,
        "rubric" to emptyList<Any>(),
    )

    private fun publicCandidate(name: String, defaultConsents: Boolean = true): TestActor = seedActor("candidate", defaultConsents = defaultConsents).also { candidate ->
        jdbc.update(
            "UPDATE candidate_profiles SET visibility='public', data=?::jsonb WHERE user_id=?::uuid",
            objectMapper.writeValueAsString(mapOf("display_name" to name, "email" to candidate.email, "phone" to "+1-202-555-0199")),
            candidate.id,
        )
    }

    private fun needBody() = mapOf<String, Any?>(
        "title" to "Backend developer",
        "description" to "Build and maintain backend services.",
        "specialization_id" to MatchingPolicy.SPECIALIZATION_ID,
        "target_grade" to "junior",
        "work_mode" to "remote",
        "city" to null,
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
    )
}
