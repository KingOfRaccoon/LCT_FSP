package ru.fsp.talent.invitations

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.MutationService
import ru.fsp.talent.core.Store
import ru.fsp.talent.matching.MatchingService
import java.time.Instant

@RestController
@RequestMapping("/api/v1")
class InvitationsController(
    private val auth: AuthAccess,
    private val contracts: Contracts,
    private val mutations: MutationService,
    private val store: Store,
    private val matching: MatchingService,
) {
    @PostMapping("/invitations")
    fun create(
        request: HttpServletRequest,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        contracts.validate("InvitationCreateRequest", normalizeNullableComment(body))
        val companyId = actor.companyId!!
        requireCompanyOwner(actor.id, companyId)
        expirePair(companyId, body.getValue("candidate_id").toString())
        return mutations.run(request, "user:${actor.id}", body, status = 201) {
            val candidateId = body.getValue("candidate_id").toString()
            val candidateRow = store.one(
                """SELECT cp.user_id, cp.visibility, cp.data, cp.contacts, u.email_verified_at
                   FROM candidate_profiles cp JOIN users u ON u.id = cp.user_id AND u.deleted_at IS NULL
                   WHERE cp.user_id = :candidateId FOR UPDATE OF cp""".trimIndent(),
                mapOf("candidateId" to candidateId),
            ) ?: throw ApiException(404, "NOT_FOUND", "Candidate not found")
            val candidate = matching.publicCandidate(candidateRow)
            ensureContactMethodEnabled(body.getValue("contact_method").toString(), candidateRow["contacts"])
            val pending = store.one(
                """SELECT id FROM invitations WHERE company_id = :companyId AND candidate_id = :candidateId
                   AND state = 'pending' FOR UPDATE""".trimIndent(),
                mapOf("companyId" to companyId, "candidateId" to candidateId),
            )
            if (pending != null) throw ApiException(409, "INVALID_STATE", "An invitation is already pending for this candidate")
            val needId = body["need_id"]?.toString()
            val need = needId?.let { activeNeed(companyId, it) }
            val salary = body.getValue("salary") as Map<String, Any?>
            InvitationRules.validateSalary(salary, need?.second)
            val company = store.one("SELECT data FROM companies WHERE id = :id FOR UPDATE", mapOf("id" to companyId))
                ?: throw ApiException(404, "NOT_FOUND", "Company not found")
            val recentTerminal = store.one(
                """SELECT max(updated_at) AS terminal_at FROM invitations
                   WHERE company_id = :companyId AND candidate_id = :candidateId
                     AND state IN ('declined', 'expired')""".trimIndent(),
                mapOf("companyId" to companyId, "candidateId" to candidateId),
            )?.get("terminal_at").asInstant()
            if (recentTerminal != null) InvitationRules.ensureCooldown(recentTerminal, store.now())
            val dailyCount = (store.one(
                """SELECT count(*) AS total FROM invitations
                   WHERE company_id = :companyId AND created_at > now() - interval '1 day'""".trimIndent(),
                mapOf("companyId" to companyId),
            )?.get("total") as Number).toInt()
            if (dailyCount >= 20) throw ApiException(429, "RATE_LIMITED", "Daily invitation limit reached", retryable = true)

            val reasons = systemReasons(candidate, need?.second)
            val snapshot = snapshot(
                salary = salary,
                description = body.getValue("description").toString(),
                contactMethod = body.getValue("contact_method").toString(),
                reasons = reasons,
                comment = body["company_comment"],
                topics = stringList(body["discussion_topics"]),
                companyName = store.objectValue(company["data"])["name"]?.toString().orEmpty(),
                companyDetails = store.objectValue(company["data"]),
                hiringPlan = validatedHiringPlan(body["hiring_plan"]),
            )
            val id = store.id()
            val expires = store.now().plusSeconds(7 * 24 * 60 * 60)
            store.exec(
                """INSERT INTO invitations(id, company_id, candidate_id, need_id, version, state, snapshot, expires_at)
                   VALUES (:id, :companyId, :candidateId, :needId, 1, 'pending', :snapshot, :expiresAt)""".trimIndent(),
                mapOf(
                    "id" to id, "companyId" to companyId, "candidateId" to candidateId, "needId" to needId,
                    "snapshot" to store.json(snapshot), "expiresAt" to expires,
                ),
            )
            store.exec(
                """INSERT INTO invitation_versions(id, invitation_id, version, snapshot, state)
                   VALUES (:id, :invitationId, 1, :snapshot, 'pending')""".trimIndent(),
                mapOf("id" to store.id(), "invitationId" to id, "snapshot" to store.json(snapshot)),
            )
            invitation(id)!!
        }
    }

    @GetMapping("/me/invitations")
    fun received(
        request: HttpServletRequest,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): Map<String, Any?> {
        val actor = auth.candidate(request)
        expirePending()
        return contracts.envelope(list(actor.id, null, limit, cursor))
    }

    @GetMapping("/invitations/sent")
    fun sent(
        request: HttpServletRequest,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): Map<String, Any?> {
        val actor = auth.employer(request)
        requireCompanyOwner(actor.id, actor.companyId!!)
        expirePending()
        return contracts.envelope(list(null, actor.companyId, limit, cursor))
    }

    @GetMapping("/invitations/{invitation_id}")
    fun get(
        request: HttpServletRequest,
        @PathVariable("invitation_id") invitationId: String,
    ): Map<String, Any?> {
        val actor = auth.current(request)
        requireUuid(invitationId, "invitation_id")
        expirePending(invitationId)
        val row = store.one("SELECT candidate_id, company_id FROM invitations WHERE id = :id", mapOf("id" to invitationId))
            ?: throw ApiException(404, "NOT_FOUND", "Invitation not found")
        if (row["candidate_id"].toString() != actor.id && row["company_id"].toString() != actor.companyId) {
            throw ApiException(404, "NOT_FOUND", "Invitation not found")
        }
        return contracts.envelope(invitation(invitationId)!!)
    }

    @PostMapping("/invitations/{invitation_id}/decision")
    fun decide(
        request: HttpServletRequest,
        @PathVariable("invitation_id") invitationId: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.candidate(request)
        requireUuid(invitationId, "invitation_id")
        expirePending(invitationId)
        contracts.validate("InvitationDecisionRequest", body)
        val expectedVersion = (body.getValue("expected_version") as Number).toInt()
        return mutations.run(request, "user:${actor.id}", body) {
            val row = lockInvitation(invitationId, candidateId = actor.id)
            ensurePending(row, expectedVersion)
            val action = body.getValue("action").toString()
            val state = if (action == "accept") "accepted" else "declined"
            var grantId: String? = null
            if (action == "accept") {
                val active = store.one(
                    """SELECT id FROM contact_grants WHERE company_id = :companyId AND candidate_id = :candidateId
                       AND revoked_at IS NULL FOR UPDATE""".trimIndent(),
                    mapOf("companyId" to row["company_id"], "candidateId" to actor.id),
                )
                grantId = active?.get("id")?.toString() ?: store.id().also { id ->
                    store.exec(
                        """INSERT INTO contact_grants(id, company_id, candidate_id, basis_invitation_id, basis_version)
                           VALUES (:id, :companyId, :candidateId, :invitationId, :version)""".trimIndent(),
                        mapOf(
                            "id" to id, "companyId" to row["company_id"], "candidateId" to actor.id,
                            "invitationId" to invitationId, "version" to expectedVersion,
                        ),
                    )
                }
            }
            store.exec(
                """UPDATE invitations SET state = :state, contact_grant_id = :grantId, updated_at = now()
                   WHERE id = :id AND state = 'pending'""".trimIndent(),
                mapOf("state" to state, "grantId" to grantId, "id" to invitationId),
            )
            store.exec(
                "UPDATE invitation_versions SET state = :state WHERE invitation_id = :id AND version = :version",
                mapOf("state" to state, "id" to invitationId, "version" to expectedVersion),
            )
            invitation(invitationId)!!
        }
    }

    @PostMapping("/invitations/{invitation_id}/withdraw")
    fun withdraw(
        request: HttpServletRequest,
        @PathVariable("invitation_id") invitationId: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.employer(request)
        requireUuid(invitationId, "invitation_id")
        requireCompanyOwner(actor.id, actor.companyId!!)
        validateExpectedVersion(body)
        expirePending(invitationId)
        val expectedVersion = (body.getValue("expected_version") as Number).toInt()
        return mutations.run(request, "user:${actor.id}", body) {
            val row = lockInvitation(invitationId, companyId = actor.companyId)
            ensurePending(row, expectedVersion)
            store.exec("UPDATE invitations SET state = 'withdrawn', updated_at = now() WHERE id = :id", mapOf("id" to invitationId))
            store.exec(
                "UPDATE invitation_versions SET state = 'withdrawn' WHERE invitation_id = :id AND version = :version",
                mapOf("id" to invitationId, "version" to expectedVersion),
            )
            invitation(invitationId)!!
        }
    }

    @PostMapping("/invitations/{invitation_id}/supersede")
    fun supersede(
        request: HttpServletRequest,
        @PathVariable("invitation_id") invitationId: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        requireUuid(invitationId, "invitation_id")
        requireCompanyOwner(actor.id, actor.companyId!!)
        contracts.validate("InvitationSupersedeRequest", normalizeNullableComment(body))
        expirePending(invitationId)
        val expectedVersion = (body.getValue("expected_version") as Number).toInt()
        return mutations.run(request, "user:${actor.id}", body) {
            val current = lockInvitation(invitationId, companyId = actor.companyId)
            ensurePending(current, expectedVersion)
            val need = current["need_id"]?.toString()?.let { activeNeed(actor.companyId!!, it)?.second }
            val salary = body.getValue("salary") as Map<String, Any?>
            InvitationRules.validateSalary(salary, need)
            val candidateId = current.getValue("candidate_id").toString()
            val candidateRow = store.one(
                """SELECT cp.user_id, cp.visibility, cp.data, cp.contacts, u.email_verified_at
                   FROM candidate_profiles cp JOIN users u ON u.id = cp.user_id AND u.deleted_at IS NULL
                   WHERE cp.user_id = :candidateId""".trimIndent(),
                mapOf("candidateId" to candidateId),
            ) ?: throw ApiException(404, "NOT_FOUND", "Candidate not found")
            val candidate = matching.publicCandidate(candidateRow)
            ensureContactMethodEnabled(body.getValue("contact_method").toString(), candidateRow["contacts"])
            val oldSnapshot = store.objectValue(current["snapshot"])
            val nextVersion = expectedVersion + 1
            val nextSnapshot = snapshot(
                salary = salary,
                description = body.getValue("description").toString(),
                contactMethod = body.getValue("contact_method").toString(),
                reasons = systemReasons(candidate, need),
                comment = body["company_comment"],
                topics = stringList(body["discussion_topics"]),
                companyName = oldSnapshot["company_name"]?.toString().orEmpty(),
                companyDetails = mapOf(
                    "industry" to oldSnapshot["company_industry"],
                    "contact_email" to oldSnapshot["company_contact_email"],
                    "contact_phone" to oldSnapshot["company_contact_phone"],
                ),
                hiringPlan = if (body.containsKey("hiring_plan")) {
                    validatedHiringPlan(body["hiring_plan"])
                } else {
                    oldSnapshot["hiring_plan"]
                },
            )
            val expires = store.now().plusSeconds(7 * 24 * 60 * 60)
            store.exec(
                "UPDATE invitation_versions SET state = 'superseded' WHERE invitation_id = :id AND version = :version",
                mapOf("id" to invitationId, "version" to expectedVersion),
            )
            store.exec(
                """UPDATE invitations SET version = :version, snapshot = :snapshot, expires_at = :expiresAt,
                   updated_at = now() WHERE id = :id AND state = 'pending'""".trimIndent(),
                mapOf("version" to nextVersion, "snapshot" to store.json(nextSnapshot), "expiresAt" to expires, "id" to invitationId),
            )
            store.exec(
                """INSERT INTO invitation_versions(id, invitation_id, version, snapshot, state)
                   VALUES (:id, :invitationId, :version, :snapshot, 'pending')""".trimIndent(),
                mapOf(
                    "id" to store.id(), "invitationId" to invitationId, "version" to nextVersion,
                    "snapshot" to store.json(nextSnapshot),
                ),
            )
            invitation(invitationId)!!
        }
    }

    private fun list(candidateId: String?, companyId: String?, limit: Int, cursor: String?): Map<String, Any?> {
        val size = checkPageLimit(limit)
        val after = PageCursor.decode(cursor)
        val filters = listOfNotNull(
            candidateId?.let { "i.candidate_id = :candidateId" },
            companyId?.let { "i.company_id = :companyId" },
            after?.let { "(i.created_at, i.id) < (:afterAt, :afterId)" },
        ).joinToString(" AND ").ifBlank { "TRUE" }
        val params = buildMap<String, Any?> {
            candidateId?.let { put("candidateId", it) }
            companyId?.let { put("companyId", it) }
            after?.let { put("afterAt", it.at); put("afterId", it.id) }
            put("fetchLimit", size + 1)
        }
        val rows = store.rows(
            """SELECT i.id, i.created_at FROM invitations i
               WHERE $filters ORDER BY i.created_at DESC, i.id DESC LIMIT :fetchLimit""".trimIndent(),
            params,
        )
        val selected = rows.take(size)
        val hasMore = rows.size > size
        val last = selected.lastOrNull()
        return mapOf(
            "items" to selected.map { invitation(it.getValue("id").toString())!! },
            "page" to mapOf(
                "next_cursor" to if (hasMore && last != null) PageCursor(last.getValue("created_at").asInstant()!!, last.getValue("id").toString()).encode() else null,
                "limit" to size,
            ),
        )
    }

    private fun activeNeed(companyId: String, needId: String): Pair<Map<String, Any?>, Map<String, Any?>> {
        val row = store.one(
            "SELECT id, company_id, status, data FROM employer_needs WHERE id = :id AND company_id = :companyId",
            mapOf("id" to needId, "companyId" to companyId),
        ) ?: throw ApiException(404, "NOT_FOUND", "Active need not found")
        if (row["status"]?.toString() != "active") throw ApiException(409, "INVALID_STATE", "Need is not active")
        return row to store.objectValue(row["data"])
    }

    private fun requireCompanyOwner(userId: String, companyId: String) {
        val owner = store.one(
            "SELECT 1 AS allowed FROM memberships WHERE user_id = :userId AND company_id = :companyId AND role = 'owner' AND active = true",
            mapOf("userId" to userId, "companyId" to companyId),
        )
        if (owner == null) throw ApiException(403, "FORBIDDEN", "Company owner access required")
    }

    private fun ensureContactMethodEnabled(method: String, contacts: Any?) {
        if (method == "platform") return
        val enabledTypes = ContactRules.grantedChannels(store.listValue(store.objectValue(contacts)["channels"]))
            .map { it.getValue("type") }.toSet()
        if (method !in enabledTypes) {
            throw ApiException(400, "VALIDATION_ERROR", "The selected contact method is not enabled by the candidate", "contact_method")
        }
    }

    private fun validateExpectedVersion(body: Map<String, Any?>) {
        val version = body["expected_version"] as? Number
        if (body.keys != setOf("expected_version") || version == null || version.toInt() < 1 || version.toDouble() != version.toInt().toDouble()) {
            throw ApiException(400, "VALIDATION_ERROR", "expected_version must be a positive integer", "expected_version")
        }
    }

    private fun systemReasons(candidate: Map<String, Any?>, need: Map<String, Any?>?): List<Map<String, Any?>> {
        if (need == null) return emptyList()
        val result = mutableListOf<Map<String, Any?>>()
        val target = need["target_grade"]?.toString()
        val verified = candidate["verified_grade"]?.toString()
        val declared = candidate["declared_grade"]?.toString()
        if (target != null && target == verified) {
            val qualification = candidate["qualification"] as? Map<*, *>
            result += reason(
                "category", null, "test_verified", "assessment", qualification?.get("demo") == true,
                "Уровень ${gradeLabel(target)} подтверждён результатом оценки",
            )
        } else if (target != null && target == declared) {
            result += reason(
                "category", null, "candidate_declared", "profile", false,
                "Уровень ${gradeLabel(target)} указан кандидатом в профиле",
            )
        }
        val evidence = store.listValue(candidate["competency_evidence"])
        val evidenceBySkill = evidence.associateBy { it["skill_id"]?.toString() }
        val requirements = store.listValue(need["requirements"])
        requirements.forEach { required ->
            val skill = required["skill_id"]?.toString() ?: return@forEach
            val proof = evidenceBySkill[skill] ?: return@forEach
            val status = proof["status"]?.toString() ?: return@forEach
            if (status != "test_verified" && status != "candidate_declared") return@forEach
            val source = if (status == "test_verified") "assessment" else "profile"
            val qualification = candidate["qualification"] as? Map<*, *>
            val skillLabel = skillLabel(skill)
            val text = when (status) {
                "test_verified" -> "Навык «$skillLabel» подтверждён результатом оценки"
                else -> "Навык «$skillLabel» указан кандидатом в профиле"
            }
            result += reason("competency", skill, status, source, status == "test_verified" && qualification?.get("demo") == true, text)
        }
        store.listValue(candidate["fsp_evidence"]).forEach { proof ->
            if (proof["source"]?.toString() != "fsp_mock" || proof["demo"] != true || proof["validity"] != "valid") return@forEach
            if (proof["team_result"] == true && proof["membership_confirmed"] != true) return@forEach
            val title = proof["event_title"]?.toString() ?: return@forEach
            val details = proof["result"]?.toString().orEmpty()
            val kind = if (proof["team_result"] == true) {
                "Командный результат ФСП (демонстрационные данные, участие кандидата подтверждено)"
            } else {
                "Запись ФСП (демонстрационные данные)"
            }
            result += reason("fsp", null, "demo_fsp", "fsp_mock", true, "$kind: $title${if (details.isBlank()) "" else " — $details"}")
        }
        return result.take(20)
    }

    private fun gradeLabel(grade: String): String = when (grade) {
        "junior" -> "Junior"
        "middle" -> "Middle"
        else -> grade
    }

    private fun skillLabel(skillId: String): String = when (skillId) {
        "python-basics" -> "Основы Python"
        "http-api" -> "HTTP и API"
        "sql-transactions" -> "SQL и транзакции"
        "reliability" -> "Надёжность"
        "postgres" -> "PostgreSQL"
        "docker" -> "Docker"
        "fastapi" -> "FastAPI"
        else -> skillId
    }

    private fun reason(kind: String, skill: String?, status: String, source: String, demo: Boolean, text: String) = mapOf(
        "kind" to kind, "skill_id" to skill, "status" to status, "source" to source, "demo" to demo, "text" to text.take(300),
    )

    private fun snapshot(
        salary: Map<String, Any?>,
        description: String,
        contactMethod: String,
        reasons: List<Map<String, Any?>>,
        comment: Any?,
        topics: List<String>,
        companyName: String,
        companyDetails: Map<String, Any?>,
        hiringPlan: Any?,
    ): Map<String, Any?> = mapOf(
        "salary" to salary,
        "description" to description,
        "contact_method" to contactMethod,
        "system_reasons" to reasons,
        "company_comment" to comment,
        "discussion_topics" to topics,
        "company_name" to companyName,
        "company_industry" to companyDetails["industry"],
        "company_contact_email" to companyDetails["contact_email"],
        "company_contact_phone" to companyDetails["contact_phone"],
        "hiring_plan" to hiringPlan,
    )

    private fun validatedHiringPlan(value: Any?): Map<String, Any?>? {
        if (value == null) return null
        val plan = value as? Map<*, *> ?: throw ApiException(400, "VALIDATION_ERROR", "Invalid hiring plan", "hiring_plan")
        val stages = plan["stages"] as? List<*> ?: throw ApiException(400, "VALIDATION_ERROR", "Hiring plan stages are required", "hiring_plan.stages")
        val normalizedStages = stages.mapIndexed { index, item ->
            val stage = item as? Map<*, *> ?: throw ApiException(400, "VALIDATION_ERROR", "Invalid hiring plan stage", "hiring_plan.stages[$index]")
            val title = stage["title"]?.toString()?.trim().orEmpty()
            val criteria = stage["criteria"]?.toString()?.trim().orEmpty()
            if (title.isEmpty()) throw ApiException(400, "VALIDATION_ERROR", "Stage title cannot be blank", "hiring_plan.stages[$index].title")
            if (criteria.isEmpty()) throw ApiException(400, "VALIDATION_ERROR", "Stage criteria cannot be blank", "hiring_plan.stages[$index].criteria")
            mapOf(
                "title" to title,
                "effort_minutes" to stage["effort_minutes"],
                "criteria" to criteria,
            )
        }
        return mapOf(
            "stages" to normalizedStages,
            "response_time_days" to plan["response_time_days"],
        )
    }

    private fun stringList(value: Any?): List<String> = (value as? List<*>)?.map { it.toString() }.orEmpty()

    private fun normalizeNullableComment(body: Map<String, Any?>): Map<String, Any?> =
        if (body.containsKey("company_comment") && body["company_comment"] == null) body + ("company_comment" to "") else body

    private fun ensurePending(row: Map<String, Any?>, expectedVersion: Int) {
        val expiresAt = row["expires_at"].asInstant()
        if (row["state"]?.toString() == "pending" && expiresAt != null && !expiresAt.isAfter(store.now())) {
            throw ApiException(409, "INVALID_STATE", "Invitation has expired")
        }
        InvitationRules.ensurePairCanTransition(
            row["state"].toString(), expectedVersion, (row["version"] as Number).toInt(),
        )
    }

    private fun lockInvitation(id: String, candidateId: String? = null, companyId: String? = null): Map<String, Any?> =
        store.one(
            """SELECT * FROM invitations WHERE id = :id
               ${candidateId?.let { "AND candidate_id = :candidateId" }.orEmpty()}
               ${companyId?.let { "AND company_id = :companyId" }.orEmpty()} FOR UPDATE""".trimIndent(),
            buildMap {
                put("id", id)
                candidateId?.let { put("candidateId", it) }
                companyId?.let { put("companyId", it) }
            },
        ) ?: throw ApiException(404, "NOT_FOUND", "Invitation not found")

    private fun expirePending(id: String? = null) {
        val params: Map<String, Any?> = if (id == null) emptyMap() else mapOf("id" to id)
        val whereId = if (id == null) "" else "AND id = :id"
        expireWhere("WHERE state = 'pending' AND expires_at <= now() $whereId", params)
    }

    private fun expirePair(companyId: String, candidateId: String) {
        expireWhere(
            "WHERE state = 'pending' AND expires_at <= now() AND company_id = :companyId AND candidate_id = :candidateId",
            mapOf("companyId" to companyId, "candidateId" to candidateId),
        )
    }

    private fun expireWhere(where: String, params: Map<String, Any?>) {
        store.tx.executeWithoutResult {
            val expired = store.rows(
                "UPDATE invitations SET state = 'expired', updated_at = now() $where RETURNING id, version",
                params,
            )
            expired.forEach { row ->
                store.exec(
                    "UPDATE invitation_versions SET state = 'expired' WHERE invitation_id = :id AND version = :version",
                    mapOf("id" to row["id"], "version" to row["version"]),
                )
            }
        }
    }

    private fun invitation(id: String): Map<String, Any?>? {
        val row = store.one("SELECT * FROM invitations WHERE id = :id", mapOf("id" to id)) ?: return null
        val snapshot = store.objectValue(row["snapshot"])
        val state = row["state"].toString()
        return mapOf(
            "id" to row["id"].toString(),
            "version" to (row["version"] as Number).toInt(),
            "company_id" to row["company_id"].toString(),
            "candidate_id" to row["candidate_id"].toString(),
            "need_id" to row["need_id"]?.toString(),
            "state" to state,
            "salary" to snapshot["salary"],
            "description" to snapshot["description"],
            "contact_method" to snapshot["contact_method"],
            "system_reasons" to (snapshot["system_reasons"] ?: emptyList<Any>()),
            "company_comment" to snapshot["company_comment"],
            "discussion_topics" to (snapshot["discussion_topics"] ?: emptyList<Any>()),
            "candidate_response_version" to if (state == "accepted" || state == "declined") (row["version"] as Number).toInt() else null,
            "contact_grant_id" to row["contact_grant_id"]?.toString(),
            "expires_at" to row["expires_at"].asInstant().toString(),
            "updated_at" to row["updated_at"].asInstant().toString(),
            "company_name" to snapshot["company_name"],
            "company_industry" to snapshot["company_industry"],
            "company_contact_email" to snapshot["company_contact_email"],
            "company_contact_phone" to snapshot["company_contact_phone"],
            "hiring_plan" to snapshot["hiring_plan"],
        )
    }
}
