package ru.fsp.talent.shorttasks

import org.springframework.stereotype.Component
import ru.fsp.talent.consents.ConsentService
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.Store
import ru.fsp.talent.invitations.PageCursor
import ru.fsp.talent.invitations.asInstant
import ru.fsp.talent.invitations.checkPageLimit
import ru.fsp.talent.invitations.listValue
import ru.fsp.talent.invitations.objectValue
import ru.fsp.talent.invitations.requireUuid
import ru.fsp.talent.privacy.PublicTextRedactor
import java.time.Instant

@Component
class ShortTaskService(
    private val store: Store,
    private val contracts: Contracts,
    private val consents: ConsentService,
) {
    fun create(actor: Actor, body: Map<String, Any?>): Map<String, Any?> {
        val companyId = requireCompany(actor)
        contracts.validate("ShortTaskCreateRequest", body)
        val validated = ShortTaskRules.validateCreate(body, store.now())
        val needId = requireUuid(body["need_id"]?.toString() ?: "", "need_id")
        val candidateId = requireUuid(body["candidate_id"]?.toString() ?: "", "candidate_id")
        val expectedNeedVersion = (body["expected_need_version"] as? Number)?.toInt()
            ?: throw ApiException(400, "VALIDATION_ERROR", "expected_need_version is required", "expected_need_version")

        return store.tx.execute {
            val need = store.one(
                "SELECT * FROM employer_needs WHERE id = :need AND company_id = :company FOR UPDATE",
                mapOf("need" to needId, "company" to companyId),
            ) ?: throw ApiException(404, "NOT_FOUND", "Need not found")
            val currentNeedVersion = (need["version"] as? Number)?.toInt() ?: 0
            if (currentNeedVersion != expectedNeedVersion) throw versionConflict(currentNeedVersion)
            if (need["status"] != "active") throw ApiException(409, "INVALID_STATE", "Only an active need can receive a task")

            // Consent updates lock the user before the profile; use the same order while pinning publication access.
            store.one(
                "SELECT id FROM users WHERE id = :candidate AND email_verified_at IS NOT NULL AND deleted_at IS NULL FOR SHARE",
                mapOf("candidate" to candidateId),
            ) ?: throw ApiException(404, "NOT_FOUND", "Candidate not found")
            val publicCandidate = store.one(
                """SELECT cp.user_id FROM candidate_profiles cp JOIN users u ON u.id = cp.user_id
                   WHERE cp.user_id = :candidate AND cp.visibility = 'public'
                     AND u.email_verified_at IS NOT NULL AND u.deleted_at IS NULL
                     ${consents.publicationGateSql()}
                   FOR SHARE OF cp""".trimIndent(),
                mapOf("candidate" to candidateId),
            ) ?: throw ApiException(404, "NOT_FOUND", "Candidate not found")
            if (publicCandidate.isEmpty()) throw ApiException(404, "NOT_FOUND", "Candidate not found")

            val company = store.one("SELECT id, data FROM companies WHERE id = :company", mapOf("company" to companyId))
                ?: throw ApiException(404, "NOT_FOUND", "Company not found")
            val needData = store.objectValue(need["data"])
            val taskId = store.id()
            val rubric = validated.rubric
            store.exec(
                """INSERT INTO short_tasks(
                       id, company_id, candidate_id, need_id, need_version, company_snapshot, need_snapshot,
                       title, purpose, instructions, estimated_minutes, due_at, evaluation_mode,
                       expected_answer, rubric, status, sharing_notice_version, created_at, updated_at
                   ) VALUES (
                       :id, :company, :candidate, :need, :needVersion, :companySnapshot, :needSnapshot,
                       :title, :purpose, :instructions, :estimatedMinutes, :dueAt, :evaluationMode,
                       :expectedAnswer, :rubric, 'offered', :noticeVersion, :now, :now
                   )""".trimIndent(),
                mapOf(
                    "id" to taskId, "company" to companyId, "candidate" to candidateId, "need" to needId,
                    "needVersion" to currentNeedVersion,
                    "companySnapshot" to store.json(mapOf("id" to companyId, "data" to company["data"])),
                    "needSnapshot" to store.json(mapOf("id" to needId, "version" to currentNeedVersion, "status" to need["status"], "data" to needData)),
                    "title" to validated.title, "purpose" to validated.purpose, "instructions" to validated.instructions,
                    "estimatedMinutes" to validated.estimatedMinutes, "dueAt" to validated.dueAt,
                    "evaluationMode" to validated.evaluationMode, "expectedAnswer" to validated.expectedAnswer,
                    "rubric" to store.json(rubric), "noticeVersion" to ShortTaskRules.SHARING_NOTICE_VERSION,
                    "now" to store.now(),
                ),
            )
            taskDto(taskForUpdate(taskId), ShortTaskView.COMPANY, includeSolution = false)
        } ?: throw IllegalStateException("Short-task creation returned no result")
    }

    fun list(actor: Actor, limitInput: Int, cursorInput: String?): Map<String, Any?> {
        val limit = checkPageLimit(limitInput)
        if ((cursorInput?.length ?: 0) > 512) throw ApiException(400, "VALIDATION_ERROR", "Invalid cursor", "cursor")
        val cursor = PageCursor.decode(cursorInput)
        val (scope, scopeId, view) = actorScope(actor)
        return store.tx.execute {
            val clauses = mutableListOf("$scope = :scopeId")
            val params = mutableMapOf<String, Any?>("scopeId" to scopeId, "limit" to limit + 1)
            if (cursor != null) {
                clauses += "(created_at, id) < (:cursorAt, :cursorId)"
                params["cursorAt"] = cursor.at
                params["cursorId"] = cursor.id
            }
            val selected = store.rows(
                "SELECT id, created_at FROM short_tasks WHERE ${clauses.joinToString(" AND ")} ORDER BY created_at DESC, id DESC LIMIT :limit",
                params,
            )
            val page = selected.take(limit).mapNotNull { key ->
                val row = store.one(
                    "SELECT * FROM short_tasks WHERE id=:id AND $scope = :scopeId FOR UPDATE",
                    mapOf("id" to key.getValue("id"), "scopeId" to scopeId),
                ) ?: return@mapNotNull null
                val refreshed = expireIfDue(row)
                taskDto(refreshed, view)
            }
            val nextCursor = if (selected.size > limit) selected[limit - 1].let { row ->
                val created = row["created_at"].asInstant() ?: throw IllegalStateException("created_at missing")
                PageCursor(created, row["id"].toString()).encode()
            } else null
            mapOf("items" to page, "next_cursor" to nextCursor)
        } ?: throw IllegalStateException("Short-task list returned no result")
    }

    fun get(actor: Actor, taskIdInput: String): Map<String, Any?> {
        val taskId = requireUuid(taskIdInput, "id")
        val (scope, scopeId, view) = actorScope(actor)
        return store.tx.execute {
            val row = store.one("SELECT * FROM short_tasks WHERE id = :id AND $scope = :scopeId FOR UPDATE", mapOf("id" to taskId, "scopeId" to scopeId))
                ?: throw ApiException(404, "NOT_FOUND", "Short task not found")
            taskDto(expireIfDue(row), view)
        } ?: throw IllegalStateException("Short-task read returned no result")
    }

    fun accept(actor: Actor, taskIdInput: String, body: Map<String, Any?>): Map<String, Any?> = transitionCandidate(
        actor, taskIdInput, body, "ShortTaskAcceptRequest",
    ) { row, expected ->
        assertVersion(row, expected)
        assertState(row, "offered")
        val noticeVersion = bodyValue(body, "sharing_notice_version")
        if (noticeVersion != row["sharing_notice_version"]) throw ApiException(409, "VERSION_CONFLICT", "The sharing notice has changed")
        store.exec(
            "UPDATE short_tasks SET status='accepted', accepted_at=:now, version=version+1, updated_at=:now WHERE id=:id AND version=:version",
            mapOf("now" to store.now(), "id" to row.getValue("id"), "version" to expected),
        )
    }

    fun decline(actor: Actor, taskIdInput: String, body: Map<String, Any?>): Map<String, Any?> = transitionCandidate(
        actor, taskIdInput, body, "ShortTaskTransitionRequest",
    ) { row, expected ->
        assertVersion(row, expected)
        assertState(row, "offered")
        store.exec(
            "UPDATE short_tasks SET status='declined', declined_at=:now, version=version+1, updated_at=:now WHERE id=:id AND version=:version",
            mapOf("now" to store.now(), "id" to row.getValue("id"), "version" to expected),
        )
    }

    fun submit(actor: Actor, taskIdInput: String, body: Map<String, Any?>): Map<String, Any?> = transitionCandidate(
        actor, taskIdInput, body, "ShortTaskSubmissionRequest",
    ) { row, expected ->
        assertVersion(row, expected)
        assertState(row, "accepted")
        if (row["sharing_revoked_at"] != null) throw ApiException(409, "INVALID_STATE", "Solution sharing was revoked")
        val dueAt = row["due_at"].asInstant() ?: throw ApiException(409, "INVALID_STATE", "Task deadline is unavailable")
        if (!dueAt.isAfter(store.now())) throw ApiException(409, "INVALID_STATE", "Task deadline has passed")
        val solution = bodyValue(body, "solution")
        val result = if (row["evaluation_mode"] == "exact") {
            mapOf("mode" to "exact", "matched" to exactAnswerMatches(row["expected_answer"] as? String, solution), "source" to "server_exact_match")
        } else null
        store.exec(
            """UPDATE short_tasks SET status='submitted', solution=:solution, automatic_result=:automaticResult,
                      submitted_at=:now, version=version+1, updated_at=:now WHERE id=:id AND version=:version""".trimIndent(),
            mapOf("solution" to solution, "automaticResult" to result?.let(store::json), "now" to store.now(), "id" to row.getValue("id"), "version" to expected),
        )
    }

    fun withdraw(actor: Actor, taskIdInput: String, body: Map<String, Any?>): Map<String, Any?> = when (actor.role) {
        "candidate" -> transitionCandidate(actor, taskIdInput, body, "ShortTaskTransitionRequest") { row, expected ->
            assertVersion(row, expected)
            if (row["status"] !in setOf("accepted", "submitted", "reviewed")) throw invalidState()
            withdrawRow(row, expected)
        }
        "employer" -> transitionCompany(actor, taskIdInput, body) { row, expected ->
            assertVersion(row, expected)
            if (row["status"] !in setOf("offered", "accepted")) throw invalidState()
            withdrawRow(row, expected)
        }
        else -> throw ApiException(403, "FORBIDDEN", "Candidate or employer role required")
    }

    fun revokeSharing(actor: Actor, taskIdInput: String, body: Map<String, Any?>): Map<String, Any?> = transitionCandidate(
        actor, taskIdInput, body, "ShortTaskTransitionRequest",
    ) { row, expected ->
        assertVersion(row, expected)
        if (row["accepted_at"] == null || row["status"] in setOf("offered", "declined", "expired")) throw invalidState()
        if (row["sharing_revoked_at"] == null) {
            store.exec(
                "UPDATE short_tasks SET sharing_revoked_at=:now, version=version+1, updated_at=:now WHERE id=:id AND version=:version",
                mapOf("now" to store.now(), "id" to row.getValue("id"), "version" to expected),
            )
        }
    }

    fun review(actor: Actor, taskIdInput: String, body: Map<String, Any?>): Map<String, Any?> {
        val companyId = requireCompany(actor)
        contracts.validate("ShortTaskReviewRequest", body)
        val taskId = requireUuid(taskIdInput, "id")
        val expected = ShortTaskRules.version(body)
        return store.tx.execute {
            val row = taskForCompanyUpdate(taskId, companyId)
            assertVersion(row, expected)
            assertState(row, "submitted")
            if (row["sharing_revoked_at"] != null || row["solution"] == null) {
                throw ApiException(404, "NOT_FOUND", "Shared solution not found")
            }
            if (row["evaluation_mode"] != "rubric") throw ApiException(409, "INVALID_STATE", "Only rubric tasks support employer review")
            val review = ShortTaskRules.validateReview(body, store.listValue(row["rubric"])) + mapOf("reviewed_at" to store.now().toString())
            store.exec(
                """UPDATE short_tasks SET status='reviewed', employer_review=:review, reviewed_at=:now,
                          version=version+1, updated_at=:now WHERE id=:id AND version=:version""".trimIndent(),
                mapOf("review" to store.json(review), "now" to store.now(), "id" to taskId, "version" to expected),
            )
            taskDto(taskForUpdate(taskId), ShortTaskView.COMPANY, includeSolution = false)
        } ?: throw IllegalStateException("Short-task review returned no result")
    }

    /** The controller wraps MutationService inside this transaction so cached review replays cannot bypass revoked sharing. */
    fun <T : Any> withCurrentCompanySharing(actor: Actor, taskIdInput: String, action: () -> T): T {
        val companyId = requireCompany(actor)
        val taskId = requireUuid(taskIdInput, "id")
        return store.tx.execute<T> {
            val row = taskForCompanyUpdate(taskId, companyId)
            if (row["accepted_at"] == null || row["sharing_revoked_at"] != null || row["solution"] == null) {
                throw ApiException(404, "NOT_FOUND", "Shared solution not found")
            }
            action()
        } ?: throw IllegalStateException("Short-task sharing guard returned no result")
    }

    private fun transitionCandidate(
        actor: Actor,
        taskIdInput: String,
        body: Map<String, Any?>,
        schema: String,
        transition: (Map<String, Any?>, Int) -> Unit,
    ): Map<String, Any?> {
        val candidateId = requireCandidate(actor)
        contracts.validate(schema, body)
        val taskId = requireUuid(taskIdInput, "id")
        val expected = ShortTaskRules.version(body)
        return store.tx.execute {
            val row = store.one("SELECT * FROM short_tasks WHERE id=:id AND candidate_id=:candidate FOR UPDATE", mapOf("id" to taskId, "candidate" to candidateId))
                ?: throw ApiException(404, "NOT_FOUND", "Short task not found")
            val current = expireIfDue(row)
            transition(current, expected)
            taskDto(taskForUpdate(taskId), ShortTaskView.CANDIDATE)
        } ?: throw IllegalStateException("Short-task transition returned no result")
    }

    private fun transitionCompany(
        actor: Actor,
        taskIdInput: String,
        body: Map<String, Any?>,
        transition: (Map<String, Any?>, Int) -> Unit,
    ): Map<String, Any?> {
        val companyId = requireCompany(actor)
        contracts.validate("ShortTaskTransitionRequest", body)
        val taskId = requireUuid(taskIdInput, "id")
        val expected = ShortTaskRules.version(body)
        return store.tx.execute {
            val row = taskForCompanyUpdate(taskId, companyId)
            val current = expireIfDue(row)
            transition(current, expected)
            taskDto(taskForUpdate(taskId), ShortTaskView.COMPANY, includeSolution = false)
        } ?: throw IllegalStateException("Short-task transition returned no result")
    }

    private fun withdrawRow(row: Map<String, Any?>, expected: Int) {
        store.exec(
            "UPDATE short_tasks SET status='withdrawn', withdrawn_at=:now, version=version+1, updated_at=:now WHERE id=:id AND version=:version",
            mapOf("now" to store.now(), "id" to row.getValue("id"), "version" to expected),
        )
    }

    private fun expireIfDue(row: Map<String, Any?>): Map<String, Any?> {
        val state = row["status"]?.toString()
        val dueAt = row["due_at"].asInstant()
        if (state in setOf("offered", "accepted") && dueAt != null && !dueAt.isAfter(store.now())) {
            store.exec(
                "UPDATE short_tasks SET status='expired', version=version+1, updated_at=:now WHERE id=:id AND status=:status AND version=:version",
                mapOf("now" to store.now(), "id" to row.getValue("id"), "status" to state, "version" to row["version"]),
            )
            return taskForUpdate(row.getValue("id").toString())
        }
        return row
    }

    private fun taskForUpdate(id: String): Map<String, Any?> = store.one(
        "SELECT * FROM short_tasks WHERE id=:id FOR UPDATE",
        mapOf("id" to id),
    ) ?: throw ApiException(404, "NOT_FOUND", "Short task not found")

    private fun taskForCompanyUpdate(id: String, companyId: String): Map<String, Any?> = store.one(
        "SELECT * FROM short_tasks WHERE id=:id AND company_id=:company FOR UPDATE",
        mapOf("id" to id, "company" to companyId),
    ) ?: throw ApiException(404, "NOT_FOUND", "Short task not found")

    private fun taskDto(row: Map<String, Any?>, view: ShortTaskView, includeSolution: Boolean = true): Map<String, Any?> {
        val canShare = row["accepted_at"] != null && row["sharing_revoked_at"] == null && row["solution"] != null
        val rawSolution = if (view == ShortTaskView.CANDIDATE) row["solution"] as? String
            else if (canShare && includeSolution) row["solution"] as? String else null
        val solution = if (view == ShortTaskView.COMPANY) PublicTextRedactor.redact(rawSolution, 20_000) else rawSolution
        val needSnapshot = store.objectValue(row["need_snapshot"])
        val needData = (needSnapshot["data"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
        return linkedMapOf(
            "id" to row.getValue("id").toString(),
            "need_id" to row.getValue("need_id").toString(),
            "candidate_id" to row.getValue("candidate_id").toString(),
            "need_version" to row.getValue("need_version"),
            "need_title" to (needData["title"]?.toString() ?: ""),
            "title" to row.getValue("title").toString(),
            "purpose" to row.getValue("purpose").toString(),
            "instructions" to row.getValue("instructions").toString(),
            "estimated_minutes" to row.getValue("estimated_minutes"),
            "due_at" to row["due_at"].asInstant()?.toString(),
            "evaluation_mode" to row.getValue("evaluation_mode").toString(),
            "rubric" to store.listValue(row["rubric"]),
            "status" to row.getValue("status").toString(),
            "version" to row.getValue("version"),
            "sharing_notice" to mapOf("version" to row.getValue("sharing_notice_version"), "text" to ShortTaskRules.SHARING_NOTICE_TEXT),
            "accepted_at" to row["accepted_at"].asInstant()?.toString(),
            "sharing_revoked_at" to row["sharing_revoked_at"].asInstant()?.toString(),
            "solution" to solution,
            "automatic_result" to store.objectValue(row["automatic_result"]).takeIf { it.isNotEmpty() && (view == ShortTaskView.CANDIDATE || canShare) },
            "employer_review" to store.objectValue(row["employer_review"]).takeIf { it.isNotEmpty() && (view == ShortTaskView.CANDIDATE || canShare) },
            "created_at" to row["created_at"].asInstant()?.toString(),
            "updated_at" to row["updated_at"].asInstant()?.toString(),
        )
    }

    private fun actorScope(actor: Actor): Triple<String, String, ShortTaskView> = when (actor.role) {
        "candidate" -> Triple("candidate_id", requireCandidate(actor), ShortTaskView.CANDIDATE)
        "employer" -> Triple("company_id", requireCompany(actor), ShortTaskView.COMPANY)
        else -> throw ApiException(403, "FORBIDDEN", "Candidate or employer role required")
    }

    private fun requireCandidate(actor: Actor): String {
        if (actor.role != "candidate") throw ApiException(403, "FORBIDDEN", "Candidate access required")
        return actor.id
    }

    private fun requireCompany(actor: Actor): String {
        if (actor.role != "employer") throw ApiException(403, "FORBIDDEN", "Employer access required")
        return actor.companyId ?: throw ApiException(403, "FORBIDDEN", "Company access required")
    }

    private fun bodyValue(body: Map<String, Any?>, field: String): String = body[field] as? String
        ?: throw ApiException(400, "VALIDATION_ERROR", "$field is required", field)

    private fun assertVersion(row: Map<String, Any?>, expected: Int) {
        val current = (row["version"] as? Number)?.toInt() ?: 0
        if (current != expected) throw versionConflict(current)
    }

    private fun assertState(row: Map<String, Any?>, expected: String) {
        if (row["status"] != expected) throw invalidState()
    }

    private fun versionConflict(currentVersion: Int) = ApiException(
        409, "VERSION_CONFLICT", "Short task has changed", currentVersion = currentVersion,
    )

    private fun invalidState() = ApiException(409, "INVALID_STATE", "Short task cannot make this transition")
}

private enum class ShortTaskView { CANDIDATE, COMPANY }
