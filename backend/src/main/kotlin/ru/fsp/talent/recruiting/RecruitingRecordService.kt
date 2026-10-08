package ru.fsp.talent.recruiting

import org.springframework.stereotype.Component
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.Store
import ru.fsp.talent.invitations.PageCursor
import ru.fsp.talent.invitations.checkPageLimit
import ru.fsp.talent.invitations.requireUuid
import ru.fsp.talent.consents.ConsentService
import ru.fsp.talent.privacy.PublicTextRedactor

@Component
class RecruitingRecordService(
    private val store: Store,
    private val contracts: Contracts,
    private val consents: ConsentService,
) {
    fun upsert(actor: Actor, candidateIdInput: String, body: Map<String, Any?>): Map<String, Any?> {
        val companyId = requireCompany(actor)
        contracts.validate("RecruitingRecordPutRequest", body)
        val candidateId = requireUuid(candidateIdInput, "candidate_id")
        val expectedVersion = (body["expected_version"] as? Number)?.toInt()
            ?: throw ApiException(400, "VALIDATION_ERROR", "expected_version is required", "expected_version")
        val stage = body["stage"]?.toString()
            ?: throw ApiException(400, "VALIDATION_ERROR", "stage is required", "stage")
        val note = body["note"] as? String
        val needId = body["need_id"]?.toString()?.let { requireUuid(it, "need_id") }

        store.one(
            "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            mapOf("lockKey" to "$companyId:$candidateId"),
        )
        val existing = store.one(
            "SELECT * FROM recruiting_records WHERE company_id = :companyId AND candidate_id = :candidateId FOR UPDATE",
            mapOf("companyId" to companyId, "candidateId" to candidateId),
        )
        if (existing == null) {
            if (expectedVersion != 0) throw versionConflict(0)
            requireCurrentlyPublic(candidateId)
        } else {
            val current = (existing["version"] as? Number)?.toInt() ?: 0
            if (expectedVersion != current) throw versionConflict(current)
        }

        if (needId != null) requireOwnNeed(companyId, needId)

        val now = store.now()
        val id = existing?.get("id")?.toString() ?: store.id()
        if (existing == null) {
            store.exec(
                """INSERT INTO recruiting_records(id, company_id, candidate_id, need_id, version, stage, note, updated_at)
                   VALUES (:id, :companyId, :candidateId, :needId, 1, :stage, :note, :updatedAt)""".trimIndent(),
                mapOf("id" to id, "companyId" to companyId, "candidateId" to candidateId, "needId" to needId,
                    "stage" to stage, "note" to note, "updatedAt" to now),
            )
        } else {
            store.exec(
                """UPDATE recruiting_records SET need_id = :needId, stage = :stage, note = :note,
                          version = version + 1, updated_at = :updatedAt
                   WHERE id = :id AND company_id = :companyId AND candidate_id = :candidateId AND version = :version""".trimIndent(),
                mapOf("needId" to needId, "stage" to stage, "note" to note, "updatedAt" to now,
                    "id" to id, "companyId" to companyId, "candidateId" to candidateId, "version" to expectedVersion),
            )
        }
        return recordDto(getRecord(companyId, candidateId))
    }

    fun list(
        actor: Actor,
        limitInput: Int,
        cursorInput: String?,
        needIdInput: String?,
        candidateIdInput: String?,
    ): Map<String, Any?> {
        val companyId = requireCompany(actor)
        val limit = checkPageLimit(limitInput)
        if ((cursorInput?.length ?: 0) > 512) throw ApiException(400, "VALIDATION_ERROR", "Invalid cursor", "cursor")
        val cursor = PageCursor.decode(cursorInput)
        val needId = needIdInput?.let { requireUuid(it, "need_id") }
        val candidateId = candidateIdInput?.let { requireUuid(it, "candidate_id") }
        val clauses = mutableListOf("rr.company_id = :companyId")
        val params = mutableMapOf<String, Any?>("companyId" to companyId, "limit" to limit + 1)
        if (needId != null) {
            clauses += "rr.need_id = :needId"
            params["needId"] = needId
        }
        if (candidateId != null) {
            clauses += "rr.candidate_id = :candidateId"
            params["candidateId"] = candidateId
        }
        if (cursor != null) {
            clauses += "(rr.updated_at, rr.id) < (CAST(:cursorAt AS timestamptz), CAST(:cursorId AS uuid))"
            params["cursorAt"] = cursor.at
            params["cursorId"] = cursor.id
        }
        val rows = store.rows(
            """SELECT rr.*,
                      CASE WHEN cp.visibility = 'public' AND u.email_verified_at IS NOT NULL AND u.deleted_at IS NULL ${consents.publicationGateSql()}
                           THEN 'public' ELSE 'hidden' END AS candidate_visibility,
                      CASE WHEN cp.visibility = 'public' AND u.email_verified_at IS NOT NULL AND u.deleted_at IS NULL ${consents.publicationGateSql()}
                           THEN cp.data ->> 'display_name' ELSE NULL END AS candidate_display_name,
                      CASE WHEN own_need.id IS NOT NULL THEN own_need.data ->> 'title' ELSE NULL END AS need_title
               FROM recruiting_records rr
               JOIN candidate_profiles cp ON cp.user_id = rr.candidate_id
               JOIN users u ON u.id = rr.candidate_id
               LEFT JOIN employer_needs own_need ON own_need.id = rr.need_id AND own_need.company_id = rr.company_id
               WHERE ${clauses.joinToString(" AND ")}
               ORDER BY rr.updated_at DESC, rr.id DESC LIMIT :limit""".trimIndent(),
            params,
        )
        val page = rows.take(limit)
        val next = if (rows.size > limit) page.lastOrNull()?.let { row ->
            val at = row["updated_at"] as? java.time.Instant
                ?: throw IllegalStateException("Recruiting record updated_at was not decoded as an Instant")
            PageCursor(at, row["id"].toString()).encode()
        } else null
        return mapOf("items" to page.map(::recordDto), "next_cursor" to next)
    }

    private fun recordDto(row: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "id" to row["id"].toString(),
        "candidate_id" to row["candidate_id"].toString(),
        "company_id" to row["company_id"].toString(),
        "need_id" to row["need_id"]?.toString(),
        "version" to row["version"],
        "stage" to row["stage"],
        "note" to row["note"],
        "updated_at" to (row["updated_at"] as? java.time.Instant)?.toString(),
        "candidate_visibility" to row["candidate_visibility"],
        "candidate_display_name" to PublicTextRedactor.redact(row["candidate_display_name"] as? String, 120),
        "need_title" to row["need_title"],
    )

    private fun getRecord(companyId: String, candidateId: String): Map<String, Any?> = store.one(
        """SELECT rr.*,
                  CASE WHEN cp.visibility = 'public' AND u.email_verified_at IS NOT NULL AND u.deleted_at IS NULL ${consents.publicationGateSql()}
                       THEN 'public' ELSE 'hidden' END AS candidate_visibility,
                  CASE WHEN cp.visibility = 'public' AND u.email_verified_at IS NOT NULL AND u.deleted_at IS NULL ${consents.publicationGateSql()}
                       THEN cp.data ->> 'display_name' ELSE NULL END AS candidate_display_name,
                  CASE WHEN own_need.id IS NOT NULL THEN own_need.data ->> 'title' ELSE NULL END AS need_title
           FROM recruiting_records rr
           JOIN candidate_profiles cp ON cp.user_id = rr.candidate_id
           JOIN users u ON u.id = rr.candidate_id
           LEFT JOIN employer_needs own_need ON own_need.id = rr.need_id AND own_need.company_id = rr.company_id
           WHERE rr.company_id = :companyId AND rr.candidate_id = :candidateId""".trimIndent(),
        mapOf("companyId" to companyId, "candidateId" to candidateId),
    ) ?: throw ApiException(404, "NOT_FOUND", "Recruiting record not found")

    private fun requireCurrentlyPublic(candidateId: String) {
        // Match consent/publication mutation order: user first, candidate profile second.
        store.one(
            "SELECT id FROM users WHERE id = :candidateId AND email_verified_at IS NOT NULL AND deleted_at IS NULL FOR SHARE",
            mapOf("candidateId" to candidateId),
        ) ?: throw ApiException(404, "NOT_FOUND", "Candidate not found")
        val visible = store.one(
            """SELECT cp.user_id FROM candidate_profiles cp JOIN users u ON u.id = cp.user_id
               WHERE cp.user_id = :candidateId AND cp.visibility = 'public'
                 AND u.email_verified_at IS NOT NULL AND u.deleted_at IS NULL
                 ${consents.publicationGateSql()}
               FOR SHARE OF cp""".trimIndent(),
            mapOf("candidateId" to candidateId),
        )
        if (visible == null) throw ApiException(404, "NOT_FOUND", "Candidate not found")
    }

    private fun requireOwnNeed(companyId: String, needId: String) {
        if (store.one(
                "SELECT id FROM employer_needs WHERE id = :needId AND company_id = :companyId",
                mapOf("needId" to needId, "companyId" to companyId),
            ) == null
        ) throw ApiException(404, "NOT_FOUND", "Need not found")
    }

    private fun requireCompany(actor: Actor): String {
        val companyId = actor.companyId ?: throw ApiException(403, "FORBIDDEN", "Company owner access required")
        if (store.one(
                "SELECT id FROM companies WHERE id = :companyId AND owner_id = :actorId",
                mapOf("companyId" to companyId, "actorId" to actor.id),
            ) == null
        ) throw ApiException(403, "FORBIDDEN", "Company owner access required")
        return companyId
    }

    private fun versionConflict(currentVersion: Int) = ApiException(
        409, "VERSION_CONFLICT", "Recruiting record has changed", currentVersion = currentVersion,
    )
}
