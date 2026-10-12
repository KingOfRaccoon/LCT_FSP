package ru.fsp.talent.vacancies

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.TestActor
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VacancyCaseCorrectionsIntegrationTest : DbTestBase() {
    @Test
    fun `publishing requires an owned active need and copies its actual numeric salary and conditions`() {
        val employer = seedActor("employer")
        val foreignEmployer = seedActor("employer")
        val needId = seedNeed(employer, salaryMin = 23_500_000, salaryMax = 31_250_000)
        val foreignNeedId = seedNeed(foreignEmployer)

        val missing = publish(employer, emptyMap())
        assertEquals(400, missing.statusCode.value(), missing.body.toString())

        val foreign = publish(employer, publishBody(foreignNeedId))
        assertEquals(404, foreign.statusCode.value(), foreign.body.toString())

        val requestBody = publishBody(needId, minSalary = 23_500_000, maxSalary = 31_250_000)
        val publishKey = UUID.randomUUID().toString()
        val result = publish(employer, requestBody, publishKey)
        assertEquals(200, result.statusCode.value(), result.body.toString())
        val vacancy = result.body!!.path("data")
        assertEquals(needId, vacancy.path("need_id").asText())
        assertEquals("middle", vacancy.path("target_grade").asText())
        assertEquals("hybrid", vacancy.path("work_mode").asText())
        assertEquals("Казань", vacancy.path("city").asText())
        assertEquals(32, vacancy.path("workload_hours_per_week").asInt())
        assertEquals(23_500_000, vacancy.path("salary").path("min_kopecks").asLong())
        assertEquals(31_250_000, vacancy.path("salary").path("max_kopecks").asLong())
        assertEquals("net", vacancy.path("salary").path("basis").asText())
        val replay = publish(employer, requestBody, publishKey)
        assertEquals(vacancy.path("id").asText(), replay.body!!.path("data").path("id").asText())
        val conflict = publish(employer, requestBody + ("description" to "Other body"), publishKey)
        assertEquals(409, conflict.statusCode.value(), conflict.body.toString())

        val stale = publish(employer, requestBody + ("salary_min_kopecks" to 10_000_000))
        assertEquals(409, stale.statusCode.value(), stale.body.toString())
        assertEquals("VERSION_CONFLICT", stale.body!!.path("error").path("code").asText())
        val missingSalary = publish(employer, requestBody - "salary_min_kopecks")
        assertEquals(400, missingSalary.statusCode.value(), missingSalary.body.toString())
        val fractionalSalary = publish(employer, requestBody + ("salary_min_kopecks" to 10_000_000.5))
        assertEquals(400, fractionalSalary.statusCode.value(), fractionalSalary.body.toString())

        val invalidNeed = seedNeed(employer, grade = "chief")
        val invalid = publish(employer, publishBody(invalidNeed, grade = "chief"))
        assertEquals(400, invalid.statusCode.value(), invalid.body.toString())
        val invalidModeNeed = seedNeed(employer, workMode = "hybrid_remote")
        assertEquals(400, publish(employer, publishBody(invalidModeNeed, workMode = "hybrid_remote")).statusCode.value())
        val invalidBasisNeed = seedNeed(employer, salaryBasis = "negotiable")
        assertEquals(400, publish(employer, publishBody(invalidBasisNeed, salaryBasis = "negotiable")).statusCode.value())
    }

    @Test
    fun `application retry is idempotent and contacts are scoped to the vacancy company`() {
        val employer = seedActor("employer")
        val otherEmployer = seedActor("employer")
        val candidate = seedActor()
        val needId = seedNeed(employer)
        val vacancy = publish(employer, publishBody(needId)).body!!.path("data").path("id").asText()
        setContacts(candidate, listOf(
            mapOf("type" to "email", "value" to "visible@example.test", "enabled" to true),
            mapOf("type" to "phone", "value" to "+7 900 111-22-33", "enabled" to true),
        ))

        val body = mapOf("cover_letter" to "Письмо", "contact_channel_types" to listOf("email", "phone"))
        val key = UUID.randomUUID().toString()
        val first = apply(candidate, vacancy, body, key)
        val retry = apply(candidate, vacancy, body, key)
        assertEquals(200, first.statusCode.value(), first.body.toString())
        assertEquals(first.body!!.path("data").path("id").asText(), retry.body!!.path("data").path("id").asText())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM candidate_applications WHERE vacancy_id=?::uuid AND candidate_id=?::uuid", Int::class.java, vacancy, candidate.id))

        val applicationId = first.body!!.path("data").path("id").asText()
        val own = request(candidate, HttpMethod.GET, "/api/v1/me/applications")
        assertFalse(own.body!!.path("data").path("items").first().path("contact_access_revoked").asBoolean())
        assertTrue(own.body!!.path("data").path("items").first().path("has_selected_contacts").asBoolean())
        val employerApplications = request(employer, HttpMethod.GET, "/api/v1/vacancies/company/applications")
            .body!!.path("data").path("items").first()
        assertFalse(employerApplications.path("contact_access_revoked").asBoolean())
        assertTrue(employerApplications.path("has_selected_contacts").asBoolean())

        // Permission remains limited to selected types, and a selected channel is re-read from current enabled settings.
        setContacts(candidate, listOf(
            mapOf("type" to "email", "value" to "new-visible@example.test", "enabled" to false),
            mapOf("type" to "phone", "value" to "+7 900 555-44-33", "enabled" to true),
        ))
        val contacts = request(employer, HttpMethod.GET, "/api/v1/vacancies/applications/$applicationId/contacts")
        assertEquals(200, contacts.statusCode.value(), contacts.body.toString())
        assertEquals(1, contacts.body!!.path("data").path("channels").size())
        assertEquals("phone", contacts.body!!.path("data").path("channels").first().path("type").asText())
        assertEquals("+7 900 555-44-33", contacts.body!!.path("data").path("channels").first().path("value").asText())
        val foreign = request(otherEmployer, HttpMethod.GET, "/api/v1/vacancies/applications/$applicationId/contacts")
        assertEquals(404, foreign.statusCode.value(), foreign.body.toString())

        val revoke = request(candidate, HttpMethod.POST, "/api/v1/me/applications/$applicationId/contact-access/revoke", emptyMap<String, Any?>(), UUID.randomUUID().toString())
        assertEquals(200, revoke.statusCode.value(), revoke.body.toString())
        val blocked = request(employer, HttpMethod.GET, "/api/v1/vacancies/applications/$applicationId/contacts")
        assertEquals(404, blocked.statusCode.value(), blocked.body.toString())
        assertTrue(request(candidate, HttpMethod.GET, "/api/v1/me/applications").body!!.path("data").path("items").first().path("contact_access_revoked").asBoolean())
        assertFalse(request(candidate, HttpMethod.GET, "/api/v1/me/applications").body!!.path("data").path("items").first().path("has_selected_contacts").asBoolean())
        val revokedApplication = request(employer, HttpMethod.GET, "/api/v1/vacancies/company/applications").body!!.path("data").path("items").first()
        assertTrue(revokedApplication.path("contact_access_revoked").asBoolean())
        assertFalse(revokedApplication.path("has_selected_contacts").asBoolean())
        // A replay returns the original mutation response but never restores the separately revoked permission.
        apply(candidate, vacancy, body, key)
        assertEquals(404, request(employer, HttpMethod.GET, "/api/v1/vacancies/applications/$applicationId/contacts").statusCode.value())
    }

    @Test
    fun `application consent is independent from publication and processing revocation clears private reads`() {
        val employer = seedActor("employer")
        val candidate = seedActor()
        val needId = seedNeed(employer)
        val vacancy = publish(employer, publishBody(needId)).body!!.path("data").path("id").asText()
        setContacts(candidate, listOf(mapOf("type" to "email", "value" to "candidate@example.test", "enabled" to true)))

        consentRevoke(candidate, "publication")
        val submitted = apply(candidate, vacancy, mapOf("cover_letter" to "Личное письмо", "contact_channel_types" to listOf("email")), UUID.randomUUID().toString())
        assertEquals(200, submitted.statusCode.value(), submitted.body.toString())
        val applicationId = submitted.body!!.path("data").path("id").asText()
        assertEquals(200, request(employer, HttpMethod.GET, "/api/v1/vacancies/applications/$applicationId/contacts").statusCode.value())

        consentRevoke(candidate, "processing")
        val own = request(candidate, HttpMethod.GET, "/api/v1/me/applications")
        assertEquals(200, own.statusCode.value())
        val ownItem = own.body!!.path("data").path("items").first()
        assertEquals(applicationId, ownItem.path("id").asText())
        assertEquals("submitted", ownItem.path("status").asText())
        assertTrue(ownItem.path("created_at").isTextual)
        assertTrue(ownItem.path("cover_letter").isMissingNode)
        assertTrue(ownItem.path("vacancy_title").isMissingNode)
        assertFalse(ownItem.path("contact_access_revoked").asBoolean())
        assertTrue(ownItem.path("has_selected_contacts").asBoolean())

        val employerItem = request(employer, HttpMethod.GET, "/api/v1/vacancies/company/applications").body!!.path("data").path("items").first()
        assertFalse(employerItem.path("candidate_processing_active").asBoolean())
        assertFalse(employerItem.path("contact_access_revoked").asBoolean())
        assertTrue(employerItem.path("has_selected_contacts").asBoolean())
        assertTrue(employerItem.path("candidate_name").isMissingNode)
        assertTrue(employerItem.path("cover_letter").isMissingNode)
        assertEquals(404, request(employer, HttpMethod.GET, "/api/v1/vacancies/applications/$applicationId/contacts").statusCode.value())
    }

    @Test
    fun `application submission requires processing consent and rejects disabled contact channels`() {
        val employer = seedActor("employer")
        val candidate = seedActor(defaultConsents = false)
        val needId = seedNeed(employer)
        val vacancy = publish(employer, publishBody(needId)).body!!.path("data").path("id").asText()
        setContacts(candidate, listOf(mapOf("type" to "email", "value" to "disabled@example.test", "enabled" to false)))

        val noConsent = apply(candidate, vacancy, mapOf("contact_channel_types" to listOf<String>()), UUID.randomUUID().toString())
        assertEquals(403, noConsent.statusCode.value(), noConsent.body.toString())
        consentAccept(candidate, "processing")
        consentAccept(candidate, "publication")
        val missingContactChoice = apply(candidate, vacancy, emptyMap(), UUID.randomUUID().toString())
        assertEquals(400, missingContactChoice.statusCode.value(), missingContactChoice.body.toString())
        val oversizedLetter = apply(candidate, vacancy, mapOf("cover_letter" to "x".repeat(5_001), "contact_channel_types" to emptyList<String>()), UUID.randomUUID().toString())
        assertEquals(400, oversizedLetter.statusCode.value(), oversizedLetter.body.toString())
        val disabled = apply(candidate, vacancy, mapOf("contact_channel_types" to listOf("email")), UUID.randomUUID().toString())
        assertEquals(400, disabled.statusCode.value(), disabled.body.toString())
        val unknown = apply(candidate, vacancy, mapOf("contact_channel_types" to listOf("account_email")), UUID.randomUUID().toString())
        assertEquals(400, unknown.statusCode.value(), unknown.body.toString())
        val duplicateTypes = apply(candidate, vacancy, mapOf("contact_channel_types" to listOf("email", "email")), UUID.randomUUID().toString())
        assertEquals(400, duplicateTypes.statusCode.value(), duplicateTypes.body.toString())
        val noContactShare = apply(candidate, vacancy, mapOf("contact_channel_types" to emptyList<String>()), UUID.randomUUID().toString())
        assertEquals(200, noContactShare.statusCode.value(), noContactShare.body.toString())
        val applicationId = noContactShare.body!!.path("data").path("id").asText()
        val own = request(candidate, HttpMethod.GET, "/api/v1/me/applications").body!!.path("data").path("items").first()
        assertFalse(own.path("contact_access_revoked").asBoolean())
        assertFalse(own.path("has_selected_contacts").asBoolean())
        val emptyContacts = request(employer, HttpMethod.GET, "/api/v1/vacancies/applications/$applicationId/contacts")
        assertEquals(200, emptyContacts.statusCode.value(), emptyContacts.body.toString())
        assertEquals(0, emptyContacts.body!!.path("data").path("channels").size())
    }

    @Test
    fun `concurrent application submissions create one row and never overwrite its contact permission`() {
        val employer = seedActor("employer")
        val candidate = seedActor()
        val needId = seedNeed(employer)
        val vacancy = publish(employer, publishBody(needId)).body!!.path("data").path("id").asText()
        setContacts(candidate, listOf(mapOf("type" to "email", "value" to "candidate@example.test", "enabled" to true)))
        val requestStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val body = mapOf("contact_channel_types" to listOf("email"))
        val results = listOf(
            executor.submit<Int> { requestStarted.await(); apply(candidate, vacancy, body, UUID.randomUUID().toString()).statusCode.value() },
            executor.submit<Int> { requestStarted.await(); apply(candidate, vacancy, body, UUID.randomUUID().toString()).statusCode.value() },
        )
        requestStarted.countDown()
        try {
            val codes = results.map { it.get(15, TimeUnit.SECONDS) }.sorted()
            assertEquals(listOf(200, 409), codes)
        } finally {
            executor.shutdownNow()
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM candidate_applications WHERE vacancy_id=?::uuid AND candidate_id=?::uuid", Int::class.java, vacancy, candidate.id))
    }

    private fun publish(actor: TestActor, body: Map<String, Any?>, key: String = UUID.randomUUID().toString()) = request(
        actor, HttpMethod.POST, "/api/v1/vacancies", body, key,
    )

    private fun publishBody(
        needId: String,
        minSalary: Long = 18_000_000,
        maxSalary: Long = 24_000_000,
        grade: String = "middle",
        workMode: String = "hybrid",
        salaryBasis: String = "net",
    ) = mapOf(
        "need_id" to needId,
        "title" to "Backend разработчик",
        "description" to "Разработка сервиса",
        "specialization_id" to "backend-python-v1",
        "target_grade" to grade,
        "work_mode" to workMode,
        "city" to "Казань",
        "workload_hours_per_week" to 32,
        "salary_min_kopecks" to minSalary,
        "salary_max_kopecks" to maxSalary,
        "salary_basis" to salaryBasis,
    )

    private fun apply(actor: TestActor, vacancyId: String, body: Map<String, Any?>, key: String) = request(
        actor, HttpMethod.POST, "/api/v1/vacancies/$vacancyId/apply", body, key,
    )

    private fun seedNeed(
        actor: TestActor,
        salaryMin: Long = 18_000_000,
        salaryMax: Long = 24_000_000,
        grade: String = "middle",
        workMode: String = "hybrid",
        salaryBasis: String = "net",
    ): String {
        val id = UUID.randomUUID().toString()
        val data = mapOf(
            "title" to "Backend разработчик",
            "description" to "Разработка сервиса",
            "specialization_id" to "backend-python-v1",
            "target_grade" to grade,
            "work_mode" to workMode,
            "city" to "Казань",
            "workload_hours_per_week" to 32,
            "salary" to mapOf("min_kopecks" to salaryMin, "max_kopecks" to salaryMax, "currency" to "RUB", "period" to "month", "basis" to salaryBasis, "workload_type" to "part_time", "hours_per_week" to 32),
            "requirements" to listOf(mapOf("skill_id" to "python-basics", "importance" to "required")),
        )
        jdbc.update("INSERT INTO employer_needs(id,company_id,status,data) VALUES(?::uuid,?::uuid,'active',?::jsonb)", id, actor.companyId, objectMapper.writeValueAsString(data))
        return id
    }

    private fun setContacts(candidate: TestActor, channels: List<Map<String, Any?>>) {
        jdbc.update("UPDATE candidate_profiles SET contacts=?::jsonb WHERE user_id=?::uuid", objectMapper.writeValueAsString(mapOf("channels" to channels)), candidate.id)
    }

    private fun consentRevoke(actor: TestActor, scope: String) {
        val response = request(actor, HttpMethod.POST, "/api/v1/me/consents", mapOf("scope" to scope, "version" to "1.0", "action" to "revoke"), UUID.randomUUID().toString())
        assertEquals(200, response.statusCode.value(), response.body.toString())
    }
}
