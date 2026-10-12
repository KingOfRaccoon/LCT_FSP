package ru.fsp.talent.assessment

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.TestActor
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class SyntheticAssessmentValidationTest : DbTestBase() {
    @Test
    fun `synthetic test retest protocol records actual server outcomes`() {
        assertEquals("fsp_test", jdbc.queryForObject("SELECT current_database()", String::class.java))

        val preexistingExposure = exposureCounts()
        val profiles = syntheticProfilesInStableRandomOrder()
        assertEquals(48, profiles.size)
        assertEquals(8, profiles.count { it.grade == "junior" && it.stratum == "weak" })
        assertEquals(8, profiles.count { it.grade == "junior" && it.stratum == "boundary" })
        assertEquals(8, profiles.count { it.grade == "junior" && it.stratum == "strong" })
        assertEquals(8, profiles.count { it.grade == "middle" && it.stratum == "weak" })
        assertEquals(8, profiles.count { it.grade == "middle" && it.stratum == "boundary" })
        assertEquals(8, profiles.count { it.grade == "middle" && it.stratum == "strong" })

        val pairs = mutableListOf<ProfilePair>()
        val testUsers = mutableListOf<String>()
        profiles.forEach { profile ->
            val actor = seedActor()
            testUsers += actor.id
            val first = runAttempt(actor, profile, formNumber = 1)

            val immediateRetake = request(
                actor = actor,
                method = HttpMethod.POST,
                path = "/api/v1/attempts",
                body = createBody(profile.grade),
                idempotencyKey = UUID.randomUUID().toString(),
            )
            assertEquals(422, immediateRetake.statusCode.value())
            assertEquals("POLICY_BLOCKED", immediateRetake.body?.path("error")?.path("code")?.asText())

            val moved = jdbc.update(
                "UPDATE attempts SET evaluated_at = now() - interval '25 hours' WHERE id = ?::uuid AND status = 'evaluated'",
                first.attemptId,
            )
            assertEquals(1, moved)

            val second = runAttempt(actor, profile, formNumber = 2)
            assertEquals(profile.grade, second.requestedGrade)
            if (first.gradeAwarded != null && second.gradeAwarded == null) {
                assertEquals(first.gradeAwarded, second.qualificationAfter)
            }
            pairs += ProfilePair(profile, first, second)
        }

        assertEquals(96, pairs.size * 2)
        assertEquals(48, pairs.size)
        assertEquals(96, pairs.sumOf { if (it.first.evaluated && it.second.evaluated) 2 else 0 })
        assertEquals(96L, countAttemptsForUsers(testUsers))

        val report = buildReport(pairs, preexistingExposure, profiles.size)
        writeReport(report)
    }

    private fun runAttempt(actor: TestActor, profile: SyntheticProfile, formNumber: Int): AttemptObservation {
        val created = request(
            actor = actor,
            method = HttpMethod.POST,
            path = "/api/v1/attempts",
            body = createBody(profile.grade),
            idempotencyKey = UUID.randomUUID().toString(),
        )
        assertEquals(201, created.statusCode.value())
        val dto = created.body?.path("data") ?: error("Create attempt response has no data")
        assertEquals("in_progress", dto.path("status").asText())
        assertPublicTasksHaveNoKeys(dto)

        val attemptId = dto.path("id").asText()
        val snapshot = privateTaskSnapshot(attemptId)
        val familyIds = snapshot.path("family_ids").map { it.asText() }
        val variantIds = snapshot.path("variant_ids").map { it.asText() }
        val internalKeys = snapshot.path("answer_keys")
        val publicTasks = dto.path("tasks").toList()
        assertEquals(12, publicTasks.size)
        assertEquals(12, familyIds.size)
        assertEquals(12, familyIds.toSet().size)
        assertEquals(12, variantIds.size)
        assertEquals(24, internalKeys.size())

        val taskCounts = publicTasks.groupingBy { it.path("competency_block").asText() }.eachCount()
        assertEquals(4, taskCounts.size)
        assertTrue(taskCounts.values.all { it == 3 })
        assertEquals(24, publicTasks.sumOf { it.path("subquestions").size() })

        val keyByQuestion = internalKeys.associateBy { it.path("task_id").asText() to it.path("subquestion_id").asText() }
        val answers = mutableListOf<Map<String, Any?>>()
        val correctByBlock = ASSESSMENT_COMPETENCIES.associateWith { 0 }.toMutableMap()
        val itemEvents = mutableListOf<ItemEvent>()
        publicTasks.forEachIndexed { taskIndex, task ->
            val taskId = task.path("task_id").asText()
            val familyId = familyIds[taskIndex]
            val variantId = variantIds[taskIndex]
            assertEquals(2, task.path("subquestions").size())
            task.path("subquestions").forEachIndexed { questionIndex, question ->
                val questionId = question.path("subquestion_id").asText()
                val key = keyByQuestion[taskId to questionId]
                    ?: error("The private test snapshot has no answer key for an issued subquestion")
                val correctOption = key.path("correct_option_id").asText()
                assertTrue(question.path("options").any { it.path("id").asText() == correctOption })

                val knowledge = knows(profile, familyId, questionIndex)
                val selectedOption = if (knowledge) correctOption else question.path("options")
                    .first { it.path("id").asText() != correctOption }.path("id").asText()
                if (knowledge) {
                    val block = task.path("competency_block").asText()
                    correctByBlock[block] = correctByBlock.getValue(block) + 1
                }
                answers += mapOf(
                    "task_id" to taskId,
                    "subquestion_id" to questionId,
                    "selected_option_id" to selectedOption,
                )
                itemEvents += ItemEvent(
                    familyId = familyId,
                    subquestionIndex = questionIndex,
                    profileKey = profile.key,
                    stratum = profile.stratum,
                    correct = knowledge,
                )
                assertNotNull(variantId)
            }
        }
        assertEquals(24, answers.size)

        val saved = request(
            actor = actor,
            method = HttpMethod.PUT,
            path = "/api/v1/attempts/$attemptId/answers",
            body = mapOf("expected_version" to 1, "answers" to answers),
            idempotencyKey = UUID.randomUUID().toString(),
        )
        assertEquals(200, saved.statusCode.value())
        val savedDto = saved.body?.path("data") ?: error("Autosave response has no data")
        assertEquals(2, savedDto.path("version").asInt())
        assertEquals(24, savedDto.path("saved_answers").size())
        assertPublicTasksHaveNoKeys(savedDto)

        val submitted = request(
            actor = actor,
            method = HttpMethod.POST,
            path = "/api/v1/attempts/$attemptId/submit",
            body = mapOf("expected_version" to 2),
            idempotencyKey = UUID.randomUUID().toString(),
        )
        assertEquals(200, submitted.statusCode.value())
        val resultDto = submitted.body?.path("data") ?: error("Submit response has no data")
        assertEquals("evaluated", resultDto.path("status").asText())
        assertPublicTasksHaveNoKeys(resultDto)

        val expectedTotal = correctByBlock.values.sum()
        val result = resultDto.path("result")
        assertEquals(expectedTotal, result.path("total_score").asInt())
        assertEquals(24, result.path("max_score").asInt())
        val serverBlocks = result.path("block_scores").associate {
            it.path("block_id").asText() to it.path("score").asInt()
        }
        assertEquals(correctByBlock, serverBlocks)
        val independentlyAwardedGrade = if (expectedTotal >= 18 && correctByBlock.values.all { it >= 3 }) profile.grade else null
        val responseAwardedGrade = result.path("grade_awarded").takeUnless { it.isNull }?.asText()
        assertEquals(independentlyAwardedGrade, responseAwardedGrade)
        assertFalse(result.has("answer_keys"))

        val dbResult = jdbc.queryForMap(
            "SELECT status, result::text AS result_json FROM attempts WHERE id = ?::uuid",
            attemptId,
        )
        assertEquals("evaluated", dbResult["status"].toString())
        val persistedResult = objectMapper.readTree(dbResult.getValue("result_json").toString())
        val dbGradeAwarded = persistedResult.path("grade_awarded").takeUnless { it.isNull }?.asText()
        val gradeAwarded = result.path("grade_awarded").takeUnless { it.isNull }?.asText()
        assertEquals(dbGradeAwarded, gradeAwarded)

        return AttemptObservation(
            attemptId = attemptId,
            formNumber = formNumber,
            requestedGrade = resultDto.path("requested_grade").asText(),
            gradeAwarded = gradeAwarded,
            totalScore = result.path("total_score").asInt(),
            blockScores = serverBlocks,
            familyIds = familyIds,
            variantIds = variantIds,
            itemEvents = itemEvents,
            qualificationAfter = qualificationGrade(actor.id),
            evaluated = true,
        )
    }

    private fun createBody(grade: String) = mapOf(
        "specialization_id" to ASSESSMENT_SPECIALIZATION,
        "requested_grade" to grade,
        "policy_id" to ASSESSMENT_POLICY_ID,
        "policy_version" to ASSESSMENT_POLICY_VERSION,
    )

    private fun privateTaskSnapshot(attemptId: String): JsonNode {
        val json = jdbc.queryForObject(
            "SELECT tasks::text FROM attempts WHERE id = ?::uuid",
            String::class.java,
            attemptId,
        ) ?: error("Attempt snapshot was not stored")
        return objectMapper.readTree(json)
    }

    private fun assertPublicTasksHaveNoKeys(dto: JsonNode) {
        assertFalse(dto.has("answer_keys"))
        dto.path("tasks").forEach { task ->
            assertFalse(task.has("correct_option_id"))
            assertFalse(task.has("answer_keys"))
            task.path("subquestions").forEach { question -> assertFalse(question.has("correct_option_id")) }
        }
    }

    private fun qualificationGrade(userId: String): String? = jdbc.queryForList(
        "SELECT grade FROM qualifications WHERE user_id = ?::uuid AND specialization_id = ?",
        String::class.java,
        userId,
        ASSESSMENT_SPECIALIZATION,
    ).firstOrNull()

    private fun countAttemptsForUsers(userIds: List<String>): Long {
        val placeholders = userIds.joinToString(",") { "?::uuid" }
        return jdbc.queryForObject(
            "SELECT count(*) FROM attempts WHERE user_id IN ($placeholders)",
            Long::class.java,
            *userIds.toTypedArray(),
        ) ?: 0L
    }

    private fun knows(profile: SyntheticProfile, familyId: String, subquestionIndex: Int): Boolean {
        val bytes = sha256("$SEED|${profile.key}|$familyId|$subquestionIndex".toByteArray())
        val unit = (ByteBuffer.wrap(bytes, 0, 8).long ushr 11).toDouble() / TWO_POW_53
        return unit < profile.probability
    }

    private fun syntheticProfilesInStableRandomOrder(): List<SyntheticProfile> = buildList {
        for (grade in listOf("junior", "middle")) {
            for (stratum in STRATA) {
                for (ordinal in 1..PROFILES_PER_COHORT) {
                    add(
                        SyntheticProfile(
                            grade = grade,
                            stratum = stratum.name,
                            ordinal = ordinal,
                            key = "$grade-${stratum.name}-${ordinal.toString().padStart(2, '0')}",
                            probability = stratum.probability,
                        ),
                    )
                }
            }
        }
    }.sortedWith(compareBy<SyntheticProfile> { stableHash("$SEED|dataset-order|${it.key}") }.thenBy { it.key })

    private fun exposureCounts(): Map<String, Any?> = mapOf(
        "family" to jdbc.queryForList("SELECT family_id, count(*) AS count FROM task_exposures GROUP BY family_id ORDER BY family_id")
            .associate { it.getValue("family_id").toString() to (it.getValue("count") as Number).toLong() },
        "variant" to jdbc.queryForList("SELECT variant_id, count(*) AS count FROM task_exposures GROUP BY variant_id ORDER BY variant_id")
            .associate { it.getValue("variant_id").toString() to (it.getValue("count") as Number).toLong() },
    )

    private fun buildReport(
        pairs: List<ProfilePair>,
        preexistingExposure: Map<String, Any?>,
        profileCount: Int,
    ): Map<String, Any?> {
        val all = pairs.flatMap { listOf(it.first, it.second) }
        val stablePairs = pairs.count { it.first.gradeAwarded == it.second.gradeAwarded }
        val transitionMatrix = listOf("junior", "middle").associateWith { grade ->
            val gradePairs = pairs.filter { it.profile.grade == grade }
            outcomes().associateWith { from ->
                outcomes().associateWith { to ->
                    gradePairs.count { outcome(it.first.gradeAwarded) == from && outcome(it.second.gradeAwarded) == to }
                }
            }
        }
        val byCohort = pairs.groupBy { it.profile.grade to it.profile.stratum }.mapKeys { (key, _) -> "${key.first}:${key.second}" }
            .mapValues { (_, values) ->
                mapOf(
                    "profiles" to values.size,
                    "stable_new_grade_decisions" to values.count { it.first.gradeAwarded == it.second.gradeAwarded },
                    "agreement" to proportion(values.count { it.first.gradeAwarded == it.second.gradeAwarded }, values.size),
                    "mean_first_total_score" to mean(values.map { it.first.totalScore }),
                    "mean_second_total_score" to mean(values.map { it.second.totalScore }),
                    "mean_score_delta_second_minus_first" to mean(values.map { it.second.totalScore - it.first.totalScore }),
                )
            }
        val scoreDeltas = pairs.map { it.second.totalScore - it.first.totalScore }
        val qualificationPreservedAfterFailedRetest = pairs.count {
            it.first.gradeAwarded != null && it.second.gradeAwarded == null &&
                it.second.qualificationAfter == it.first.gradeAwarded
        }
        val secondDecisionDiffersFromStoredQualification = pairs.count {
            it.second.qualificationAfter != it.second.gradeAwarded
        }
        val familyOverlap = pairs.map { it.first.familyIds.toSet().intersect(it.second.familyIds.toSet()).size }
        val variantOverlap = pairs.map { it.first.variantIds.toSet().intersect(it.second.variantIds.toSet()).size }
        val allFamilyExposure = all.flatMap { it.familyIds }.groupingBy { it }.eachCount().toSortedMap()
        val allVariantExposure = all.flatMap { it.variantIds }.groupingBy { it }.eachCount().toSortedMap()
        val itemReport = buildItemReport(all)
        val bankHash = resourceHash("assessment/question-bank.v1.json")
        val policyHash = resourceHash("assessment/assessment-policy.v1.json")

        return mapOf(
            "status" to "completed_synthetic_observation",
            "synthetic" to true,
            "generated_at" to Instant.now().toString(),
            "model" to mapOf(
                "name" to "fixed_latent_item_knowledge_hash_model",
                "seed" to SEED,
                "dataset_order" to "ascending SHA-256(seed|dataset-order|profile_key)",
                "knowledge_hash" to "SHA-256(seed|profile_key|family_id|zero_based_subquestion_index)",
                "probability_by_stratum" to STRATA.associate { it.name to it.probability },
                "profiles_per_grade_stratum" to PROFILES_PER_COHORT,
                "answers_repeat_for_same_family_and_subquestion_across_forms" to true,
            ),
            "counts" to mapOf(
                "synthetic_profiles" to profileCount,
                "profiles_per_grade_stratum" to PROFILES_PER_COHORT,
                "grades" to listOf("junior", "middle"),
                "strata" to STRATA.map { it.name },
                "attempts_requested" to 96,
                "evaluated_attempts" to all.count { it.evaluated },
                "retest_pairs" to pairs.size,
                "attempts_per_profile" to 2,
                "structurally_complete_forms" to all.size,
                "full_snapshots_saved" to all.size,
                "answers_saved" to all.sumOf { it.familyIds.size * 2 },
            ),
            "test_retest" to mapOf(
                "decision_source" to "AttemptResult.grade_awarded for each new evaluation",
                "stable_new_grade_decisions" to stablePairs,
                "different_new_grade_decisions" to pairs.size - stablePairs,
                "agreement" to proportion(stablePairs, pairs.size),
                "transition_matrix_by_requested_grade" to transitionMatrix,
                "by_cohort" to byCohort,
                "score_delta_second_minus_first" to mapOf(
                    "mean" to mean(scoreDeltas),
                    "median" to median(scoreDeltas),
                    "minimum" to scoreDeltas.minOrNull(),
                    "maximum" to scoreDeltas.maxOrNull(),
                ),
                "stored_qualification_comparison" to mapOf(
                    "pairs_where_failed_second_retest_preserved_prior_qualification" to qualificationPreservedAfterFailedRetest,
                    "pairs_where_stored_qualification_differs_from_second_grade_awarded" to secondDecisionDiffersFromStoredQualification,
                ),
                "pair_family_overlap" to overlapSummary(familyOverlap),
                "pair_variant_overlap" to overlapSummary(variantOverlap),
            ),
            "items" to itemReport,
            "exposure" to mapOf(
                "current_run_family_counts" to allFamilyExposure,
                "current_run_variant_counts" to allVariantExposure,
                "preexisting_database_exposure_counts_at_start" to preexistingExposure,
                "issued_forms" to all.size,
                "family_exposures" to all.sumOf { it.familyIds.size },
                "variant_exposures" to all.sumOf { it.variantIds.size },
                "selection_note" to "The server uses crypto-random seeds and current global exposure counts; captured forms are reproducible from their stored snapshots, but fresh-database or rerun form selection is not promised to match.",
            ),
            "hashes" to mapOf(
                "bank_version" to ASSESSMENT_BANK_VERSION,
                "bank_sha256" to bankHash,
                "policy_version" to ASSESSMENT_POLICY_VERSION,
                "policy_sha256" to policyHash,
            ),
            "limits" to listOf(
                "Synthetic item knowledge probabilities are model assumptions, not human response estimates.",
                "The bank remains draft_content_review and requires subject-matter review and empirical validation.",
                "The report does not establish professional grade validity, A/B difficulty equivalence, hiring quality, or resistance to assistance.",
                "Repeated forms for one synthetic profile are not independent people; item unique-profile counts deduplicate those repeats.",
                "This run uses a small public starter bank; family/variant overlap is descriptive and does not demonstrate validity.",
                "The retake interval test uses a test-only evaluated_at fixture shift of 25 hours.",
            ),
        )
    }

    private fun buildItemReport(attempts: List<AttemptObservation>): List<Map<String, Any?>> {
        val grouped = attempts.flatMap { it.itemEvents }.groupBy { ItemKey(it.familyId, it.subquestionIndex) }
        return grouped.toSortedMap(compareBy<ItemKey> { it.familyId }.thenBy { it.subquestionIndex }).map { (key, events) ->
            val byProfile = events.groupBy { it.profileKey }.mapValues { (_, profileEvents) ->
                val responses = profileEvents.map { it.correct }.distinct()
                assertEquals(1, responses.size)
                responses.single()
            }
            val uniqueEvents = byProfile.map { (profileKey, correct) ->
                val event = events.first { it.profileKey == profileKey }
                event.copy(correct = correct)
            }
            val weak = uniqueEvents.filter { it.stratum == "weak" }
            val strong = uniqueEvents.filter { it.stratum == "strong" }
            val byStratum = STRATA.associate { stratum ->
                val values = uniqueEvents.filter { it.stratum == stratum.name }
                stratum.name to mapOf(
                    "unique_profiles" to values.size,
                    "correct_profiles" to values.count { it.correct },
                    "proportion_correct" to proportion(values.count { it.correct }, values.size),
                )
            }
            val weakRate = proportion(weak.count { it.correct }, weak.size)
            val strongRate = proportion(strong.count { it.correct }, strong.size)
            mapOf(
                "family_id" to key.familyId,
                "subquestion_index_zero_based" to key.subquestionIndex,
                "observations" to events.size,
                "correct_observations" to events.count { it.correct },
                "proportion_correct" to proportion(events.count { it.correct }, events.size),
                "unique_synthetic_profiles" to byProfile.size,
                "unique_profile_correct_count" to byProfile.values.count { it },
                "unique_profile_proportion_correct" to proportion(byProfile.values.count { it }, byProfile.size),
                "unique_profiles_by_stratum" to byStratum,
                "strong_minus_weak_unique_profile_proportion" to if (strongRate == null || weakRate == null) null else strongRate - weakRate,
            )
        }
    }

    private fun writeReport(report: Map<String, Any?>) {
        val current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        val backend = if (current.fileName.toString() == "backend") current else current.resolve("backend")
        val target = backend.resolve("build/reports/assessment-synthetic-results-2026-10-07.json")
        Files.createDirectories(target.parent)
        Files.writeString(target, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n")
        assertTrue(Files.size(target) > 0)
    }

    private fun resourceHash(resource: String): String {
        val bytes = javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: error("Required assessment resource $resource is missing")
        return sha256(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun stableHash(value: String): String = sha256(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun outcomes() = listOf("junior", "middle", "null")

    private fun outcome(value: String?) = value ?: "null"

    private fun proportion(numerator: Int, denominator: Int): Double? =
        if (denominator == 0) null else numerator.toDouble() / denominator

    private fun mean(values: List<Int>): Double? = if (values.isEmpty()) null else values.average()

    private fun median(values: List<Int>): Double? = if (values.isEmpty()) null else {
        val sorted = values.sorted()
        if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble()
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }

    private fun overlapSummary(values: List<Int>): Map<String, Any?> = mapOf(
        "profiles" to values.size,
        "mean" to mean(values),
        "minimum" to values.minOrNull(),
        "maximum" to values.maxOrNull(),
        "distribution" to values.groupingBy { it }.eachCount().toSortedMap(),
    )

    private data class Stratum(val name: String, val probability: Double)
    private data class SyntheticProfile(
        val grade: String,
        val stratum: String,
        val ordinal: Int,
        val key: String,
        val probability: Double,
    )
    private data class ItemKey(val familyId: String, val subquestionIndex: Int)
    private data class ItemEvent(
        val familyId: String,
        val subquestionIndex: Int,
        val profileKey: String,
        val stratum: String,
        val correct: Boolean,
    )
    private data class AttemptObservation(
        val attemptId: String,
        val formNumber: Int,
        val requestedGrade: String,
        val gradeAwarded: String?,
        val totalScore: Int,
        val blockScores: Map<String, Int>,
        val familyIds: List<String>,
        val variantIds: List<String>,
        val itemEvents: List<ItemEvent>,
        val qualificationAfter: String?,
        val evaluated: Boolean,
    )
    private data class ProfilePair(
        val profile: SyntheticProfile,
        val first: AttemptObservation,
        val second: AttemptObservation,
    )

    companion object {
        private const val SEED = 20261007
        private const val PROFILES_PER_COHORT = 8
        private const val TWO_POW_53 = 9_007_199_254_740_992.0
        private val STRATA = listOf(
            Stratum("weak", 0.35),
            Stratum("boundary", 0.75),
            Stratum("strong", 0.95),
        )
    }
}
