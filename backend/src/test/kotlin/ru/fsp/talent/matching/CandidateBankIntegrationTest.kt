package ru.fsp.talent.matching

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.TestActor
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

class CandidateBankIntegrationTest : DbTestBase() {
    @Test
    fun `search is employer only and returns only public verified profiles without private data`() {
        val employer = seedActor("employer")
        val before = searchAllIds(employer).toSet()
        val visible = candidate("Visible", visibility = "public", contacts = mapOf("email" to "private@example.test"))
        val hidden = candidate("Hidden", visibility = "hidden")
        val unverified = candidate("Unverified", verified = false, visibility = "public")

        val response = search(employer)

        assertEquals(200, response.statusCode.value(), response.body.toString())
        assertEquals("private, no-store", response.headers.getFirst("Cache-Control"))
        val data = response.body!!.path("data")
        val allItems = searchAllItems(employer)
        assertEquals(setOf(visible.id), allItems.map { it.path("id").asText() }.toSet() - before)
        assertEquals("any", data.path("filter_context").path("category_status").asText())
        assertFalse(allItems.toString().contains("private@example.test"))
        assertFalse(allItems.toString().contains("contacts"))
        assertFalse(allItems.toString().contains("notes"))

        assertEquals(403, search(seedActor("candidate")).statusCode.value())
        assertEquals(422, search(seedActor("employer", verified = false)).statusCode.value())
        assertFalse(allItems.any { it.path("id").asText() == hidden.id })
        assertFalse(allItems.any { it.path("id").asText() == unverified.id })
    }

    @Test
    fun `grade filter separates verified categories from explicitly declared categories`() {
        val employer = seedActor("employer")
        val beforeConfirmed = searchAllIds(employer, grade = "junior", categoryStatus = "confirmed").toSet()
        val beforeUnconfirmed = searchAllIds(employer, grade = "junior", categoryStatus = "unconfirmed").toSet()
        val confirmedJunior = candidate("Confirmed", verifiedGrade = "junior", declaredGrade = "middle")
        val declaredJunior = candidate("Declared", declaredGrade = "junior")
        candidate("Confirmed middle", verifiedGrade = "middle", declaredGrade = "junior")

        val confirmed = search(employer, grade = "junior", categoryStatus = "confirmed")
        assertEquals(200, confirmed.statusCode.value(), confirmed.body.toString())
        assertEquals(setOf(confirmedJunior.id), searchAllIds(employer, grade = "junior", categoryStatus = "confirmed").toSet() - beforeConfirmed)

        val unconfirmed = search(employer, grade = "junior", categoryStatus = "unconfirmed")
        assertEquals(200, unconfirmed.statusCode.value(), unconfirmed.body.toString())
        val unconfirmedItems = searchAllItems(employer, grade = "junior", categoryStatus = "unconfirmed")
        assertEquals(setOf(declaredJunior.id), unconfirmedItems.map { it.path("id").asText() }.toSet() - beforeUnconfirmed)
        assertEquals("unconfirmed", unconfirmedItems.first { it.path("id").asText() == declaredJunior.id }.path("category_evidence").asText())
    }

    @Test
    fun `skill filter includes declared and test evidenced skills with distinct provenance`() {
        val employer = seedActor("employer")
        val before = searchAllIds(employer, skillIds = listOf("python-basics")).toSet()
        val declared = candidate("Declared skill", declaredSkills = listOf("python-basics"))
        val tested = candidate("Tested skill", verifiedSkills = listOf("python-basics"))
        candidate("Other skill", declaredSkills = listOf("http-api"))

        val response = search(employer, skillIds = listOf("python-basics"))

        assertEquals(200, response.statusCode.value(), response.body.toString())
        val items = searchAllItems(employer, skillIds = listOf("python-basics"))
        assertEquals(setOf(declared.id, tested.id), items.map { it.path("id").asText() }.toSet() - before)
        val declaredItem = items.first { it.path("id").asText() == declared.id }
        val testedItem = items.first { it.path("id").asText() == tested.id }
        assertEquals("candidate_declared", declaredItem.path("skills").first().path("provenance").asText())
        assertEquals("test_verified", testedItem.path("competency_evidence").first().path("status").asText())
    }

    @Test
    fun `fsp filter requires linked valid participant evidence and confirmed team membership`() {
        val employer = seedActor("employer")
        val beforeHasEvidence = searchAllIds(employer, fspStatus = "has_evidence").toSet()
        val beforeNoEvidence = searchAllIds(employer, fspStatus = "no_evidence").toSet()
        val individual = candidate("Linked individual")
        linkFsp(individual, "linked_confirmed")
        achievement(individual, "individual-prize", mapOf(
            "result" to "Prize", "demo" to true, "participant_linked" to true,
        ))

        val unlinked = candidate("Unlinked")
        achievement(unlinked, "unlinked-prize", mapOf("result" to "Prize", "demo" to true))

        val teamWithoutConfirmation = candidate("Unconfirmed team")
        linkFsp(teamWithoutConfirmation, "linked_confirmed")
        achievement(teamWithoutConfirmation, "team-prize", mapOf(
            "result" to "Prize", "demo" to true, "participant_linked" to true,
            "team_event" to true, "membership_confirmed" to false,
        ))

        val missingParticipantLink = candidate("Missing participant linkage")
        linkFsp(missingParticipantLink, "linked_confirmed")
        achievement(missingParticipantLink, "missing-participant-link", mapOf(
            "event_name" to "Missing participant linkage event", "result" to "Prize", "demo" to true,
        ))

        val confirmedTeam = candidate("Confirmed team")
        linkFsp(confirmedTeam, "linked_confirmed")
        achievement(confirmedTeam, "confirmed-team-prize", mapOf(
            "result" to "Prize", "demo" to true, "participant_linked" to true,
            "team_event" to true, "membership_confirmed" to true,
        ))

        val hasEvidence = search(employer, fspStatus = "has_evidence")
        assertEquals(200, hasEvidence.statusCode.value(), hasEvidence.body.toString())
        assertEquals(setOf(individual.id, confirmedTeam.id), searchAllIds(employer, fspStatus = "has_evidence").toSet() - beforeHasEvidence)

        val noEvidence = search(employer, fspStatus = "no_evidence")
        assertEquals(200, noEvidence.statusCode.value(), noEvidence.body.toString())
        assertEquals(
            setOf(unlinked.id, teamWithoutConfirmation.id, missingParticipantLink.id),
            searchAllIds(employer, fspStatus = "no_evidence").toSet() - beforeNoEvidence,
        )
        val unconfirmedItem = searchAllItems(employer, fspStatus = "no_evidence")
            .first { it.path("id").asText() == missingParticipantLink.id }
        assertEquals("none", unconfirmedItem.path("fsp_signal").asText())
        val evidence = unconfirmedItem.path("fsp_evidence")
            .first { it.path("event_title").asText() == "Missing participant linkage event" }
        assertFalse(evidence.path("participant_linked").asBoolean())
        assertFalse(evidence.path("used_for_ranking").asBoolean())
    }

    @Test
    fun `cursor is bound to filters and requesting company`() {
        val employer = seedActor("employer")
        val first = candidate("First", declaredSkills = listOf("python-basics"))
        val second = candidate("Second", declaredSkills = listOf("python-basics"))
        val third = candidate("Third", declaredSkills = listOf("http-api"))

        val orderedIds = searchAllIds(employer, skillIds = listOf("python-basics"))
        assertTrue(orderedIds.containsAll(listOf(first.id, second.id)))
        val firstPage = search(employer, skillIds = listOf("python-basics"), limit = 1)
        assertEquals(200, firstPage.statusCode.value(), firstPage.body.toString())
        val firstData = firstPage.body!!.path("data")
        assertEquals(1, firstData.path("items").size())
        assertEquals(orderedIds.first(), firstData.path("items").first().path("id").asText())
        assertTrue(firstData.path("next_cursor").isTextual)
        val cursor = firstData.path("next_cursor").asText()

        val nextPage = search(employer, skillIds = listOf("python-basics"), limit = 1, cursor = cursor)
        assertEquals(200, nextPage.statusCode.value(), nextPage.body.toString())
        assertEquals(orderedIds[1], nextPage.body!!.path("data").path("items").first().path("id").asText())
        assertNotEquals(firstData.path("items").first().path("id").asText(), nextPage.body!!.path("data").path("items").first().path("id").asText())

        val cursorParts = cursor.split('.')
        val payload = String(Base64.getUrlDecoder().decode(cursorParts[0]), StandardCharsets.UTF_8)
        val editedPayload = payload.substringBeforeLast('|') + "|999"
        val forgedCursor = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(editedPayload.toByteArray(StandardCharsets.UTF_8)) + "." + cursorParts[1]
        val tamperedOffset = search(employer, skillIds = listOf("python-basics"), limit = 1, cursor = forgedCursor)
        assertEquals(400, tamperedOffset.statusCode.value())

        val changedFilters = search(employer, skillIds = listOf("http-api"), limit = 1, cursor = cursor)
        assertEquals(400, changedFilters.statusCode.value())
        assertFalse(ids(changedFilters.body!!.path("data")).contains(third.id))

        val otherCompany = seedActor("employer")
        val crossCompany = search(otherCompany, skillIds = listOf("python-basics"), limit = 1, cursor = cursor)
        assertEquals(400, crossCompany.statusCode.value())
    }

    @Test
    fun `search is read only`() {
        val employer = seedActor("employer")
        candidate("Candidate")
        val before = jdbc.queryForObject("SELECT count(*) FROM employer_needs", Int::class.java)
        val replayCount = jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Int::class.java)

        val response = search(employer)

        assertEquals(200, response.statusCode.value(), response.body.toString())
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM employer_needs", Int::class.java))
        assertEquals(replayCount, jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Int::class.java))
    }

    @Test
    fun `legacy public visibility is not effective until current publication consents are accepted`() {
        val employer = seedActor("employer")
        val legacy = seedActor("candidate", defaultConsents = false)
        jdbc.update(
            "UPDATE candidate_profiles SET visibility = 'public', data = ?::jsonb WHERE user_id = ?::uuid",
            objectMapper.writeValueAsString(mapOf(
                "display_name" to "Legacy public profile",
                "specialization_id" to MatchingPolicy.SPECIALIZATION_ID,
                "allowed_work_modes" to listOf("remote"),
                "cities" to emptyList<String>(),
                "max_weekly_hours" to 40,
                "skills" to emptyList<Any>(),
            )),
            legacy.id,
        )

        assertFalse(searchAllIds(employer).contains(legacy.id))
        assertEquals(404, request(employer, HttpMethod.GET, "/api/v1/candidates/${legacy.id}").statusCode.value())
        val preview = request(legacy, HttpMethod.GET, "/api/v1/me/public-preview")
        assertEquals("hidden", preview.body!!.path("data").path("visibility").asText())

        assertTrue(consentAccept(legacy, "processing").statusCode.is2xxSuccessful)
        assertTrue(consentAccept(legacy, "publication").statusCode.is2xxSuccessful)
        assertTrue(searchAllIds(employer).contains(legacy.id))
        assertEquals(200, request(employer, HttpMethod.GET, "/api/v1/candidates/${legacy.id}").statusCode.value())
    }

    private fun search(
        employer: TestActor,
        grade: String? = null,
        categoryStatus: String = "any",
        skillIds: List<String> = emptyList(),
        fspStatus: String = "any",
        limit: Int = 20,
        cursor: String? = null,
    ) = request(
        actor = employer,
        method = HttpMethod.POST,
        path = "/api/v1/candidates/search",
        body = mapOf(
            "specialization_id" to null,
            "grade" to grade,
            "category_status" to categoryStatus,
            "skill_ids" to skillIds,
            "fsp_status" to fspStatus,
            "limit" to limit,
            "cursor" to cursor,
        ),
    )

    private fun ids(data: com.fasterxml.jackson.databind.JsonNode): List<String> =
        data.path("items").map { it.path("id").asText() }

    private fun searchAllIds(
        employer: TestActor,
        grade: String? = null,
        categoryStatus: String = "any",
        skillIds: List<String> = emptyList(),
        fspStatus: String = "any",
    ): List<String> = searchAllItems(employer, grade, categoryStatus, skillIds, fspStatus)
        .map { it.path("id").asText() }

    private fun searchAllItems(
        employer: TestActor,
        grade: String? = null,
        categoryStatus: String = "any",
        skillIds: List<String> = emptyList(),
        fspStatus: String = "any",
    ): List<com.fasterxml.jackson.databind.JsonNode> {
        val items = mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
        var cursor: String? = null
        do {
            val response = search(
                employer,
                grade = grade,
                categoryStatus = categoryStatus,
                skillIds = skillIds,
                fspStatus = fspStatus,
                limit = 100,
                cursor = cursor,
            )
            assertEquals(200, response.statusCode.value(), response.body.toString())
            val data = response.body!!.path("data")
            data.path("items").forEach(items::add)
            cursor = data.path("next_cursor").takeIf { it.isTextual }?.asText()
        } while (cursor != null)
        return items
    }

    private fun candidate(
        name: String,
        verified: Boolean = true,
        visibility: String = "public",
        verifiedGrade: String? = null,
        declaredGrade: String? = null,
        declaredSkills: List<String> = emptyList(),
        verifiedSkills: List<String> = emptyList(),
        contacts: Map<String, Any?> = emptyMap(),
    ): TestActor {
        val actor = seedActor("candidate", verified)
        val profile = buildMap<String, Any?> {
            put("display_name", name)
            put("specialization_id", MatchingPolicy.SPECIALIZATION_ID)
            put("skills", declaredSkills.map { mapOf("skill_id" to it, "provenance" to "candidate_declared") })
            if (declaredGrade != null) put("declared_grade", declaredGrade)
        }
        jdbc.update(
            "UPDATE candidate_profiles SET visibility = ?, data = ?::jsonb, contacts = ?::jsonb WHERE user_id = ?::uuid",
            visibility, objectMapper.writeValueAsString(profile), objectMapper.writeValueAsString(contacts), actor.id,
        )
        if (verifiedGrade != null) {
            val qualification = mapOf("competency_summary" to verifiedSkills.map {
                mapOf("skill_id" to it, "status" to "verified")
            })
            jdbc.update(
                "INSERT INTO qualifications(id, user_id, specialization_id, grade, data) VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb)",
                UUID.randomUUID().toString(), actor.id, MatchingPolicy.SPECIALIZATION_ID, verifiedGrade,
                objectMapper.writeValueAsString(qualification),
            )
        } else if (verifiedSkills.isNotEmpty()) {
            // An ungraded profile may still have test evidence, as it must remain distinct from category evidence.
            val qualification = mapOf("competency_summary" to verifiedSkills.map {
                mapOf("skill_id" to it, "status" to "verified")
            })
            jdbc.update(
                "INSERT INTO qualifications(id, user_id, specialization_id, grade, data) VALUES (?::uuid, ?::uuid, ?, 'junior', ?::jsonb)",
                UUID.randomUUID().toString(), actor.id, MatchingPolicy.SPECIALIZATION_ID,
                objectMapper.writeValueAsString(qualification),
            )
        }
        return actor
    }

    private fun linkFsp(candidate: TestActor, status: String) {
        jdbc.update(
            "INSERT INTO fsp_links(id, user_id, external_id, binding_status, source, data) VALUES (?::uuid, ?::uuid, ?, ?, 'fsp_mock', ?::jsonb)",
            UUID.randomUUID().toString(), candidate.id, "external-${candidate.id}", status,
            objectMapper.writeValueAsString(mapOf("demo" to true)),
        )
    }

    private fun achievement(candidate: TestActor, sourceId: String, data: Map<String, Any?>, validity: String = "valid") {
        jdbc.update(
            "INSERT INTO achievements(id, user_id, source_record_id, validity, data) VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb)",
            UUID.randomUUID().toString(), candidate.id, sourceId, validity, objectMapper.writeValueAsString(data),
        )
    }
}
