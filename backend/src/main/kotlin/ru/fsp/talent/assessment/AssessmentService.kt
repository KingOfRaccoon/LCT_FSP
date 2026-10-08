package ru.fsp.talent.assessment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.postgresql.util.PGobject
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.Store
import ru.fsp.talent.consents.ConsentService
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

@Component
class AssessmentService(
    private val store: Store,
    private val mapper: ObjectMapper,
    private val consents: ConsentService,
    @Value("\${fsp.demo-mode:true}") private val demoMode: Boolean,
    @Value("\${fsp.assessment.real-grade-enabled:false}") private val realGradeEnabled: Boolean,
) {
    private val bank: JsonNode by lazy {
        ClassPathResource("assessment/question-bank.v1.json").inputStream.use(mapper::readTree)
    }
    private val policy: JsonNode by lazy {
        ClassPathResource("assessment/assessment-policy.v1.json").inputStream.use(mapper::readTree)
    }
    private val releaseVerification: AssessmentReleaseVerification by lazy {
        AssessmentReleaseVerifier.verify(
            classpathBytes("assessment/assessment-release.v1.json"),
            classpathBytes("assessment/question-bank.v1.json"),
            classpathBytes("assessment/assessment-policy.v1.json"),
            mapper,
        )
    }

    fun getEligibility(userId: String): Map<String, Any?> {
        val qualification = store.one(
            "SELECT grade, last_changed_at FROM qualifications WHERE user_id = :user AND specialization_id = :specialization",
            mapOf("user" to userId, "specialization" to ASSESSMENT_SPECIALIZATION),
        )
        val activeId = activeAttemptId(userId)
        val now = store.now()
        return mapOf(
            "junior" to eligibilityDto(eligibilityFor(userId, "junior", qualification, activeId, now)),
            "middle" to eligibilityDto(eligibilityFor(userId, "middle", qualification, activeId, now)),
        )
    }

    fun activeAttemptId(userId: String): String? = store.one(
        """SELECT id FROM attempts WHERE user_id = :user AND specialization_id = :specialization
           AND status IN ('in_progress', 'submitted') ORDER BY started_at DESC, id DESC LIMIT 1""".trimIndent(),
        mapOf("user" to userId, "specialization" to ASSESSMENT_SPECIALIZATION),
    )?.get("id")?.toString()

    fun taxonomy(): Map<String, Any?> {
        val taxonomySkills = (ASSESSMENT_COMPETENCIES.map { it to true } +
            listOf("postgres", "docker", "fastapi").map { it to false })
            .map { (id, testAssessed) -> mapOf("id" to id, "test_assessed" to testAssessed) }
        val gates = (policy.path("pass_rule").path("minimum_total").asInt() == 18) &&
            (policy.path("blueprint").path("tasks_per_attempt").asInt() == 12)
        if (!gates) throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Assessment policy is unavailable", retryable = true)
        return mapOf(
            "specialization_id" to ASSESSMENT_SPECIALIZATION,
            "version" to "1.0.0",
            "grades" to listOf("junior", "middle").mapIndexed { index, grade ->
                mapOf(
                    "id" to grade,
                    "order" to index + 1,
                    "passing_score" to 18,
                    "max_score" to 24,
                    "critical_blocks" to ASSESSMENT_COMPETENCIES,
                    "minimum_per_critical_block" to 3,
                )
            },
            "skills" to taxonomySkills,
            "assessment_policy" to mapOf(
                "policy_id" to ASSESSMENT_POLICY_ID,
                "version" to ASSESSMENT_POLICY_VERSION,
                "duration_seconds" to 1800,
                "same_grade_retake_interval_seconds" to 86400,
                "grade_change_cooldown_seconds" to 2592000,
                "maximum_active_attempts_per_specialization" to 1,
                "minimum_total_score" to 18,
                "minimum_each_competency" to 3,
                "maximum_score" to 24,
                "confident_minimum_total" to 22,
                "confident_minimum_each_competency" to 4,
            ),
            "assessment_release" to releaseVerification.metadata,
            "profile_completion" to mapOf(
                "required_candidate_fields" to listOf(
                    "display_name", "specialization_id", "declared_grade", "allowed_work_modes", "max_weekly_hours",
                ),
                "city_required_when_work_mode_includes" to listOf("hybrid", "office"),
                "candidate_must_be_email_verified_before" to listOf(
                    "public_visibility", "graded_attempt", "employer_search", "invitation_send",
                ),
            ),
            "rate_limits" to mapOf(
                "registration_per_ip_per_hour" to 5,
                "login_failed_attempts_per_email_and_ip_per_15_minutes" to 5,
                "login_block_seconds" to 900,
                "confirmation_resend_min_interval_seconds" to 60,
                "confirmation_resend_per_account_per_day" to 5,
                "confirmation_resend_per_ip_per_day" to 20,
                "match_search_per_company_per_minute" to 60,
                "invitation_create_per_company_per_day" to 20,
                "email_outbox_max_delivery_attempts" to 5,
                "email_outbox_retry_delay_seconds" to listOf(60, 300, 900, 3600),
            ),
        )
    }

    fun createAttempt(userId: String, requestedGrade: String): Map<String, Any?> {
        requireDemoGradeGate()
        return store.tx.execute<Map<String, Any?>> {
            store.one("SELECT id FROM users WHERE id = :user FOR UPDATE", mapOf("user" to userId))
                ?: throw ApiException(404, "NOT_FOUND", "Candidate was not found")
            consents.requireProcessing(userId)
            var activeId = activeAttemptId(userId)
            if (activeId != null) {
                val active = loadAttempt(activeId, userId, lock = true) ?: notFound()
                if (store.now() >= active.instant("deadline_at")) {
                    finalizeAttempt(active, store.now(), submitted = false)
                    activeId = null
                } else if (active.getValue("requested_grade").toString() == requestedGrade) {
                    return@execute attemptDto(active, store.now())
                } else {
                    throw ApiException(409, "ACTIVE_ATTEMPT_EXISTS", "An assessment is already active", activeAttemptId = activeId)
                }
            }
            if (activeId != null) {
                throw ApiException(409, "ACTIVE_ATTEMPT_EXISTS", "An assessment is already active", activeAttemptId = activeId)
            }
            val eligibility = eligibilityForUser(userId, requestedGrade, activeId, store.now())
            if (!eligibility.allowed) throw eligibilityError(eligibility)

            val issued = buildForm(userId, requestedGrade)
            val now = store.now()
            val attemptId = store.id()
            val deadline = now.plusSeconds(1800)
            val snapshot = issued.snapshot
            store.exec(
                """INSERT INTO attempts(id, user_id, specialization_id, requested_grade, status,
                       form_version, policy_version, bank_version, rubric_version, started_at, deadline_at,
                       tasks, answers, result)
                   VALUES (:id, :user, :specialization, :grade, 'in_progress', 'form-v1', :policyVersion,
                       :bankVersion, 'rubric-v1', :started, :deadline, :tasks, :answers, '{}'::jsonb)""".trimIndent(),
                mapOf(
                    "id" to attemptId, "user" to userId, "specialization" to ASSESSMENT_SPECIALIZATION,
                    "grade" to requestedGrade, "policyVersion" to ASSESSMENT_POLICY_VERSION,
                    "bankVersion" to ASSESSMENT_BANK_VERSION, "started" to now, "deadline" to deadline,
                    "tasks" to store.json(snapshot), "answers" to store.json(mapOf("items" to emptyList<Any>())),
                ),
            )
            issued.exposures.forEach { exposure ->
                store.exec(
                    """INSERT INTO task_exposures(id, attempt_id, user_id, family_id, variant_id)
                       VALUES (:id, :attempt, :user, :family, :variant)""".trimIndent(),
                    mapOf(
                        "id" to store.id(), "attempt" to attemptId, "user" to userId,
                        "family" to exposure.first, "variant" to exposure.second,
                    ),
                )
            }
            attemptDto(loadAttempt(attemptId, userId, lock = false)!!, store.now())
        } ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)
    }

    fun getAttempt(userId: String, attemptId: String): Map<String, Any?> {
        val current = loadAttempt(attemptId, userId) ?: notFound()
        if (isOpen(current) && !store.now().isBefore(current.instant("deadline_at"))) {
            store.tx.execute {
                val locked = loadAttempt(attemptId, userId, lock = true) ?: notFound()
                if (isOpen(locked) && !store.now().isBefore(locked.instant("deadline_at"))) {
                    finalizeAttempt(locked, store.now(), submitted = false)
                }
            }
        }
        val latest = loadAttempt(attemptId, userId) ?: notFound()
        return attemptDto(latest, store.now())
    }

    fun autosave(userId: String, attemptId: String, expectedVersion: Int, answers: List<Map<String, Any?>>): Map<String, Any?> =
        store.tx.execute<Map<String, Any?>> {
            consents.requireProcessing(userId)
            val locked = loadAttempt(attemptId, userId, lock = true) ?: notFound()
            if (!isOpen(locked)) throw ApiException(409, "INVALID_STATE", "The attempt is no longer editable")
            if (store.now() >= locked.instant("deadline_at")) {
                finalizeAttempt(locked, store.now(), submitted = false)
                return@execute mapOf("__assessment_error_after_commit" to "ATTEMPT_DEADLINE_REACHED")
            }
            val currentVersion = locked.int("version")
            if (currentVersion != expectedVersion) {
                throw ApiException(409, "VERSION_CONFLICT", "Reload the saved answer snapshot", currentVersion = currentVersion)
            }
            val normalized = normalizeAnswers(locked, answers)
            store.exec(
                """UPDATE attempts SET answers = :answers, version = version + 1
                   WHERE id = :id AND user_id = :user AND version = :version""".trimIndent(),
                mapOf("answers" to store.json(mapOf("items" to normalized)), "id" to attemptId,
                    "user" to userId, "version" to expectedVersion),
            )
            val updated = loadAttempt(attemptId, userId) ?: notFound()
            attemptDto(updated, store.now())
        } ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)

    fun submit(userId: String, attemptId: String, expectedVersion: Int): Map<String, Any?> =
        store.tx.execute<Map<String, Any?>> {
            consents.requireProcessing(userId)
            val locked = loadAttempt(attemptId, userId, lock = true) ?: notFound()
            if (!isOpen(locked)) {
                return@execute attemptDto(locked, store.now())
            }
            val now = store.now()
            if (now >= locked.instant("deadline_at")) {
                return@execute finalizeAttempt(locked, now, submitted = false)
            }
            val currentVersion = locked.int("version")
            if (currentVersion != expectedVersion) {
                throw ApiException(409, "VERSION_CONFLICT", "Reload the saved answer snapshot", currentVersion = currentVersion)
            }
            finalizeAttempt(locked, now, submitted = true)
        } ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)

    fun attemptHistory(userId: String, requestedLimit: Int?, cursor: String?): Map<String, Any?> {
        val limit = requestedLimit ?: 20
        if (limit !in 1..100) throw ApiException(400, "VALIDATION_ERROR", "limit must be between 1 and 100", "limit")
        val position = cursor?.let(::decodeCursor)
        val params = mutableMapOf<String, Any?>("user" to userId, "specialization" to ASSESSMENT_SPECIALIZATION, "limit" to limit + 1)
        val cursorClause = if (position != null) {
            params["startedAt"] = position.first
            params["id"] = position.second
            "AND (started_at < :startedAt OR (started_at = :startedAt AND id < :id))"
        } else ""
        val rows = store.rows(
            """SELECT id, requested_grade, status, deadline_at, evaluated_at, result, policy_version, started_at
               FROM attempts WHERE user_id = :user AND specialization_id = :specialization $cursorClause
               ORDER BY started_at DESC, id DESC LIMIT :limit""".trimIndent(), params,
        )
        val page = rows.take(limit)
        val nextCursor = if (rows.size > limit) page.lastOrNull()?.let { encodeCursor(it.instant("started_at"), it.getValue("id").toString()) } else null
        return mapOf(
            "items" to page.map { row ->
                val result = row.jsonMap("result")
                mapOf(
                    "id" to row.getValue("id").toString(),
                    "requested_grade" to row.getValue("requested_grade"),
                    "status" to row.getValue("status"),
                    "deadline_at" to row.instant("deadline_at").toString(),
                    "evaluated_at" to row.instantOrNull("evaluated_at")?.toString(),
                    "total_score" to (result["total_score"] as? Number)?.toInt(),
                    "passed" to result["passed"] as? Boolean,
                    "policy_version" to row.getValue("policy_version"),
                )
            },
            "next_cursor" to nextCursor,
        )
    }

    private fun eligibilityForUser(userId: String, target: String, activeId: String?, now: Instant): GradeEligibility {
        val qualification = store.one(
            "SELECT grade, last_changed_at FROM qualifications WHERE user_id = :user AND specialization_id = :specialization",
            mapOf("user" to userId, "specialization" to ASSESSMENT_SPECIALIZATION),
        )
        return eligibilityFor(userId, target, qualification, activeId, now)
    }

    private fun eligibilityFor(
        userId: String,
        target: String,
        qualification: Map<String, Any?>?,
        activeId: String?,
        now: Instant,
    ): GradeEligibility {
        val lastEvaluated = store.one(
            """SELECT evaluated_at FROM attempts WHERE user_id = :user AND specialization_id = :specialization
               AND requested_grade = :grade AND status = 'evaluated' ORDER BY evaluated_at DESC, id DESC LIMIT 1""".trimIndent(),
            mapOf("user" to userId, "specialization" to ASSESSMENT_SPECIALIZATION, "grade" to target),
        )?.instantOrNull("evaluated_at")
        val decision = AssessmentPolicy.eligibility(
            targetGrade = target,
            confirmedGrade = qualification?.get("grade")?.toString(),
            evaluatedAtForTarget = lastEvaluated,
            lastSuccessfulGradeChangeAt = qualification?.instantOrNull("last_changed_at"),
            activeAttemptId = activeId,
            now = now,
        )
        return decision
    }

    private fun eligibilityDto(decision: GradeEligibility): Map<String, Any?> = mapOf(
            "allowed" to decision.allowed,
            "eligible_at" to decision.eligibleAt?.toString(),
            "reason" to decision.reason,
        )

    private fun eligibilityError(decision: GradeEligibility): ApiException = when (decision.reason) {
        "attempt_active" -> ApiException(409, "ACTIVE_ATTEMPT_EXISTS", "An assessment is already active", activeAttemptId = decision.activeAttemptId)
        "policy_blocked" -> ApiException(422, "POLICY_BLOCKED", "This grade transition is unavailable")
        else -> ApiException(422, "POLICY_BLOCKED", "Assessment is not currently available", eligibleAt = decision.eligibleAt)
    }

    private fun buildForm(userId: String, grade: String): IssuedForm {
        val families = bank.path("families").filter { it.path("grade").asText() == grade }
        val candidateFamilyCounts = store.rows(
            "SELECT family_id, count(*) AS exposure_count FROM task_exposures WHERE user_id = :user GROUP BY family_id",
            mapOf("user" to userId),
        ).associate { it.getValue("family_id").toString() to it.getValue("exposure_count").toString().toLong() }
        val globalFamilyCounts = store.rows(
            "SELECT family_id, count(*) AS exposure_count FROM task_exposures GROUP BY family_id",
        ).associate { it.getValue("family_id").toString() to it.getValue("exposure_count").toString().toLong() }
        val candidateVariantCounts = store.rows(
            "SELECT variant_id, count(*) AS exposure_count FROM task_exposures WHERE user_id = :user GROUP BY variant_id",
            mapOf("user" to userId),
        ).associate { it.getValue("variant_id").toString() to it.getValue("exposure_count").toString().toLong() }
        val globalVariantCounts = store.rows(
            "SELECT variant_id, count(*) AS exposure_count FROM task_exposures GROUP BY variant_id",
        ).associate { it.getValue("variant_id").toString() to it.getValue("exposure_count").toString().toLong() }
        val seedBytes = ByteArray(16).also(SecureRandom()::nextBytes)
        val seed = seedBytes.joinToString("") { "%02x".format(it) }
        val selected = mutableListOf<Pair<JsonNode, JsonNode>>()
        for (competency in ASSESSMENT_COMPETENCIES) {
            val blockFamilies = families.filter {
                it.path("competency_id").asText() == competency && eligibleFamily(it)
            }
            if (blockFamilies.size < 4 || blockFamilies.map { it.path("id").asText() }.toSet().size != blockFamilies.size) bankUnavailable()
            val chosenFamilies = blockFamilies.sortedWith(
                compareBy<JsonNode> { candidateFamilyCounts[it.path("id").asText()] ?: 0L }
                    .thenBy { globalFamilyCounts[it.path("id").asText()] ?: 0L }
                    .thenBy { seeded(seed, "family:${it.path("id").asText()}") },
            ).take(3)
            chosenFamilies.forEach { family ->
                val variant = family.path("variants").filter(::eligibleVariant).sortedWith(
                    compareBy<JsonNode> { candidateVariantCounts[it.path("id").asText()] ?: 0L }
                        .thenBy { globalVariantCounts[it.path("id").asText()] ?: 0L }
                        .thenBy { seeded(seed, "variant:${it.path("id").asText()}") },
                ).firstOrNull() ?: bankUnavailable()
                selected += family to variant
            }
        }
        if (selected.size != 12) bankUnavailable()
        val answerKeys = mutableListOf<Map<String, String>>()
        val exposureRows = mutableListOf<Pair<String, String>>()
        val familyVersions = mutableListOf<Map<String, String>>()
        val optionPermutations = mutableListOf<Map<String, Any?>>()
        val taskItems = selected.map { (family, variant) ->
            val taskId = store.id()
            val competency = family.path("competency_id").asText()
            val version = variant.path("version").asText().ifBlank { family.path("version").asText() }
            val subquestionPublic = mutableListOf<Map<String, Any?>>()
            val taskPermutations = mutableListOf<Map<String, Any?>>()
            variant.path("subquestions").forEach { question ->
                val options = question.path("options").toList()
                if (options.size != 4) bankUnavailable()
                val shuffled = options.shuffled(Random(seedLong(seed, "options:${question.path("id").asText()}")))
                val ids = shuffled.map { it.path("id").asText() }
                taskPermutations += mapOf("subquestion_id" to question.path("id").asText(), "option_ids" to ids)
                subquestionPublic += mapOf(
                    "subquestion_id" to question.path("id").asText(),
                    "prompt" to question.path("prompt").asText(),
                    "options" to shuffled.map { mapOf("id" to it.path("id").asText(), "text" to it.path("text").asText()) },
                )
                answerKeys += mapOf(
                    "task_id" to taskId,
                    "competency_id" to competency,
                    "subquestion_id" to question.path("id").asText(),
                    "correct_option_id" to question.path("correct_option_id").asText(),
                )
            }
            exposureRows += family.path("id").asText() to variant.path("id").asText()
            familyVersions += mapOf("family_id" to family.path("id").asText(), "version" to family.path("version").asText())
            optionPermutations += mapOf("task_id" to taskId, "subquestions" to taskPermutations)
            mapOf(
                "task_id" to taskId,
                "task_version" to version,
                "kind" to "multiple_choice",
                "prompt" to variant.path("prompt").asText(),
                "subquestions" to subquestionPublic,
                "competency_block" to competency,
            )
        }
        if (answerKeys.size != 24 || answerKeys.map { it["task_id"] to it["subquestion_id"] }.toSet().size != 24) {
            bankUnavailable()
        }
        val publicItems = taskItems.map { item -> item.filterKeys { !it.startsWith("_") } }
        val snapshot = mapOf(
            "seed" to seed,
            "demo" to demoMode,
            "form_version" to "form-v1",
            "bank_version" to ASSESSMENT_BANK_VERSION,
            "policy_version" to ASSESSMENT_POLICY_VERSION,
            "family_ids" to exposureRows.map { it.first },
            "variant_ids" to exposureRows.map { it.second },
            "family_versions" to familyVersions,
            "option_permutations" to optionPermutations,
            "answer_keys" to answerKeys,
            "items" to publicItems,
        )
        return IssuedForm(snapshot, exposureRows)
    }

    private fun eligibleFamily(family: JsonNode): Boolean {
        val id = family.path("id").asText()
        val version = family.path("version").asText()
        val variants = family.path("variants").filter(::eligibleVariant)
        return id.isNotBlank() && version.isNotBlank() && variants.size >= 2 &&
            variants.map { it.path("id").asText() }.toSet().size == variants.size
    }

    private fun eligibleVariant(variant: JsonNode): Boolean {
        if (variant.path("id").asText().isBlank() ||
            variant.path("prompt").asText().isBlank()
        ) return false
        val questions = variant.path("subquestions").toList()
        if (questions.size != 2 || questions.map { it.path("id").asText() }.toSet().size != 2) return false
        return questions.all { question ->
            val options = question.path("options").toList()
            val optionIds = options.map { it.path("id").asText() }
            question.path("id").asText().isNotBlank() && question.path("prompt").asText().isNotBlank() &&
                options.size == 4 && optionIds.all(String::isNotBlank) && optionIds.toSet().size == 4 &&
                question.path("correct_option_id").asText() in optionIds
        }
    }

    private fun normalizeAnswers(attempt: Map<String, Any?>, answers: List<Map<String, Any?>>): List<Map<String, Any?>> {
        if (answers.size > 24) throw ApiException(400, "VALIDATION_ERROR", "Too many answers", "answers")
        val items = attempt.jsonMap("tasks")["items"] as? List<*> ?: emptyList<Any>()
        val validOptions = buildMap<Pair<String, String>, Set<String>> {
            items.forEach { item ->
                val task = item.mapObject()
                val taskId = task["task_id"].toString()
                val subquestions = task["subquestions"] as? List<*> ?: emptyList<Any>()
                subquestions.forEach { raw ->
                    val question = raw.mapObject()
                    val id = question["subquestion_id"].toString()
                    val options = (question["options"] as? List<*> ?: emptyList<Any>()).map { it.mapObject()["id"].toString() }.toSet()
                    put(taskId to id, options)
                }
            }
        }
        val seen = mutableSetOf<Pair<String, String>>()
        val now = store.now().toString()
        return answers.map { answer ->
            val taskId = answer["task_id"]?.toString() ?: throw ApiException(400, "VALIDATION_ERROR", "task_id is required")
            val questionId = answer["subquestion_id"]?.toString() ?: throw ApiException(400, "VALIDATION_ERROR", "subquestion_id is required")
            val pair = taskId to questionId
            val options = validOptions[pair] ?: throw ApiException(400, "VALIDATION_ERROR", "Answer does not belong to the issued form")
            if (!seen.add(pair)) throw ApiException(400, "VALIDATION_ERROR", "Duplicate answer")
            val selected = answer["selected_option_id"]?.toString()
            if (selected != null && selected !in options) throw ApiException(400, "VALIDATION_ERROR", "Unknown option")
            mapOf("task_id" to taskId, "subquestion_id" to questionId, "selected_option_id" to selected, "saved_at" to now)
        }
    }

    private fun finalizeAttempt(row: Map<String, Any?>, evaluatedAt: Instant, submitted: Boolean): Map<String, Any?> {
        if (!demoMode) requireDemoGradeGate()
        val userId = row.getValue("user_id").toString()
        val attemptId = row.getValue("id").toString()
        val answerKeys = row.jsonMap("tasks")["answer_keys"] as? List<*> ?: bankUnavailable()
        val keys = answerKeys.map { raw ->
            val key = raw.mapObject()
            AssessmentQuestionKey(
                taskId = key["task_id"].toString(),
                competencyId = key["competency_id"].toString(),
                subquestionId = key["subquestion_id"].toString(),
                correctOptionId = key["correct_option_id"].toString(),
            )
        }
        val savedAnswers = row.jsonMap("answers")["items"] as? List<*> ?: emptyList<Any>()
        val answers = savedAnswers.map { it.mapObject() }
        val score = try {
            AssessmentScoring.score(keys, answers)
        } catch (error: Exception) {
            store.exec("UPDATE attempts SET status='invalidated', invalidity_reason='assessment_snapshot_invalid', evaluated_at=:at, version=version+1 WHERE id=:id",
                mapOf("at" to evaluatedAt, "id" to attemptId))
            val invalidated = loadAttempt(attemptId, userId) ?: notFound()
            return attemptDto(invalidated, evaluatedAt)
        }
        val priorQualification = store.one(
            "SELECT grade, last_changed_at FROM qualifications WHERE user_id=:user AND specialization_id=:specialization FOR UPDATE",
            mapOf("user" to userId, "specialization" to ASSESSMENT_SPECIALIZATION),
        )
        val targetGrade = row.getValue("requested_grade").toString()
        val transition = AssessmentPolicy.transition(
            confirmedGrade = priorQualification?.get("grade")?.toString(),
            targetGrade = targetGrade,
            passed = score.passed,
            evaluatedAt = evaluatedAt,
            lastSuccessfulGradeChangeAt = priorQualification?.instantOrNull("last_changed_at"),
        )
        val publicTasks = row.jsonMap("tasks")["items"] as? List<*> ?: emptyList<Any>()
        val taskVersions = publicTasks.map { item ->
            val task = item.mapObject()
            mapOf("task_id" to task["task_id"], "version" to task["task_version"])
        }
        val result = mapOf(
            "passed" to score.passed,
            "total_score" to score.total,
            "max_score" to 24,
            "grade_awarded" to if (score.passed && transition.confirmedGrade == targetGrade) targetGrade else null,
            "block_scores" to ASSESSMENT_COMPETENCIES.map { id ->
                mapOf("block_id" to id, "score" to score.byCompetency.getValue(id), "max_score" to 6)
            },
            "competency_summary" to ASSESSMENT_COMPETENCIES.map { id ->
                mapOf(
                    "skill_id" to id,
                    "status" to if (score.byCompetency.getValue(id) >= 3) "verified" else "not_verified",
                    "summary" to when {
                        score.byCompetency.getValue(id) < 3 -> "Порог блока не выполнен по методике платформы v1."
                        score.confident && targetGrade == "junior" && id == ASSESSMENT_COMPETENCIES.first() ->
                            "Порог блока выполнен по методике платформы v1. Уверенный итог: рекомендуется тест следующего уровня; автоизменение грейда не выполняется. Это не подтверждает профессиональную пригодность."
                        else -> "Порог блока выполнен по методике платформы v1. Это не подтверждает профессиональную пригодность."
                    },
                )
            },
            "task_versions" to taskVersions,
        )
        store.exec(
            """UPDATE attempts SET status='evaluated', submitted_at=COALESCE(submitted_at, :submittedAt),
               evaluated_at=:evaluatedAt, result=:result, version=version+1
               WHERE id=:id AND status IN ('in_progress','submitted')""".trimIndent(),
            mapOf(
                "submittedAt" to if (submitted) evaluatedAt else null,
                "evaluatedAt" to evaluatedAt,
                "result" to store.json(result),
                "id" to attemptId,
            ),
        )
        if (transition.refreshesEvidence) {
            store.exec(
                """INSERT INTO qualifications(id, user_id, specialization_id, grade, evidence_attempt_id,
                       last_changed_at, assessed_at, policy_version, bank_version, data)
                   VALUES (:id, :user, :specialization, :grade, :attempt, :changedAt, :assessedAt,
                       :policyVersion, :bankVersion, :data)
                   ON CONFLICT (user_id, specialization_id) DO UPDATE SET grade=EXCLUDED.grade,
                       evidence_attempt_id=EXCLUDED.evidence_attempt_id, last_changed_at=CASE
                           WHEN qualifications.grade <> EXCLUDED.grade THEN EXCLUDED.assessed_at
                           ELSE qualifications.last_changed_at END,
                       assessed_at=EXCLUDED.assessed_at, policy_version=EXCLUDED.policy_version,
                       bank_version=EXCLUDED.bank_version, data=EXCLUDED.data""".trimIndent(),
                mapOf(
                    "id" to store.id(), "user" to userId, "specialization" to ASSESSMENT_SPECIALIZATION,
                    "grade" to transition.confirmedGrade, "attempt" to attemptId,
                    "changedAt" to transition.lastSuccessfulGradeChangeAt, "assessedAt" to evaluatedAt,
                    "policyVersion" to ASSESSMENT_POLICY_VERSION, "bankVersion" to ASSESSMENT_BANK_VERSION,
                    "data" to store.json(
                        mapOf(
                            "total_score" to score.total,
                            "block_scores" to score.byCompetency,
                            "demo" to row.jsonMap("tasks")["demo"].asBooleanValue(),
                            "form_version" to row.getValue("form_version"),
                            "policy_version" to row.getValue("policy_version"),
                            "bank_version" to row.getValue("bank_version"),
                        ),
                    ),
                ),
            )
        }
        val updated = loadAttempt(attemptId, userId) ?: notFound()
        return attemptDto(updated, evaluatedAt)
    }

    private fun attemptDto(row: Map<String, Any?>, now: Instant): Map<String, Any?> {
        val taskItems = row.jsonMap("tasks")["items"] as? List<*> ?: emptyList<Any>()
        val answerItems = row.jsonMap("answers")["items"] as? List<*> ?: emptyList<Any>()
        val result = row.jsonMap("result").takeIf { it.isNotEmpty() }
        val deadline = row.instant("deadline_at")
        val remaining = if (row.getValue("status").toString() == "evaluated" || row.getValue("status").toString() == "invalidated") 0L
            else (deadline.epochSecond - now.epochSecond).coerceIn(0L, 1800L)
        return mapOf(
            "id" to row.getValue("id").toString(),
            "demo" to row.jsonMap("tasks")["demo"].asBooleanValue(),
            "version" to row.int("version"),
            "specialization_id" to row.getValue("specialization_id"),
            "requested_grade" to row.getValue("requested_grade"),
            "status" to row.getValue("status"),
            "form_version" to row.getValue("form_version"),
            "rubric_version" to row.getValue("rubric_version"),
            "policy_version" to row.getValue("policy_version"),
            "deadline_at" to deadline.toString(),
            "remaining_seconds" to remaining,
            "submitted_at" to row.instantOrNull("submitted_at")?.toString(),
            "evaluated_at" to row.instantOrNull("evaluated_at")?.toString(),
            "tasks" to taskItems,
            "saved_answers" to answerItems,
            "result" to result,
            "next_action" to when (row.getValue("status").toString()) {
                "in_progress", "submitted" -> if (remaining == 0L) "submit" else "answer"
                "evaluated" -> if (isRetakeWait(row)) "wait_retry" else "none"
                else -> "none"
            },
            "policy_id" to ASSESSMENT_POLICY_ID,
        )
    }

    private fun isRetakeWait(row: Map<String, Any?>): Boolean {
        val last = row.instantOrNull("evaluated_at") ?: return false
        return store.now().isBefore(last.plusSeconds(86400))
    }

    private fun loadAttempt(id: String, userId: String, lock: Boolean = false): Map<String, Any?>? = store.one(
        """SELECT id, user_id, specialization_id, requested_grade, status, form_version, rubric_version,
           policy_version, bank_version, started_at, deadline_at, submitted_at, evaluated_at, version,
           tasks, answers, result, invalidity_reason FROM attempts WHERE id=:id AND user_id=:user
           ${if (lock) "FOR UPDATE" else ""}""".trimIndent(),
        mapOf("id" to requireUuid(id), "user" to userId),
    )

    private fun requireUuid(id: String): String = try {
        UUID.fromString(id).toString().also {
            if (it != id) throw IllegalArgumentException()
        }
    } catch (_: Exception) {
        throw ApiException(400, "VALIDATION_ERROR", "attempt_id must be a canonical UUID", "attempt_id")
    }

    private fun isOpen(row: Map<String, Any?>) = row.getValue("status").toString() in setOf("in_progress", "submitted")

    private fun requireDemoGradeGate() {
        if (!demoMode && !realGradeEnabled) {
            throw ApiException(422, "POLICY_BLOCKED", "Real-candidate grade release is disabled pending content review and validation")
        }
        if (!demoMode && !releaseVerification.realGradeReleaseReady) {
            throw ApiException(503, "GRADE_RELEASE_BLOCKED", "Real-grade release is unavailable pending verified review and validation")
        }
    }

    private fun bankUnavailable(): Nothing = throw ApiException(503, "BANK_UNAVAILABLE", "Assessment bank is unavailable", retryable = true)
    private fun notFound(): Nothing = throw ApiException(404, "NOT_FOUND", "Attempt was not found")

    private fun classpathBytes(path: String): ByteArray =
        runCatching { ClassPathResource(path).inputStream.use { it.readBytes() } }.getOrDefault(ByteArray(0))

    private fun seeded(seed: String, value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$seed:$value".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun seedLong(seed: String, value: String): Long = ByteBuffer.wrap(
        MessageDigest.getInstance("SHA-256").digest("$seed:$value".toByteArray()).copyOfRange(0, 8),
    ).long

    private fun encodeCursor(startedAt: Instant, id: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("${startedAt}|$id".toByteArray())

    private fun decodeCursor(cursor: String): Pair<Instant, String> = try {
        val decoded = String(Base64.getUrlDecoder().decode(cursor)).split('|', limit = 2)
        require(decoded.size == 2 && UUID.fromString(decoded[1]).toString() == decoded[1])
        Instant.parse(decoded[0]) to decoded[1]
    } catch (_: Exception) {
        throw ApiException(400, "VALIDATION_ERROR", "Invalid history cursor", "cursor")
    }

    private fun Map<String, Any?>.jsonMap(key: String): Map<String, Any?> {
        val value = this[key] ?: return emptyMap()
        return when (value) {
            is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
            is PGobject -> mapper.readValue(value.value ?: "{}")
            is String -> mapper.readValue(value)
            else -> mapper.convertValue(value, Map::class.java).entries.associate { it.key.toString() to it.value }
        }
    }

    private fun Map<String, Any?>.instant(key: String): Instant = instantOrNull(key)
        ?: throw IllegalStateException("Missing timestamp $key")

    private fun Map<String, Any?>.instantOrNull(key: String): Instant? = when (val value = this[key]) {
        null -> null
        is Instant -> value
        is OffsetDateTime -> value.toInstant()
        is java.sql.Timestamp -> value.toInstant()
        else -> Instant.parse(value.toString())
    }

    private fun Map<String, Any?>.int(key: String): Int = (this[key] as Number).toInt()

    private fun Any?.mapObject(): Map<String, Any?> = when (this) {
        is Map<*, *> -> entries.associate { it.key.toString() to it.value }
        else -> mapper.convertValue(this, Map::class.java).entries.associate { it.key.toString() to it.value }
    }

    private fun Any?.asBooleanValue(): Boolean = this as? Boolean ?: false

    private data class IssuedForm(
        val snapshot: Map<String, Any?>,
        val exposures: List<Pair<String, String>>,
    )
}
