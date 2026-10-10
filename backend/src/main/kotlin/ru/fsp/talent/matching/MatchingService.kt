package ru.fsp.talent.matching

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.postgresql.util.PGobject
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.Store
import ru.fsp.talent.consents.ConsentService
import ru.fsp.talent.privacy.PublicTextRedactor
import ru.fsp.talent.profiles.CandidateProfileEntries

@Component
class MatchingService(
    private val store: Store,
    private val consents: ConsentService,
    @Value("\${fsp.demo-mode:true}") private val demoMode: Boolean,
    @Value("\${fsp.session-secret}") private val sessionSecret: String,
) {
    fun searchCandidateBank(
        companyId: String,
        filters: CandidateBankFilters,
        limit: Int,
        cursor: String?,
    ): Map<String, Any?> {
        val offset = decodeCandidateBankCursor(cursor, companyId, filters)
        val profiles = policyProfiles().asSequence()
            .filter { profile -> filters.specializationId == null || profile["specialization_id"] == filters.specializationId }
            .filter { profile ->
                val verifiedGrade = profile["verified_grade"]?.toString()
                val declaredGrade = profile["declared_grade"]?.toString()
                when (filters.categoryStatus) {
                    "confirmed" -> verifiedGrade != null
                    "unconfirmed" -> verifiedGrade == null && declaredGrade != null
                    else -> true
                } && when {
                    filters.grade == null -> true
                    filters.categoryStatus == "unconfirmed" -> verifiedGrade == null && declaredGrade == filters.grade
                    else -> verifiedGrade == filters.grade
                }
            }
            .filter { profile ->
                filters.skillIds.isEmpty() || filters.skillIds.any { skillId ->
                    skillId in profile["test_evidenced_skills"].asStrings() ||
                        skillId in profile["candidate_declared_skills"].asStrings()
                }
            }
            .filter { profile ->
                val hasEvidence = MatchingPolicy.fspEvidenceOrdinal(profile["fsp_results"]) > 0
                when (filters.fspStatus) {
                    "has_evidence" -> hasEvidence
                    "no_evidence" -> !hasEvidence
                    else -> true
                }
            }
            .map { profile ->
                val testSkillMatches = filters.skillIds.count { it in profile["test_evidenced_skills"].asStrings() }
                val categoryKey = candidateBankCategoryKey(profile)
                CandidateBankEntry(
                    profile = profile,
                    id = profile["id"].toString(),
                    categoryKey = categoryKey,
                    testSkillMatches = testSkillMatches,
                    fspOrdinal = MatchingPolicy.fspEvidenceOrdinal(profile["fsp_results"]),
                )
            }
            .sortedWith(
                compareBy<CandidateBankEntry> { it.categoryKey.first }
                    .thenBy { it.categoryKey.second }
                    .thenByDescending { it.testSkillMatches }
                    .thenComparator { left, right ->
                        if (left.categoryKey == right.categoryKey) right.fspOrdinal.compareTo(left.fspOrdinal) else 0
                    }
                    .thenBy { it.id },
            )
            .toList()

        val page = profiles.drop(offset).take(limit)
        return mapOf(
            "items" to page.map { entry ->
                publicCandidateById(entry.id) + ("category_evidence" to MatchingPolicy.categoryEvidence(entry.profile))
            },
            "next_cursor" to if (offset + page.size < profiles.size) {
                encodeCandidateBankCursor(companyId, filters, offset + page.size)
            } else null,
            "filter_context" to filters.context(),
        )
    }

    /** Re-reads current public rights so callers cannot publish a stale profile row. */
    fun publicCandidate(profileRow: Map<String, Any?>): Map<String, Any?> {
        val id = (profileRow["user_id"] ?: profileRow["id"])?.toString()
            ?: throw ApiException(404, "NOT_FOUND", "Candidate not found")
        return publicCandidateById(id)
    }

    fun publicCandidateById(id: String): Map<String, Any?> {
        return publicCandidateFromSnapshot(candidateSnapshotById(id, requirePublic = true))
    }

    /** Candidate-only preview source: caller must pass its authenticated own user id. */
    fun previewOwnCandidate(candidateId: String): Map<String, Any?> {
        val row = candidateSnapshotById(candidateId, requirePublic = false)
        return mapOf(
            "visibility" to if (row["visibility"] == "public" && !consents.publicationAllowed(candidateId)) "hidden" else row["visibility"].toString(),
            "candidate" to publicCandidateFromSnapshot(row),
        )
    }

    /** Matching internals are exposed only for current public, verified candidates. */
    fun policyProfileById(id: String): Map<String, Any?>? = policyProfiles(listOf(id)).firstOrNull()

    /** Internal aggregate diagnostics reuse exactly the public search consent and visibility gates. */
    fun insightProfiles(): List<Map<String, Any?>> = policyProfiles()

    /** Reasons are derived by the same components as filtering and ordering. */
    fun candidateReasons(profileRow: Map<String, Any?>, needRow: Map<String, Any?>): List<String> {
        val need = if (needRow.containsKey("requirements")) needRow else NeedService.needDto(needRow)
        val id = (profileRow["user_id"] ?: profileRow["id"])?.toString() ?: return emptyList()
        val snapshot = policyProfiles(listOf(id)).firstOrNull() ?: return emptyList()
        val all = MatchingPolicy.computeMatches(listOf(snapshot), need)
        return (all.groups.confirmed + all.groups.needsClarification + all.groups.needsConfirmation)
            .firstOrNull { it.candidateId == id }?.rankReasons.orEmpty()
    }

    fun findMatchingProfiles(need: Map<String, Any?>): List<MatchCandidate> =
        MatchingPolicy.computeMatches(policyProfiles(), need).groups.let {
            it.confirmed + it.needsClarification + it.needsConfirmation
        }

    fun search(needRow: Map<String, Any?>, limit: Int, cursor: String?, strictConfirmedOnly: Boolean): Map<String, Any?> {
        val need = NeedService.needDto(needRow)
        val needId = need["id"].toString()
        val version = need["version"].toString()
        val offset = decodeSearchCursor(cursor, needId, version, strictConfirmedOnly)
        val all = MatchingPolicy.computeMatches(policyProfiles(), need)
        val ordered = if (strictConfirmedOnly) all.groups.confirmed else
            all.groups.confirmed + all.groups.needsClarification + all.groups.needsConfirmation
        val page = ordered.drop(offset).take(limit)
        fun serialize(candidate: MatchCandidate): Map<String, Any?> {
            val publicCandidate = runCatching { publicCandidateById(candidate.candidateId) }
                .getOrElse { error ->
                    if (error is ApiException && error.status == 404) return emptyMap()
                    throw error
                }
            return mapOf(
                "candidate" to publicCandidate,
                "state" to candidate.state.wireValue,
                "rank_reasons" to candidate.rankReasons,
                "test_verified_preferred_count" to candidate.testVerifiedPreferredCount,
                "candidate_declared_only_preferred_count" to candidate.candidateDeclaredOnlyPreferredCount,
            )
        }
        val serialized = page.map(::serialize).filter { it.isNotEmpty() }
        val byState = serialized.groupBy { it["state"] }
        val end = offset + page.size
        return mapOf(
            "need_id" to needId,
            "need_version" to need["version"],
            "strict_confirmed_only" to strictConfirmedOnly,
            "groups" to mapOf(
                "confirmed" to byState[MatchState.CONFIRMED.wireValue].orEmpty(),
                "needs_clarification" to byState[MatchState.NEEDS_CLARIFICATION.wireValue].orEmpty(),
                "needs_confirmation" to byState[MatchState.NEEDS_CONFIRMATION.wireValue].orEmpty(),
            ),
            "next_cursor" to if (end < ordered.size) encodeSearchCursor(needId, version, strictConfirmedOnly, end) else null,
            "display_order" to listOf("confirmed", "needs_clarification", "needs_confirmation"),
            "group_counts" to mapOf(
                "confirmed" to all.groupCounts.confirmed,
                "needs_clarification" to all.groupCounts.needsClarification,
                "needs_confirmation" to all.groupCounts.needsConfirmation,
            ),
        )
    }

    fun visibleCandidateIds(ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        return store.rows(
            """SELECT cp.user_id FROM candidate_profiles cp JOIN users u ON u.id = cp.user_id
               WHERE cp.user_id IN (:ids) AND cp.visibility = 'public'
                 AND u.email_verified_at IS NOT NULL AND u.deleted_at IS NULL
                 ${consents.publicationGateSql()}""".trimIndent(),
            mapOf("ids" to ids),
        ).mapNotNull { it["user_id"]?.toString() }.toSet()
    }

    private fun decodeSearchCursor(cursor: String?, needId: String, version: String, strict: Boolean): Int {
        if (cursor == null) return 0
        val parts = runCatching { String(Base64.getUrlDecoder().decode(cursor)).split('|') }.getOrNull()
        if (parts == null || parts.size != 4 || parts[0] != needId || parts[1] != version || parts[2] != strict.toString()) {
            throw ApiException(400, "VALIDATION_ERROR", "Invalid page cursor", "cursor")
        }
        return parts[3].toIntOrNull()?.takeIf { it >= 0 }
            ?: throw ApiException(400, "VALIDATION_ERROR", "Invalid page cursor", "cursor")
    }

    private fun encodeSearchCursor(needId: String, version: String, strict: Boolean, offset: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$needId|$version|$strict|$offset".toByteArray())

    private fun decodeCandidateBankCursor(cursor: String?, companyId: String, filters: CandidateBankFilters): Int {
        if (cursor == null) return 0
        val tokenParts = cursor.split('.')
        if (tokenParts.size != 2) throw invalidCandidateBankCursor()
        val payload = runCatching { Base64.getUrlDecoder().decode(tokenParts[0]) }.getOrNull()
            ?: throw invalidCandidateBankCursor()
        val signature = runCatching { Base64.getUrlDecoder().decode(tokenParts[1]) }.getOrNull()
            ?: throw invalidCandidateBankCursor()
        val expectedSignature = signCandidateBankCursor(payload)
        if (!MessageDigest.isEqual(signature, expectedSignature)) throw invalidCandidateBankCursor()
        val parts = String(payload, StandardCharsets.UTF_8).split('|')
        val expected = listOf("CB1", companyId) + filters.canonicalParts()
        if (parts.size != expected.size + 1 || parts.dropLast(1) != expected) throw invalidCandidateBankCursor()
        return parts.last().toIntOrNull()?.takeIf { it >= 0 }
            ?: throw invalidCandidateBankCursor()
    }

    private fun encodeCandidateBankCursor(companyId: String, filters: CandidateBankFilters, offset: Int): String {
        val payload = (listOf("CB1", companyId) + filters.canonicalParts() + offset.toString())
            .joinToString("|").toByteArray(StandardCharsets.UTF_8)
        val encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signCandidateBankCursor(payload))
        return "$encodedPayload.$signature"
    }

    private fun signCandidateBankCursor(payload: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(sessionSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        doFinal(payload)
    }

    private fun invalidCandidateBankCursor() = ApiException(400, "VALIDATION_ERROR", "Invalid page cursor", "cursor")

    private fun candidateBankCategoryKey(profile: Map<String, Any?>): Pair<Int, String> {
        val status = MatchingPolicy.categoryEvidence(profile)
        val rank = when (status) {
            "confirmed" -> 0
            "unconfirmed" -> 1
            else -> 2
        }
        val grade = if (status == "confirmed") profile["verified_grade"] else profile["declared_grade"]
        return rank to "${profile["specialization_id"] ?: ""}:${grade ?: ""}"
    }

    fun profileDto(userId: String): Map<String, Any?> = publicCandidateById(userId)

    private fun policyProfiles(onlyIds: List<String>? = null): List<Map<String, Any?>> {
        val params: Map<String, Any?> = if (onlyIds == null) emptyMap<String, Any?>() else mapOf("ids" to onlyIds)
        if (onlyIds != null && onlyIds.isEmpty()) return emptyList()
        val filter = if (onlyIds == null) "" else "AND cp.user_id IN (:ids)"
        val rows = store.rows(
            """SELECT cp.user_id, cp.visibility, cp.data AS profile_data,
                      (u.email_verified_at IS NOT NULL) AS email_confirmed,
                      q.grade AS verified_grade, q.specialization_id,
                      q.assessed_at, q.policy_version, q.bank_version, q.data AS qualification_data,
                      a.result AS assessment_result,
                      fl.binding_status, fl.source AS fsp_source, fl.retrieved_at AS fsp_retrieved_at,
                      fl.data AS fsp_link_data
               FROM candidate_profiles cp
               JOIN users u ON u.id = cp.user_id
               LEFT JOIN qualifications q ON q.user_id = cp.user_id AND q.specialization_id = '${MatchingPolicy.SPECIALIZATION_ID}'
               LEFT JOIN attempts a ON a.id = q.evidence_attempt_id AND a.status = 'evaluated'
               LEFT JOIN fsp_links fl ON fl.user_id = cp.user_id
               WHERE cp.visibility = 'public' AND u.email_verified_at IS NOT NULL AND u.deleted_at IS NULL
                 ${consents.publicationGateSql()}
                 $filter
               ORDER BY cp.user_id""".trimIndent(),
            params,
        )
        val ids = rows.mapNotNull { it["user_id"]?.toString() }
        val achievements = loadAchievements(ids)
        return rows.map { row -> policySnapshot(row, achievements[row["user_id"].toString()].orEmpty()) }
    }

    private fun loadAchievements(ids: List<String>): Map<String, List<Map<String, Any?>>> {
        if (ids.isEmpty()) return emptyMap()
        return store.rows(
            """SELECT user_id, source_record_id, validity, data
               FROM achievements WHERE user_id IN (:ids) ORDER BY user_id, source_record_id""".trimIndent(),
            mapOf("ids" to ids),
        ).groupBy { it["user_id"].toString() }.mapValues { (_, records) ->
            records.map { record ->
                val data = jsonMap(record["data"])
                data + mapOf(
                    "source_record_id" to record["source_record_id"].toString(),
                    "valid" to (record["validity"] == "valid"),
                    "validity_label" to record["validity"]?.toString(),
                )
            }
        }
    }

    private fun policySnapshot(row: Map<String, Any?>, achievementRows: List<Map<String, Any?>>): Map<String, Any?> {
        val data = jsonMap(row["profile_data"] ?: row["data"])
        val qualification = jsonMap(row["qualification_data"])
        val assessment = jsonMap(row["assessment_result"]).ifEmpty { qualification }
        val competencies = listMap(assessment["competency_summary"])
        val evidence = competencies.filter { it["status"] == "verified" }
            .mapNotNull { it["skill_id"]?.toString() }
        val declaredSkills = listMap(data["skills"]).mapNotNull { skill ->
            skill["skill_id"]?.toString()?.takeIf { skill["provenance"] == "candidate_declared" || skill["status"] == "candidate_declared" }
        }.distinct()
        val linked = row["binding_status"] == "linked_confirmed" || row["binding_status"] == "demo_linked"
        val linkData = jsonMap(row["fsp_link_data"])
        val fspResults = if (!linked || row["fsp_source"] != "fsp_mock") emptyList() else achievementRows.map { achievement ->
            val result = achievement["result_kind"]?.toString()
                ?: achievement["result"]?.toString()?.lowercase()
            val team = achievement["team_event"] == true || achievement["team"] == true
            mapOf(
                "source_record_id" to achievement["source_record_id"],
                "valid" to (achievement["valid"] == true),
                "demo" to (achievement["demo"] == true && linkData["demo"] == true),
                "participant_linked" to (achievement["participant_linked"] as? Boolean ?: false),
                "team" to team,
                "membership_confirmed" to achievement["membership_confirmed"],
                "result" to when {
                    result == "prize" || result == "winner" || result == "medal" -> "prize"
                    result == "participant" || achievement["participation_status"] == "confirmed" -> "participant"
                    else -> result
                },
            )
        }
        return mapOf(
            "id" to row["user_id"].toString(),
            "visibility" to if (row["visibility"] == "public") "visible" else row["visibility"],
            "email_confirmed" to (row["email_confirmed"] == true),
            "demo" to (data["demo"] as? Boolean ?: demoMode),
            "specialization_id" to (row["specialization_id"]?.toString() ?: data["specialization_id"]?.toString() ?: MatchingPolicy.SPECIALIZATION_ID),
            "declared_grade" to data["declared_grade"],
            "verified_grade" to row["verified_grade"]?.toString(),
            "allowed_work_modes" to data["allowed_work_modes"],
            "cities" to data["cities"],
            "desired_hours_per_week" to data["desired_hours_per_week"],
            "max_weekly_hours" to data["max_weekly_hours"],
            "test_evidenced_skills" to evidence,
            "candidate_declared_skills" to declaredSkills,
            "fsp_results" to fspResults,
            "mock_evidence_demo" to (row["fsp_source"] == "fsp_mock" && linkData["demo"] == true),
            "profile_data" to data,
            "qualification_data" to qualification,
            "qualification_assessed_at" to row["assessed_at"],
            "qualification_policy_version" to row["policy_version"],
            "qualification_bank_version" to row["bank_version"],
            "fsp_linked" to linked,
            "fsp_retrieved_at" to row["fsp_retrieved_at"],
            "achievement_rows" to achievementRows,
            "competency_summary" to competencies,
        )
    }

    private fun candidateSnapshotById(id: String, requirePublic: Boolean): Map<String, Any?> {
        val publicClause = if (requirePublic) {
            "AND cp.visibility = 'public' AND u.email_verified_at IS NOT NULL ${consents.publicationGateSql()}"
        } else ""
        return store.one(
            """SELECT cp.user_id, cp.version AS profile_version, cp.visibility, cp.data AS profile_data,
                      (u.email_verified_at IS NOT NULL) AS email_confirmed,
                      q.grade AS verified_grade, q.specialization_id,
                      q.assessed_at, q.policy_version, q.bank_version, q.data AS qualification_data,
                      a.result AS assessment_result,
                      fl.binding_status, fl.source AS fsp_source, fl.retrieved_at AS fsp_retrieved_at,
                      fl.data AS fsp_link_data
               FROM candidate_profiles cp JOIN users u ON u.id = cp.user_id
               LEFT JOIN qualifications q ON q.user_id = cp.user_id AND q.specialization_id = '${MatchingPolicy.SPECIALIZATION_ID}'
               LEFT JOIN attempts a ON a.id = q.evidence_attempt_id AND a.status = 'evaluated'
               LEFT JOIN fsp_links fl ON fl.user_id = cp.user_id
               WHERE cp.user_id = :id $publicClause AND u.deleted_at IS NULL""".trimIndent(),
            mapOf("id" to id),
        ) ?: throw ApiException(404, "NOT_FOUND", "Candidate not found")
    }

    private fun publicCandidateFromSnapshot(row: Map<String, Any?>): Map<String, Any?> {
        val id = row["user_id"]?.toString() ?: throw ApiException(404, "NOT_FOUND", "Candidate not found")
        val achievements = loadAchievements(listOf(id))[id].orEmpty()
        val internal = policySnapshot(row, achievements)
        val data = internal.getValue("profile_data") as Map<String, Any?>
        val qualificationData = internal.getValue("qualification_data") as Map<String, Any?>
        val competencySummary = internal.getValue("competency_summary") as List<Map<String, Any?>>
        val assessedAt = internal["qualification_assessed_at"]?.toString()
        val competencyEvidence = buildMap<String, Map<String, Any?>> {
            competencySummary.forEach { item ->
                val skillId = item["skill_id"]?.toString() ?: return@forEach
                put(skillId, mapOf(
                    "skill_id" to skillId,
                    "status" to if (item["status"] == "verified") "test_verified" else "unknown",
                    "source" to "assessment",
                    "verified_at" to if (item["status"] == "verified") assessedAt else null,
                ))
            }
            listMap(data["skills"]).forEach { item ->
                val skillId = item["skill_id"]?.toString() ?: return@forEach
                putIfAbsent(skillId, mapOf(
                    "skill_id" to skillId,
                    "status" to "candidate_declared",
                    "source" to "candidate_profile",
                    "verified_at" to null,
                ))
            }
        }.values.toList()
        val declaredSkills = listMap(data["skills"]).filter { it["provenance"] == "candidate_declared" || it["status"] == "candidate_declared" }
            .map { mapOf("skill_id" to it["skill_id"], "provenance" to "candidate_declared") }
        val fspEvidence = publicFspEvidence(internal).take(100)
        val verifiedGrade = internal["verified_grade"]?.toString()
        val qualification = if (verifiedGrade == null) null else mapOf(
            "grade" to verifiedGrade,
            "specialization_id" to internal["specialization_id"],
            "policy_id" to "backend-python-policy-v1",
            "policy_version" to internal["qualification_policy_version"],
            "bank_version" to internal["qualification_bank_version"],
            "assessed_at" to assessedAt,
            "demo" to (qualificationData["demo"] as? Boolean ?: demoMode),
        )
        val experience = CandidateProfileEntries.experience(data).map { entry ->
            entry + mapOf(
                "company" to PublicTextRedactor.redact(entry["company"] as? String, 160),
                "role" to PublicTextRedactor.redact(entry["role"] as? String, 160),
                "description" to (PublicTextRedactor.redact(entry["description"] as? String, 2000) ?: ""),
            )
        }
        val projects = CandidateProfileEntries.projects(data).map { entry ->
            entry + mapOf(
                "title" to PublicTextRedactor.redact(entry["title"] as? String, 160),
                "description" to (PublicTextRedactor.redact(entry["description"] as? String, 2000) ?: ""),
                "url" to PublicTextRedactor.publicUrl(entry["url"]),
            )
        }
        val cities = (data["cities"] as? Iterable<*>)
            ?.map { PublicTextRedactor.redact(it?.toString(), 100).orEmpty() }
            .orEmpty()
        val displayName = PublicTextRedactor.redact(data["display_name"] as? String, 120).orEmpty()
            .ifBlank { "Имя не указано" }
        return linkedMapOf(
            "id" to id,
            "profile_version" to (row["profile_version"] as? Number)?.toInt(),
            "display_name" to displayName,
            "headline" to PublicTextRedactor.redact(data["headline"] as? String, 160),
            "summary" to PublicTextRedactor.redact(data["summary"] as? String, 2000),
            "specialization_id" to (internal["specialization_id"] ?: data["specialization_id"]),
            "declared_grade" to data["declared_grade"],
            "verified_grade" to verifiedGrade,
            "category_evidence" to MatchingPolicy.categoryEvidence(internal),
            "allowed_work_modes" to data["allowed_work_modes"],
            "cities" to cities,
            "desired_hours_per_week" to data["desired_hours_per_week"],
            "max_weekly_hours" to data["max_weekly_hours"],
            "skills" to declaredSkills,
            "competency_evidence" to competencyEvidence,
            "fsp_signal" to when (MatchingPolicy.fspEvidenceOrdinal(internal["fsp_results"])) {
                2 -> "prize"
                1 -> "participant"
                else -> "none"
            },
            "profile_pdf_url" to "/api/v1/candidates/$id/profile.pdf",
            "qualification" to qualification,
            "demo" to (internal["demo"] as? Boolean ?: demoMode),
            "fsp_evidence" to fspEvidence,
            "experience_entries" to experience,
            "project_entries" to projects,
            "industry" to PublicTextRedactor.redact(data["industry"] as? String, 160),
            "role_preferences" to publicTextList(data["role_preferences"]),
            "soft_skills" to publicTextList(data["soft_skills"]),
        )
    }

    private fun publicTextList(value: Any?): List<String> = (value as? Iterable<*>)
        ?.mapNotNull { PublicTextRedactor.redact(it as? String, 160)?.takeIf(String::isNotBlank) }
        ?.take(10)
        .orEmpty()

    private fun publicFspEvidence(internal: Map<String, Any?>): List<Map<String, Any?>> {
        if (internal["fsp_linked"] != true || internal["mock_evidence_demo"] != true) return emptyList()
        val source = internal["achievement_rows"].asListMap().filter { it["demo"] == true }
        val policyResults = internal["fsp_results"].asListMap().associateBy { it["source_record_id"]?.toString() }
        val rankedIds = policyResults.values.filter { MatchingPolicy.fspEvidenceOrdinal(listOf(it)) > 0 }
            .mapNotNull { it["source_record_id"]?.toString() }.toSet()
        val retrievedAt = internal["fsp_retrieved_at"]?.toString()
        return source.map { row ->
            val sourceRecordId = row["source_record_id"]?.toString()
            val policyResult = policyResults[sourceRecordId]
            mapOf(
                "event_title" to (row["event_title"] ?: row["event_name"] ?: "Соревнование ФСП"),
                "event_date" to (row["event_date"] ?: row["date"]),
                "result" to (row["result"] ?: row["result_text"] ?: "Участие"),
                "team_result" to (row["team_event"] == true || row["team"] == true),
                "membership_confirmed" to (row["membership_confirmed"] == true),
                "participant_linked" to (policyResult?.get("participant_linked") == true),
                "used_for_ranking" to (sourceRecordId != null && sourceRecordId in rankedIds),
                "source" to "fsp_mock",
                "demo" to true,
                "validity" to (row["validity_label"]?.toString()
                    ?: if (row["valid"] == true) "valid" else "unknown"),
                "retrieved_at" to (row["retrieved_at"]?.toString() ?: retrievedAt),
            )
        }
    }

    private fun Any?.asListMap(): List<Map<String, Any?>> = listMap(this)

    private fun Any?.asStrings(): List<String> = (this as? Iterable<*>)
        ?.mapNotNull { it?.toString() }
        .orEmpty()

    private fun listMap(value: Any?): List<Map<String, Any?>> = (value as? Iterable<*>)
        ?.mapNotNull { item -> (item as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } }
        .orEmpty()

    private fun jsonMap(value: Any?): Map<String, Any?> = when (value) {
        is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
        is PGobject -> decodeObject(value.value)
        is String -> decodeObject(value)
        else -> emptyMap()
    }

    private fun decodeObject(value: String?): Map<String, Any?> = value?.let {
        runCatching { store.mapper.readValue(it, object : TypeReference<Map<String, Any?>>() {}) }.getOrDefault(emptyMap())
    }.orEmpty()
}

data class CandidateBankFilters(
    val specializationId: String?,
    val grade: String?,
    val categoryStatus: String,
    val skillIds: List<String>,
    val fspStatus: String,
) {
    fun canonicalParts(): List<String> = listOf(
        specializationId.orEmpty(),
        grade.orEmpty(),
        categoryStatus,
        skillIds.sorted().joinToString(","),
        fspStatus,
    )

    fun context(): Map<String, Any?> = mapOf(
        "specialization_id" to specializationId,
        "grade" to grade,
        "category_status" to categoryStatus,
        "skill_ids" to skillIds.sorted(),
        "fsp_status" to fspStatus,
    )
}

private data class CandidateBankEntry(
    val profile: Map<String, Any?>,
    val id: String,
    val categoryKey: Pair<Int, String>,
    val testSkillMatches: Int,
    val fspOrdinal: Int,
)
