package ru.fsp.talent.matching

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.postgresql.util.PGobject
import org.springframework.stereotype.Component
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.Store
import java.util.Base64

@Component
class ShortlistService(
    private val store: Store,
    private val contracts: Contracts,
    private val needs: NeedService,
    private val matching: MatchingService,
) {
    fun create(actor: Actor, body: Map<String, Any?>): Map<String, Any?> {
        needs.requireOwner(actor)
        contracts.validate("ShortlistCreateRequest", body)
        val candidateIds = candidateIds(body["candidate_ids"])
        requireVisible(candidateIds)
        val needId = body["need_id"]?.toString()
        val needSnapshot = needId?.let { needs.get(actor, it) }
        val data = linkedMapOf<String, Any?>(
            "name" to body["name"],
            "need_id" to needId,
            "query_snapshot" to body["query_snapshot"],
            "candidate_ids" to candidateIds,
            "need_snapshot" to needSnapshot,
            "updated_at" to store.now().toString(),
        )
        val id = store.id()
        store.exec(
            "INSERT INTO shortlists(id, company_id, version, data) VALUES (:id, :companyId, 1, :data)",
            mapOf("id" to id, "companyId" to actor.companyId!!, "data" to store.json(data)),
        )
        return shortlistDto(getRow(id, actor.companyId!!))
    }

    fun list(actor: Actor, limit: Int, cursor: String?): Map<String, Any?> {
        needs.requireOwner(actor)
        val offset = decodeOffset(cursor)
        val rows = store.rows(
            """SELECT * FROM shortlists WHERE company_id = :companyId
               ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset""".trimIndent(),
            mapOf("companyId" to actor.companyId!!, "limit" to limit + 1, "offset" to offset),
        )
        val page = rows.take(limit)
        return mapOf(
            "items" to page.map(::shortlistDto),
            "page" to mapOf("next_cursor" to if (rows.size > limit) encodeOffset(offset + limit) else null, "limit" to limit),
        )
    }

    fun get(actor: Actor, id: String): Map<String, Any?> {
        needs.requireOwner(actor)
        return shortlistDto(getRow(id, actor.companyId!!))
    }

    fun patch(actor: Actor, id: String, body: Map<String, Any?>): Map<String, Any?> {
        needs.requireOwner(actor)
        contracts.validate("ShortlistPatchRequest", body)
        val row = getRowForUpdate(id, actor.companyId!!)
        val expectedVersion = (body["expected_version"] as? Number)?.toInt()
            ?: throw ApiException(400, "VALIDATION_ERROR", "expected_version is required", "expected_version")
        val currentVersion = (row["version"] as? Number)?.toInt() ?: 0
        if (currentVersion != expectedVersion) throw ApiException(409, "VERSION_CONFLICT", "Shortlist has changed", currentVersion = currentVersion)
        val data = jsonMap(row["data"]).toMutableMap()
        body["name"]?.let { data["name"] = it }
        if (body.containsKey("candidate_ids")) {
            val ids = candidateIds(body["candidate_ids"])
            requireVisible(ids)
            data["candidate_ids"] = ids
        }
        data["updated_at"] = store.now().toString()
        val updated = store.exec(
            "UPDATE shortlists SET data = :data, version = version + 1 WHERE id = :id AND company_id = :companyId AND version = :version",
            mapOf("data" to store.json(data), "id" to id, "companyId" to actor.companyId, "version" to expectedVersion),
        )
        if (updated == 0) {
            val current = (getRow(id, actor.companyId!!)["version"] as? Number)?.toInt() ?: 0
            throw ApiException(409, "VERSION_CONFLICT", "Shortlist has changed", currentVersion = current)
        }
        return shortlistDto(getRow(id, actor.companyId!!))
    }

    private fun shortlistDto(row: Map<String, Any?>): Map<String, Any?> {
        val data = jsonMap(row["data"])
        val savedIds = candidateIds(data["candidate_ids"])
        val currentlyVisible = matching.visibleCandidateIds(savedIds)
        return linkedMapOf(
            "id" to row["id"].toString(),
            "version" to row["version"],
            "company_id" to row["company_id"].toString(),
            "name" to data["name"],
            "need_id" to data["need_id"],
            "query_snapshot" to data["query_snapshot"],
            "candidate_ids" to savedIds.filter(currentlyVisible::contains),
            "updated_at" to (data["updated_at"]?.toString() ?: row["created_at"]?.toString()),
        )
    }

    private fun candidateIds(value: Any?): List<String> {
        val ids = (value as? Iterable<*>)?.mapNotNull { it?.toString() }.orEmpty()
        if (ids.size > 500 || ids.size != ids.toSet().size) {
            throw ApiException(400, "VALIDATION_ERROR", "Candidate pins must be unique and contain at most 500 IDs", "candidate_ids")
        }
        return ids
    }

    private fun requireVisible(ids: List<String>) {
        if (matching.visibleCandidateIds(ids).size != ids.size) throw ApiException(404, "NOT_FOUND", "Candidate not found")
    }

    private fun getRow(id: String, companyId: String): Map<String, Any?> = store.one(
        "SELECT * FROM shortlists WHERE id = :id AND company_id = :companyId",
        mapOf("id" to id, "companyId" to companyId),
    ) ?: throw ApiException(404, "NOT_FOUND", "Shortlist not found")

    private fun getRowForUpdate(id: String, companyId: String): Map<String, Any?> = store.one(
        "SELECT * FROM shortlists WHERE id = :id AND company_id = :companyId FOR UPDATE",
        mapOf("id" to id, "companyId" to companyId),
    ) ?: throw ApiException(404, "NOT_FOUND", "Shortlist not found")

    private fun decodeOffset(cursor: String?): Int {
        if (cursor == null) return 0
        return runCatching { String(Base64.getUrlDecoder().decode(cursor)).toInt() }.getOrNull()
            ?.takeIf { it >= 0 } ?: throw ApiException(400, "VALIDATION_ERROR", "Invalid page cursor", "cursor")
    }

    private fun encodeOffset(offset: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(offset.toString().toByteArray())

    companion object {
        private val JSON = ObjectMapper()
        private fun jsonMap(value: Any?): Map<String, Any?> = when (value) {
            is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
            is PGobject -> parseJson(value.value)
            is String -> parseJson(value)
            else -> emptyMap()
        }
        private fun parseJson(value: String?): Map<String, Any?> = value?.let {
            runCatching { JSON.readValue(it, object : TypeReference<Map<String, Any?>>() {}) }.getOrDefault(emptyMap())
        }.orEmpty()
    }
}
