package ru.fsp.talent.matching

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.postgresql.util.PGobject
import org.springframework.stereotype.Component
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.Store
import java.time.Instant
import java.util.Base64

@Component
class NeedService(private val store: Store, private val contracts: Contracts) {
    fun create(actor: Actor, body: Map<String, Any?>): Map<String, Any?> {
        requireOwner(actor)
        contracts.validate("NeedCreateRequest", body)
        validateNeed(body)
        val companyId = actor.companyId!!
        val id = store.id()
        val data = body + mapOf(
            "updated_at" to store.now().toString(),
            "role_tasks" to (body["role_tasks"] ?: emptyList<Any>()),
        )
        store.exec(
            """INSERT INTO employer_needs(id, company_id, version, status, data)
               VALUES (:id, :companyId, 1, 'draft', :data)""".trimIndent(),
            mapOf("id" to id, "companyId" to companyId, "data" to store.json(data)),
        )
        return getNeed(id, companyId).let(::needDto)
    }

    fun list(actor: Actor, limit: Int, cursor: String?): Map<String, Any?> {
        requireOwner(actor)
        val companyId = actor.companyId!!
        val offset = decodeOffset(cursor)
        val rows = store.rows(
            """SELECT * FROM employer_needs WHERE company_id = :companyId
               ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset""".trimIndent(),
            mapOf("companyId" to companyId, "limit" to (limit + 1), "offset" to offset),
        )
        val page = rows.take(limit)
        val next = if (rows.size > limit) encodeOffset(offset + limit) else null
        return mapOf("items" to page.map(::needDto), "page" to mapOf("next_cursor" to next, "limit" to limit))
    }

    fun get(actor: Actor, id: String): Map<String, Any?> {
        requireOwner(actor)
        return needDto(getNeed(id, actor.companyId!!))
    }

    fun patch(actor: Actor, id: String, request: Map<String, Any?>): Map<String, Any?> {
        requireOwner(actor)
        contracts.validate("NeedPatchRequest", request)
        val companyId = actor.companyId!!
        val expectedVersion = request["expected_version"].asInt()
            ?: throw ApiException(400, "VALIDATION_ERROR", "expected_version is required", "expected_version")
        val row = getNeedForUpdate(id, companyId)
        checkVersion(row, expectedVersion)
        val data = jsonMap(row["data"]).toMutableMap()
        data.putIfAbsent("role_tasks", emptyList<Any>())
        request.filterKeys { it != "expected_version" && it != "status" }.forEach { (key, value) -> data[key] = value }
        validateNeed(data)
        val statusPatch = request["status"]?.toString()
        val changedCompetencies = request.keys.any { it in setOf("target_grade", "specialization_id", "requirements", "role_tasks") }
        val status = when {
            statusPatch == "archived" -> "archived"
            statusPatch == "draft" || changedCompetencies -> "draft"
            row["status"] == "active" && request.keys.any { it != "expected_version" } -> "draft"
            else -> row["status"].toString()
        }
        val clearsConfirmation = status != "active"
        data["updated_at"] = store.now().toString()
        val updated = store.exec(
            """UPDATE employer_needs SET data = :data, status = :status, version = version + 1,
                      confirmed_at = CASE WHEN :clearConfirmation THEN NULL ELSE confirmed_at END,
                      confirmed_by = CASE WHEN :clearConfirmation THEN NULL ELSE confirmed_by END
               WHERE id = :id AND company_id = :companyId AND version = :version""".trimIndent(),
            mapOf("data" to store.json(data), "status" to status, "clearConfirmation" to clearsConfirmation,
                "id" to id, "companyId" to companyId, "version" to expectedVersion),
        )
        if (updated == 0) {
            val current = (getNeed(id, companyId)["version"] as? Number)?.toInt() ?: 0
            throw ApiException(409, "VERSION_CONFLICT", "Need has changed", currentVersion = current)
        }
        return needDto(getNeed(id, companyId))
    }

    fun confirm(actor: Actor, id: String, request: Map<String, Any?>): Map<String, Any?> {
        requireOwner(actor)
        contracts.validate("ConfirmCompetenciesRequest", request)
        val companyId = actor.companyId!!
        val expectedVersion = request["expected_version"].asInt()!!
        val row = getNeedForUpdate(id, companyId)
        checkVersion(row, expectedVersion)
        if (row["status"] != "draft") throw ApiException(409, "INVALID_STATE", "Only draft needs can be confirmed")
        val data = jsonMap(row["data"]).toMutableMap()
        val requirements = listMap(data["requirements"])
        val requiredIds = requirements.mapNotNull { it["skill_id"]?.toString() }.toSet()
        val confirmedList = (request["confirmed_skill_ids"] as? Iterable<*>)?.mapNotNull { it?.toString() }.orEmpty()
        val confirmedIds = confirmedList.toSet()
        if (confirmedIds != requiredIds || confirmedList.size != confirmedIds.size) {
            throw ApiException(422, "POLICY_BLOCKED", "Confirm exactly the skills declared in the need", "confirmed_skill_ids")
        }
        data["confirmed_skill_ids"] = requiredIds.sorted()
        data["updated_at"] = store.now().toString()
        val updated = store.exec(
            """UPDATE employer_needs SET data = :data, status = 'active', version = version + 1,
                      confirmed_at = now(), confirmed_by = :actorId
               WHERE id = :id AND company_id = :companyId AND version = :version""".trimIndent(),
            mapOf("data" to store.json(data), "actorId" to actor.id, "id" to id,
                "companyId" to companyId, "version" to expectedVersion),
        )
        if (updated == 0) {
            val current = (getNeed(id, companyId)["version"] as? Number)?.toInt() ?: 0
            throw ApiException(409, "VERSION_CONFLICT", "Need has changed", currentVersion = current)
        }
        return needDto(getNeed(id, companyId))
    }

    fun getNeedForSearch(actor: Actor, id: String): Map<String, Any?> {
        requireOwner(actor)
        val row = getNeed(id, actor.companyId!!)
        if (row["status"] != "active") throw ApiException(409, "INVALID_STATE", "Only active needs can be searched")
        return row
    }

    fun requireOwner(actor: Actor) {
        val companyId = actor.companyId ?: throw ApiException(403, "FORBIDDEN", "Company owner access required")
        val owner = store.one(
            "SELECT id FROM companies WHERE id = :companyId AND owner_id = :actorId",
            mapOf("companyId" to companyId, "actorId" to actor.id),
        )
        if (owner == null) throw ApiException(403, "FORBIDDEN", "Company owner access required")
    }

    private fun validateNeed(data: Map<String, Any?>) {
        val specialization = data["specialization_id"]?.toString()
        if (specialization != MatchingPolicy.SPECIALIZATION_ID) {
            throw ApiException(422, "POLICY_BLOCKED", "Only the verified Backend Python specialization is supported", "specialization_id")
        }
        val mode = data["work_mode"]?.toString()
        val city = data["city"]?.toString()?.trim().orEmpty()
        if (mode != "remote" && city.isEmpty()) {
            throw ApiException(400, "VALIDATION_ERROR", "City is required for hybrid or office work", "city")
        }
        val hours = data["workload_hours_per_week"].asInt()
        val salary = jsonMap(data["salary"])
        val minimum = salary["min_kopecks"].asLong()
        val maximum = salary["max_kopecks"].asLong()
        if (minimum == null || maximum == null || minimum <= 0 || maximum < minimum ||
            salary["currency"] != "RUB" || salary["period"] != "month" || salary["hours_per_week"].asInt() != hours
        ) {
            throw ApiException(400, "VALIDATION_ERROR", "Salary must be a positive monthly RUB range for the need workload", "salary")
        }
        val workloadType = salary["workload_type"]?.toString()
        if ((workloadType == "full_time" && hours != 40) ||
            (workloadType == "part_time" && (hours == null || hours !in 1..39))
        ) throw ApiException(400, "VALIDATION_ERROR", "Salary workload must match need hours", "salary.workload_type")

        val requirements = listMap(data["requirements"])
        val skillIds = requirements.mapNotNull { it["skill_id"]?.toString() }
        if (requirements.isEmpty() || skillIds.size != requirements.size || skillIds.size > 7 || skillIds.toSet().size != skillIds.size) {
            throw ApiException(400, "VALIDATION_ERROR", "Requirements must contain one to seven unique taxonomy skills", "requirements")
        }
        if (requirements.none { it["importance"] == "required" }) {
            throw ApiException(400, "VALIDATION_ERROR", "At least one required skill is needed", "requirements")
        }
        val rawRoleTasks = data["role_tasks"] ?: emptyList<Any>()
        val roleTasks = listMap(rawRoleTasks)
        if (roleTasks.size > 10 || roleTasks.size != (rawRoleTasks as? Iterable<*>)?.count()) {
            throw ApiException(400, "VALIDATION_ERROR", "Role tasks must contain at most ten valid entries", "role_tasks")
        }
        val requirementIds = skillIds.toSet()
        val taskIds = roleTasks.mapNotNull { it["id"]?.toString() }
        if (taskIds.size != roleTasks.size || taskIds.toSet().size != taskIds.size) {
            throw ApiException(400, "VALIDATION_ERROR", "Role task ids must be unique", "role_tasks")
        }
        roleTasks.forEachIndexed { index, task ->
            val taskSkills = (task["skill_ids"] as? Iterable<*>)?.mapNotNull { it?.toString() }.orEmpty()
            if (taskSkills.isEmpty() || taskSkills.size > 7 || taskSkills.toSet().size != taskSkills.size ||
                taskSkills.any { it !in requirementIds }
            ) {
                throw ApiException(400, "VALIDATION_ERROR", "Each role task must link unique skills declared in requirements", "role_tasks[$index].skill_ids")
            }
        }
    }

    private fun getNeed(id: String, companyId: String): Map<String, Any?> = store.one(
        "SELECT * FROM employer_needs WHERE id = :id AND company_id = :companyId",
        mapOf("id" to id, "companyId" to companyId),
    ) ?: throw ApiException(404, "NOT_FOUND", "Need not found")

    private fun getNeedForUpdate(id: String, companyId: String): Map<String, Any?> = store.one(
        "SELECT * FROM employer_needs WHERE id = :id AND company_id = :companyId FOR UPDATE",
        mapOf("id" to id, "companyId" to companyId),
    ) ?: throw ApiException(404, "NOT_FOUND", "Need not found")

    private fun checkVersion(row: Map<String, Any?>, expected: Int) {
        val current = row["version"].asInt() ?: 0
        if (expected != current) throw ApiException(409, "VERSION_CONFLICT", "Need has changed", currentVersion = current)
    }

    private fun decodeOffset(cursor: String?): Int {
        if (cursor == null) return 0
        val value = runCatching { String(Base64.getUrlDecoder().decode(cursor)) }.getOrNull()?.toIntOrNull()
        return value?.takeIf { it >= 0 } ?: throw ApiException(400, "VALIDATION_ERROR", "Invalid page cursor", "cursor")
    }

    private fun encodeOffset(offset: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(offset.toString().toByteArray())

    companion object {
        fun needDto(row: Map<String, Any?>): Map<String, Any?> {
            val data = jsonMap(row["data"])
            return linkedMapOf(
                "id" to row["id"].toString(),
                "version" to row["version"],
                "company_id" to row["company_id"].toString(),
                "title" to data["title"],
                "description" to data["description"],
                "specialization_id" to data["specialization_id"],
                "target_grade" to data["target_grade"],
                "work_mode" to data["work_mode"],
                "city" to data["city"],
                "workload_hours_per_week" to data["workload_hours_per_week"],
                "salary" to data["salary"],
                "requirements" to data["requirements"],
                "role_tasks" to (data["role_tasks"] ?: emptyList<Any>()),
                "competencies_confirmed_at" to row["confirmed_at"]?.instantString(),
                "competencies_confirmed_by" to row["confirmed_by"]?.toString(),
                "status" to row["status"],
                "updated_at" to (data["updated_at"]?.toString() ?: row["created_at"]?.instantString()),
            )
        }

        private fun jsonMap(value: Any?): Map<String, Any?> = when (value) {
            is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
            is PGobject -> parseJson(value.value)
            is String -> parseJson(value)
            else -> emptyMap()
        }

        private fun parseJson(value: String?): Map<String, Any?> = value?.let {
            runCatching { JSON.readValue(it, object : TypeReference<Map<String, Any?>>() {}) }
                .getOrDefault(emptyMap())
        }.orEmpty()

        private val JSON = ObjectMapper()

        private fun Any?.instantString(): String? = when (this) {
            is Instant -> toString()
            null -> null
            else -> toString()
        }
    }
}

private fun Any?.asInt(): Int? = (this as? Number)?.toInt()
private fun Any?.asLong(): Long? = (this as? Number)?.toLong()
private fun listMap(value: Any?): List<Map<String, Any?>> = (value as? Iterable<*>)
    ?.mapNotNull { item -> (item as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } }
    .orEmpty()
