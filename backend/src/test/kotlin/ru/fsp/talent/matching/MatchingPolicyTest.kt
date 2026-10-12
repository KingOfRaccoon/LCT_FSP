package ru.fsp.talent.matching

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class MatchingPolicyTest {
    private val mapper = ObjectMapper()
    private val fixtureFile = listOf(
        File("../docs/validation/matching-cases.v1.json"),
        File("docs/validation/matching-cases.v1.json"),
    ).first(File::isFile)
    private val fixture = mapper.readValue(fixtureFile, Map::class.java)
    private val profiles = fixture["profiles"] as List<Map<String, Any?>>
    private val needs = (fixture["needs"] as List<Map<String, Any?>>).associateBy { it["id"].toString() }
    private val ids = fixture["candidate_ids"] as Map<String, String>

    @Test
    fun `all normative matching cases preserve exact groups and order`() {
        val cases = fixture["cases"] as List<Map<String, Any?>>
        cases.forEach { case ->
            val result = MatchingPolicy.computeMatches(
                profiles,
                needs.getValue(case["need_id"].toString()),
                case["confirmed_only"] == true,
            )
            val expected = case["expected_groups"] as Map<String, List<String>>
            assertEquals(expected["confirmed"], result.groups.confirmed.map { it.candidateId }, case["id"].toString())
            assertEquals(expected["needs_clarification"], result.groups.needsClarification.map { it.candidateId }, case["id"].toString())
            assertEquals(expected["needs_confirmation"], result.groups.needsConfirmation.map { it.candidateId }, case["id"].toString())
            if (expected.containsKey("excluded")) assertEquals(expected["excluded"], result.excludedCandidateIds, case["id"].toString())
        }
    }

    @Test
    fun `event title and date do not affect FSP ordering`() {
        val base = computeMain().groups.confirmed.map { it.candidateId }
        val changed = copyProfiles().map { p ->
            if (p["id"] == ids["prize"]) {
                val records = p["fsp_results"] as MutableList<MutableMap<String, Any?>>
                records[0]["event_title"] = "Prestigious national final"
                records[0]["date"] = "1999-01-01"
            }
            p
        }
        assertEquals(base, MatchingPolicy.computeMatches(changed, needs.getValue("need-junior-remote")).groups.confirmed.map { it.candidateId })
    }

    @Test
    fun `duplicate FSP source records do not increase evidence`() {
        val base = computeMain().groups.confirmed.map { it.candidateId }
        val changed = copyProfiles().map { p ->
            if (p["id"] == ids["participant"]) {
                val records = p["fsp_results"] as MutableList<MutableMap<String, Any?>>
                repeat(100) { records.add(records.first().toMutableMap()) }
            }
            p
        }
        assertEquals(base, MatchingPolicy.computeMatches(changed, needs.getValue("need-junior-remote")).groups.confirmed.map { it.candidateId })
    }

    @Test
    fun `FSP evidence cannot compensate for a missing preferred skill`() {
        val changed = copyProfiles().map { p ->
            if (p["id"] == ids["no_framework"]) p["fsp_results"] = listOf(
                mapOf("source_record_id" to "new-prize", "result" to "prize", "valid" to true,
                    "participant_linked" to true, "team" to false, "membership_confirmed" to null),
            )
            p
        }
        val order = MatchingPolicy.computeMatches(changed, needs.getValue("need-junior-remote")).groups.confirmed.map { it.candidateId }
        assertTrue(order.indexOf(ids.getValue("no_framework")) > order.indexOf(ids.getValue("plain")))
    }

    @Test
    fun `team prize without confirmed member linkage gives no ordinal`() {
        val changed = copyProfiles().map { p ->
            if (p["id"] == ids["unlinked"]) p["fsp_results"] = listOf(
                mapOf("source_record_id" to "team-prize", "result" to "prize", "valid" to true,
                    "participant_linked" to true, "team" to true, "membership_confirmed" to false),
            )
            p
        }
        val group = MatchingPolicy.computeMatches(changed, needs.getValue("need-junior-remote")).groups.confirmed
        assertTrue(group.indexOfFirst { it.candidateId == ids["unlinked"] } > group.indexOfFirst { it.candidateId == ids["plain"] })
    }

    @Test
    fun `raw scores do not affect ordering`() {
        val base = computeMain().groups.confirmed.map { it.candidateId }
        val changed = copyProfiles().onEachIndexed { index, p -> p["raw_assessment_score"] = 1000 - index }
        assertEquals(base, MatchingPolicy.computeMatches(changed, needs.getValue("need-junior-remote")).groups.confirmed.map { it.candidateId })
    }

    @Test
    fun `hidden or unverified profiles never appear in results`() {
        val result = computeMain()
        val returned = result.groups.confirmed + result.groups.needsClarification + result.groups.needsConfirmation
        assertFalse(returned.any { it.candidateId == ids["hidden"] })
        assertFalse(returned.any { it.candidateId == ids["unverified_email"] })
    }

    @Test
    fun `unknown weekly capacity requires clarification`() {
        val changed = copyProfiles().map { p -> if (p["id"] == ids["plain"]) p["max_weekly_hours"] = null; p }
        val result = MatchingPolicy.computeMatches(changed, needs.getValue("need-junior-remote"))
        assertTrue(result.groups.needsClarification.any { it.candidateId == ids["plain"] })
        assertFalse(result.groups.confirmed.any { it.candidateId == ids["plain"] })
    }

    @Test
    fun `candidate declared skills never become test evidence`() {
        val changed = copyProfiles().map { p ->
            if (p["id"] == ids["plain"]) p["candidate_declared_skills"] = listOf("postgres", "docker", "fastapi")
            p
        }
        val result = MatchingPolicy.computeMatches(changed, needs.getValue("need-junior-fastapi-required"))
        assertTrue(result.groups.needsClarification.any { it.candidateId == ids["plain"] })
        assertFalse(result.groups.confirmed.any { it.candidateId == ids["plain"] })
    }

    @Test
    fun `fit state agrees with the existing matcher for each visible profile and need`() {
        profiles.filter { it["visibility"] == "visible" && it["email_confirmed"] == true }.forEach { profile ->
            needs.values.forEach { need ->
                val expected = MatchingPolicy.computeMatches(listOf(profile), need)
                val expectedState = when {
                    expected.groups.confirmed.isNotEmpty() -> "confirmed"
                    expected.groups.needsClarification.isNotEmpty() -> "needs_clarification"
                    expected.groups.needsConfirmation.isNotEmpty() -> "needs_confirmation"
                    else -> "excluded"
                }
                val fit = MatchingPolicy.explainFit(profile, need)
                assertEquals(expectedState, fit["state"], "${profile["id"]}/${need["id"]}")
            }
        }
    }

    @Test
    fun `fit shows declared and unknown skills separately from test evidence`() {
        val profile = copyProfiles().first { it["id"] == ids["plain"] }
        profile["test_evidenced_skills"] = emptyList<String>()
        profile["candidate_declared_skills"] = listOf("python-basics")
        val fit = MatchingPolicy.explainFit(profile, needs.getValue("need-junior-remote"))
        val skills = (fit["checks"] as List<Map<String, Any?>>).filter { it["type"] == "skill" }
            .associateBy { it["skill_id"] }
        assertEquals("declared", skills["python-basics"]?.get("status"))
        assertEquals("unknown", skills["http-api"]?.get("status"))
        assertEquals("needs_clarification", fit["state"])
    }

    @Test
    fun `explicit hard condition mismatch is included as excluded with reason`() {
        val profile = copyProfiles().first { it["id"] == ids["plain"] }
        profile["verified_grade"] = "middle"
        val fit = MatchingPolicy.explainFit(profile, needs.getValue("need-junior-remote"))
        assertEquals("excluded", fit["state"])
        assertTrue((fit["checks"] as List<Map<String, Any?>>).any {
            it["type"] == "grade" && it["status"] == "mismatch"
        })
    }

    @Test
    fun `unsupported specialization is shown as an explicit mismatch`() {
        val profile = copyProfiles().first { it["id"] == ids["plain"] }
        profile["specialization_id"] = "frontend-v1"
        val fit = MatchingPolicy.explainFit(profile, needs.getValue("need-junior-remote"))
        assertEquals("excluded", fit["state"])
        assertTrue((fit["checks"] as List<Map<String, Any?>>).any {
            it["type"] == "specialization" && it["status"] == "mismatch"
        })
    }

    private fun computeMain() = MatchingPolicy.computeMatches(profiles, needs.getValue("need-junior-remote"))
    private fun copyProfiles(): MutableList<MutableMap<String, Any?>> = mapper.convertValue(profiles, List::class.java)
        .map { mapper.convertValue(it, MutableMap::class.java) as MutableMap<String, Any?> }.toMutableList()
}
