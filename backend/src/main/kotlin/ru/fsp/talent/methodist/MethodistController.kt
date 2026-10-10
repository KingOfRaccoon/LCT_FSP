package ru.fsp.talent.methodist

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.MutationService
import ru.fsp.talent.core.Store
import org.springframework.core.io.ClassPathResource
import ru.fsp.talent.core.AuthAccess

private const val PILOT_MINIMUM_SAMPLE = 10

@RestController
@RequestMapping("/api/v1/methodist")
class MethodistController(
    private val access: MethodistAccess,
    private val service: MethodistService,
    private val contracts: Contracts,
    private val mutations: MutationService,
) {
    @GetMapping("/bank/monitor")
    fun monitor(request: HttpServletRequest): Map<String, Any?> {
        access.require(request)
        return contracts.envelope(service.bankMonitor())
    }

    @GetMapping("/drafts")
    fun listDrafts(request: HttpServletRequest): Map<String, Any?> {
        access.require(request)
        return contracts.envelope(service.listDrafts())
    }

    @PostMapping("/drafts")
    fun createDraft(
        request: HttpServletRequest,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = access.require(request)
        contracts.validate("MethodistDraftRequest", body)
        val payload = service.validateDraft(body)
        return mutations.run(request, "methodist:${actor.id}", body, status = 201) {
            service.createDraft(actor.id, payload)
        }
    }

    @PatchMapping("/drafts/{draft_id}")
    fun updateDraft(
        request: HttpServletRequest,
        @PathVariable("draft_id") draftId: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = access.require(request)
        contracts.validate("MethodistDraftPatchRequest", body)
        val expectedVersion = service.expectedVersion(body)
        val payload = service.validateDraft(body.filterKeys { it != "expected_version" })
        return mutations.run(request, "methodist:${actor.id}", body) {
            service.updateDraft(actor.id, draftId, expectedVersion, payload)
        }
    }

    @PostMapping("/drafts/{draft_id}/review")
    fun reviewDraft(
        request: HttpServletRequest,
        @PathVariable("draft_id") draftId: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = access.require(request)
        contracts.validate("MethodistReviewRequest", body)
        val review = service.validateReview(body)
        return mutations.run(request, "methodist:${actor.id}", body) {
            service.reviewDraft(actor.id, draftId, review)
        }
    }

    @PostMapping("/drafts/{draft_id}/pilot")
    fun recordPilot(
        request: HttpServletRequest,
        @PathVariable("draft_id") draftId: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = access.require(request)
        contracts.validate("MethodistPilotRequest", body)
        val pilot = service.validatePilot(body)
        return mutations.run(request, "methodist:${actor.id}", body) {
            service.recordPilot(actor.id, draftId, pilot)
        }
    }

    @GetMapping("/drafts/{draft_id}/pilot-report")
    fun pilotReport(
        request: HttpServletRequest,
        @PathVariable("draft_id") draftId: String,
    ): Map<String, Any?> {
        access.require(request)
        return contracts.envelope(service.pilotReport(draftId))
    }

    @GetMapping("/drafts/{draft_id}/export")
    fun exportDraft(
        request: HttpServletRequest,
        @PathVariable("draft_id") draftId: String,
    ): Map<String, Any?> {
        access.require(request)
        return contracts.envelope(service.exportDraft(draftId))
    }
}

@org.springframework.stereotype.Component
class MethodistAccess(
    private val auth: AuthAccess,
    private val store: Store,
) {
    fun require(request: HttpServletRequest) = auth.verified(auth.current(request)).also { actor ->
        val granted = store.one(
            "SELECT active FROM assessment_methodists WHERE user_id = :user",
            mapOf("user" to actor.id),
        )?.get("active") as? Boolean ?: false
        if (!granted) throw ApiException(403, "FORBIDDEN", "Methodist access required")
    }
}

@org.springframework.stereotype.Component
class MethodistService(
    private val store: Store,
) {
    fun validateDraft(input: Map<String, Any?>): Map<String, Any?> {
        val fields = setOf(
            "source_title", "source_reference", "source_excerpt", "competency_id", "grade",
            "question", "options", "correct_option", "rubric",
        )
        if (input.keys != fields) invalid("Draft fields are incomplete or contain unknown values")
        val sourceTitle = text(input["source_title"], "source_title", 1, 200)
        val sourceReference = text(input["source_reference"], "source_reference", 1, 2048)
        val excerpt = text(input["source_excerpt"], "source_excerpt", 1, 5000)
        val competency = text(input["competency_id"], "competency_id", 1, 80)
        if (competency !in COMPETENCIES) invalid("Unknown competency_id", "competency_id")
        val grade = text(input["grade"], "grade", 1, 20)
        if (grade !in GRADES) invalid("grade must be junior or middle", "grade")
        val question = text(input["question"], "question", 1, 5000)
        val options = (input["options"] as? List<*>) ?: invalid("options must contain exactly four strings", "options")
        if (options.size != 4) invalid("options must contain exactly four strings", "options")
        val checkedOptions = options.mapIndexed { index, option -> text(option, "options[$index]", 1, 1000) }
        if (checkedOptions.map { it.trim().lowercase() }.toSet().size != 4) {
            invalid("options must be distinct", "options")
        }
        val correctOption = wholeNumber(input["correct_option"], "correct_option")
        if (correctOption !in 0..3) invalid("correct_option must be from 0 to 3", "correct_option")
        val rubric = text(input["rubric"], "rubric", 1, 4000)
        return mapOf(
            "source_title" to sourceTitle,
            "source_reference" to sourceReference,
            "source_excerpt" to excerpt,
            "competency_id" to competency,
            "grade" to grade,
            "question" to question,
            "options" to checkedOptions,
            "correct_option" to correctOption,
            "rubric" to rubric,
        )
    }

    fun expectedVersion(body: Map<String, Any?>): Int {
        if ("expected_version" !in body) invalid("expected_version is required", "expected_version")
        return wholeNumber(body["expected_version"], "expected_version").also {
            if (it < 1) invalid("expected_version must be positive", "expected_version")
        }
    }

    fun validateReview(body: Map<String, Any?>): Map<String, Any?> {
        if (body.keys != setOf("expected_version", "outcome", "marks", "comment")) {
            invalid("Review fields are incomplete or contain unknown values")
        }
        val version = expectedVersion(body)
        val outcome = text(body["outcome"], "outcome", 1, 30)
        if (outcome !in REVIEW_OUTCOMES) invalid("Unknown review outcome", "outcome")
        val marks = body["marks"] as? Map<*, *> ?: invalid("marks must contain all four review axes", "marks")
        val axes = setOf("relevance", "level", "clarity", "correctness")
        if (marks.keys.map { it.toString() }.toSet() != axes || marks.values.any { it !is Boolean }) {
            invalid("marks must contain boolean relevance, level, clarity, and correctness", "marks")
        }
        val comment = text(body["comment"], "comment", 0, 1000)
        return mapOf(
            "expected_version" to version,
            "outcome" to outcome,
            "marks" to axes.associateWith { marks[it] as Boolean },
            "comment" to comment,
        )
    }

    fun validatePilot(body: Map<String, Any?>): Map<String, Any?> {
        if (body.keys != setOf("expected_version", "observations")) invalid("Pilot fields are incomplete or contain unknown values")
        val version = expectedVersion(body)
        val observations = body["observations"] as? List<*> ?: invalid("observations must be an array", "observations")
        if (observations.isEmpty() || observations.size > 500) invalid("observations must contain between 1 and 500 rows", "observations")
        val rows = observations.mapIndexed { index, value ->
            val row = value as? Map<*, *> ?: invalid("Each observation must be an object", "observations[$index]")
            if (row.keys.map { it.toString() }.toSet() != setOf("source", "correct") || row["correct"] !is Boolean) {
                invalid("Each observation needs source and boolean correct", "observations[$index]")
            }
            val source = row["source"] as? String ?: invalid("Invalid observation source", "observations[$index].source")
            if (source !in PILOT_SOURCES) invalid("source must be synthetic or pilot_record", "observations[$index].source")
            mapOf("source" to source, "correct" to row["correct"] as Boolean)
        }
        return mapOf("expected_version" to version, "observations" to rows)
    }

    fun createDraft(actorId: String, payload: Map<String, Any?>): Map<String, Any?> {
        val id = store.id()
        store.exec(
            """INSERT INTO methodist_drafts(id, payload, created_by, updated_by)
               VALUES (:id, :payload, :actor, :actor)""".trimIndent(),
            mapOf("id" to id, "payload" to store.json(payload), "actor" to actorId),
        )
        store.exec(
            "INSERT INTO methodist_draft_revisions(draft_id, version, payload, actor_id) VALUES (:id, 1, :payload, :actor)",
            mapOf("id" to id, "payload" to store.json(payload), "actor" to actorId),
        )
        return draftDto(id, 1, "draft", payload, null)
    }

    fun listDrafts(): Map<String, Any?> = mapOf(
        "items" to store.rows("SELECT id, version, status, payload FROM methodist_drafts ORDER BY updated_at DESC, id")
            .map { draftDto(it) },
    )

    fun updateDraft(actorId: String, draftId: String, expectedVersion: Int, payload: Map<String, Any?>): Map<String, Any?> {
        val current = store.one("SELECT id, version, status, payload FROM methodist_drafts WHERE id=:id FOR UPDATE", mapOf("id" to draftId))
            ?: notFound()
        val version = (current["version"] as Number).toInt()
        if (version != expectedVersion) versionConflict(version)
        val nextVersion = version + 1
        store.exec(
            """UPDATE methodist_drafts SET version=:version, status='draft', payload=:payload,
               updated_by=:actor, updated_at=now() WHERE id=:id""".trimIndent(),
            mapOf("version" to nextVersion, "payload" to store.json(payload), "actor" to actorId, "id" to draftId),
        )
        store.exec(
            "INSERT INTO methodist_draft_revisions(draft_id, version, payload, actor_id) VALUES (:id, :version, :payload, :actor)",
            mapOf("id" to draftId, "version" to nextVersion, "payload" to store.json(payload), "actor" to actorId),
        )
        return draftDto(draftId, nextVersion, "draft", payload, null)
    }

    fun reviewDraft(actorId: String, draftId: String, review: Map<String, Any?>): Map<String, Any?> {
        val current = lockDraft(draftId)
        val version = (current["version"] as Number).toInt()
        checkVersion(version, review.getValue("expected_version") as Int)
        if (current["status"] != "draft") throw ApiException(409, "INVALID_STATE", "Only a draft version can be reviewed")
        val outcome = review.getValue("outcome").toString()
        val marks = review.getValue("marks") as Map<*, *>
        store.exec(
            """INSERT INTO methodist_draft_reviews(id, draft_id, version, actor_id, outcome, marks, "comment")
               VALUES (:id, :draftId, :version, :actor, :outcome, :marks, :comment)""".trimIndent(),
            mapOf(
                "id" to store.id(), "draftId" to draftId, "version" to version, "actor" to actorId,
                "outcome" to outcome, "marks" to store.json(marks), "comment" to review.getValue("comment"),
            ),
        )
        store.exec("UPDATE methodist_drafts SET status=:status, updated_by=:actor, updated_at=now() WHERE id=:id",
            mapOf("status" to outcome, "actor" to actorId, "id" to draftId))
        return draftDto(store.one(
            "SELECT id, version, status, payload FROM methodist_drafts WHERE id=:id",
            mapOf("id" to draftId),
        ) ?: notFound())
    }

    fun recordPilot(actorId: String, draftId: String, pilot: Map<String, Any?>): Map<String, Any?> {
        val current = lockDraft(draftId)
        val version = (current["version"] as Number).toInt()
        checkVersion(version, pilot.getValue("expected_version") as Int)
        if (current["status"] != "approved") throw ApiException(409, "INVALID_STATE", "Only an approved draft version can be piloted")
        @Suppress("UNCHECKED_CAST")
        val observations = pilot.getValue("observations") as List<Map<String, Any?>>
        observations.forEach { observation ->
            store.exec(
                """INSERT INTO methodist_pilot_observations(id, draft_id, version, source, correct, actor_id)
                   VALUES (:id, :draftId, :version, :source, :correct, :actor)""".trimIndent(),
                mapOf(
                    "id" to store.id(), "draftId" to draftId, "version" to version,
                    "source" to observation.getValue("source"), "correct" to observation.getValue("correct"),
                    "actor" to actorId,
                ),
            )
        }
        return mapOf("draft_id" to draftId, "version" to version, "accepted_count" to observations.size)
    }

    fun pilotReport(draftId: String): Map<String, Any?> {
        val current = store.one("SELECT id, version, status, payload FROM methodist_drafts WHERE id=:id", mapOf("id" to draftId)) ?: notFound()
        val version = (current["version"] as Number).toInt()
        val counts = store.rows(
            """SELECT source, count(*) AS sample_count, count(*) FILTER (WHERE correct) AS correct_count
               FROM methodist_pilot_observations WHERE draft_id=:id AND version=:version GROUP BY source""".trimIndent(),
            mapOf("id" to draftId, "version" to version),
        ).associate { row ->
            row.getValue("source").toString() to mapOf(
                "sample_count" to (row["sample_count"] as Number).toInt(),
                "correct_count" to (row["correct_count"] as Number).toInt(),
            )
        }
        val total = counts.values.sumOf { (it["sample_count"] as Number).toInt() }
        val correct = counts.values.sumOf { (it["correct_count"] as Number).toInt() }
        val sourceCounts = PILOT_SOURCES.associateWith { source -> (counts[source]?.get("sample_count") as? Int) ?: 0 }
        val sourceAccuracy = PILOT_SOURCES.associateWith { source ->
            val row = counts[source]
            val n = row?.get("sample_count") as? Int ?: 0
            if (n < PILOT_MINIMUM_SAMPLE) null else ((row?.get("correct_count") as? Int) ?: 0).toDouble() / n
        }
        val sourcesWithObservations = sourceCounts.filterValues { it > 0 }.keys
        return mapOf(
            "draft_id" to draftId,
            "version" to version,
            "status" to current["status"],
            "source_title" to jsonMap(current["payload"])["source_title"],
            "sample_count" to total,
            "correct_count" to correct,
            "accuracy" to if (total < PILOT_MINIMUM_SAMPLE || sourcesWithObservations.size != 1) null else correct.toDouble() / total,
            "source_counts" to sourceCounts,
            "accuracy_by_source" to sourceAccuracy,
            "minimum_sample_for_statistics" to PILOT_MINIMUM_SAMPLE,
            "evidence_sources" to counts.keys.sorted(),
            "human_results_claimed" to false,
        )
    }

    fun exportDraft(draftId: String): Map<String, Any?> {
        val row = store.one("SELECT id, version, status, payload FROM methodist_drafts WHERE id=:id", mapOf("id" to draftId)) ?: notFound()
        if (row["status"] != "approved") throw ApiException(409, "INVALID_STATE", "Only an approved draft version can be exported")
        val draft = jsonMap(row["payload"])
        val version = (row["version"] as Number).toInt()
        return mapOf(
            "draft_id" to draftId,
            "version" to version,
            "candidate_form" to mapOf(
                "format" to "draft-v1",
                "source" to mapOf("title" to draft["source_title"], "reference" to draft["source_reference"]),
                "competency_id" to draft["competency_id"],
                "grade" to draft["grade"],
                "question" to draft["question"],
                "options" to draft["options"],
            ),
            "private_answer_key" to mapOf(
                "format" to "draft-v1",
                "draft_id" to draftId,
                "version" to version,
                "correct_option" to draft["correct_option"],
                "source_excerpt" to draft["source_excerpt"],
                "rubric" to draft["rubric"],
            ),
            "live_bank_published" to false,
        )
    }

    fun bankMonitor(): Map<String, Any?> {
        val bank = ClassPathResource("assessment/question-bank.v1.json").inputStream.use(store.mapper::readTree)
        val bankVersion = bank.path("bank_version").asText()
        val exposureRows = store.rows(
            """SELECT exposure.family_id, exposure.variant_id, count(*) AS exposure_count
               FROM task_exposures exposure JOIN attempts attempt ON attempt.id=exposure.attempt_id
               WHERE attempt.bank_version=:bankVersion
               GROUP BY exposure.family_id, exposure.variant_id
               ORDER BY exposure.family_id, exposure.variant_id""".trimIndent(),
            mapOf("bankVersion" to bankVersion),
        )
        val familyCounts = exposureRows.groupBy { it.getValue("family_id").toString() }.mapValues { (_, rows) ->
            rows.sumOf { (it["exposure_count"] as Number).toLong() }
        }
        val variantCounts = exposureRows.associate { it.getValue("variant_id").toString() to (it["exposure_count"] as Number).toLong() }
        val families = bank.path("families").toList()
        val coverage = GRADES.flatMap { grade -> COMPETENCIES.map { competency ->
            val block = families.filter { it.path("grade").asText() == grade && it.path("competency_id").asText() == competency }
            val exposures = block.sumOf { familyCounts[it.path("id").asText()] ?: 0L }
            mapOf(
                "grade" to grade,
                "competency_id" to competency,
                "families_total" to block.size,
                "families_exposed" to block.count { (familyCounts[it.path("id").asText()] ?: 0L) > 0L },
                "exposure_count" to exposures,
            )
        } }
        val repeated = families.map { family ->
            val id = family.path("id").asText()
            val count = familyCounts[id] ?: 0L
            mapOf(
                "grade" to family.path("grade").asText(),
                "competency_id" to family.path("competency_id").asText(),
                "family_id" to id,
                "exposure_count" to count,
                "variant_exposures" to family.path("variants").map { variant ->
                    mapOf("variant_id" to variant.path("id").asText(), "exposure_count" to (variantCounts[variant.path("id").asText()] ?: 0L))
                },
                "repeated_use" to (count >= 3),
                "review_required" to (count >= 3),
                "review_signal" to if (count >= 3) "repeated_exposure" else null,
            )
        }
        return mapOf(
            "bank_version" to bankVersion,
            "families_total" to families.size,
            "coverage" to coverage,
            "repeated_use" to repeated,
            "difficulty_windows" to difficultyWindows(bankVersion),
            "answer_keys_included" to false,
            "person_or_attempt_data_included" to false,
        )
    }

    private fun difficultyWindows(bankVersion: String): List<Map<String, Any?>> {
        val rows = store.rows(
            """SELECT date_trunc('month', evaluated_at)::date::text AS period_start,
                      requested_grade, result
               FROM attempts WHERE status='evaluated' AND evaluated_at >= now() - interval '12 months'
                 AND specialization_id='backend-python-v1' AND bank_version=:bankVersion
               ORDER BY period_start, requested_grade""".trimIndent(),
            mapOf("bankVersion" to bankVersion),
        )
        val grouped = rows.groupBy { it.getValue("period_start").toString() to it.getValue("requested_grade").toString() }
        val values = grouped.map { (key, sample) ->
            val n = sample.size
            val totalScores = sample.map { jsonMap(it["result"])["total_score"] as? Number }.filterNotNull()
            val blockScores = COMPETENCIES.associateWith { competency ->
                val scores = sample.flatMap { row ->
                    val blocks = jsonMap(row["result"])["block_scores"] as? List<*> ?: emptyList<Any>()
                    blocks.mapNotNull { block ->
                        val map = mapObject(block)
                        if (map["block_id"] == competency) (map["score"] as? Number)?.toDouble() else null
                    }
                }
                if (n < PILOT_MINIMUM_SAMPLE || scores.size < n) null else scores.average()
            }
            mapOf(
                "window" to key.first,
                "grade" to key.second,
                "sample_count" to n,
                "mean_total_score" to if (n < PILOT_MINIMUM_SAMPLE || totalScores.size < n) null else totalScores.map { it.toDouble() }.average(),
                "mean_block_scores" to blockScores,
                "review_required" to false,
            )
        }.sortedBy { it["window"].toString() + it["grade"].toString() }
        val reviewSignals = values
            .filter { it["mean_total_score"] != null }
            .groupBy { it.getValue("grade").toString() }
            .flatMap { (grade, gradeWindows) ->
                val ordered = gradeWindows.sortedBy { it.getValue("window").toString() }
                ordered.zipWithNext().mapNotNull { (previous, current) ->
                    val previousMean = previous.getValue("mean_total_score") as Double
                    val currentMean = current.getValue("mean_total_score") as Double
                    if (kotlin.math.abs(currentMean - previousMean) >= 2.0) {
                        grade to current.getValue("window").toString()
                    } else null
                }
            }.toSet()
        return values.map { value ->
            val shifted = (value.getValue("grade").toString() to value.getValue("window").toString()) in reviewSignals
            value + mapOf(
                "review_required" to shifted,
                "review_signal" to if (shifted) "aggregate_score_shift" else null,
                "interpretation" to if (shifted) "Aggregate signal for expert review; not evidence of leakage or misconduct." else "Descriptive aggregate only.",
            )
        }
    }

    private fun lockDraft(draftId: String) = store.one(
        "SELECT id, version, status, payload FROM methodist_drafts WHERE id=:id FOR UPDATE",
        mapOf("id" to draftId),
    ) ?: notFound()

    private fun checkVersion(current: Int, expected: Int) {
        if (current != expected) versionConflict(current)
    }

    private fun versionConflict(current: Int): Nothing = throw ApiException(
        409, "VERSION_CONFLICT", "Draft has changed", currentVersion = current,
    )

    private fun draftDto(row: Map<String, Any?>): Map<String, Any?> {
        val id = row.getValue("id").toString()
        val version = (row.getValue("version") as Number).toInt()
        val review = store.one(
            """SELECT actor_id, outcome, marks, "comment", created_at FROM methodist_draft_reviews
               WHERE draft_id=:id AND version=:version ORDER BY created_at DESC, id DESC LIMIT 1""".trimIndent(),
            mapOf("id" to id, "version" to version),
        )?.let {
            mapOf("actor_id" to it["actor_id"], "outcome" to it["outcome"], "marks" to it["marks"], "comment" to it["comment"], "reviewed_at" to it["created_at"])
        }
        return draftDto(id, version, row.getValue("status").toString(), jsonMap(row["payload"]), review)
    }

    private fun draftDto(id: String, version: Int, status: String, payload: Map<String, Any?>, review: Any?): Map<String, Any?> =
        payload + mapOf("id" to id, "version" to version, "status" to status, "review" to review)

    private fun text(value: Any?, field: String, min: Int, max: Int): String {
        val string = value as? String ?: invalid("$field must be text", field)
        if (string.length !in min..max || (min > 0 && string.isBlank())) invalid("$field has an invalid length", field)
        return string.trim()
    }

    private fun wholeNumber(value: Any?, field: String): Int {
        val number = value as? Number ?: invalid("$field must be an integer", field)
        val double = number.toDouble()
        if (!double.isFinite() || double % 1.0 != 0.0 || double !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
            invalid("$field must be an integer", field)
        }
        return double.toInt()
    }

    private fun jsonMap(value: Any?): Map<String, Any?> = when (value) {
        is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
        else -> emptyMap()
    }

    private fun mapObject(value: Any?): Map<String, Any?> = jsonMap(value)

    private fun invalid(message: String, field: String? = null): Nothing =
        throw ApiException(400, "VALIDATION_ERROR", message, field)

    private fun notFound(): Nothing = throw ApiException(404, "NOT_FOUND", "Draft was not found")

    companion object {
        private val GRADES = setOf("junior", "middle")
        private val COMPETENCIES = setOf("python-basics", "http-api", "sql-transactions", "reliability")
        private val REVIEW_OUTCOMES = setOf("approved", "changes_requested", "rejected")
        private val PILOT_SOURCES = listOf("synthetic", "pilot_record")
    }
}
