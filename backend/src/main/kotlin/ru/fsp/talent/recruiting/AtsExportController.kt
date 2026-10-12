package ru.fsp.talent.recruiting

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.consents.ConsentService
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.Store
import ru.fsp.talent.invitations.ContactRules
import ru.fsp.talent.invitations.listValue
import ru.fsp.talent.invitations.objectValue
import ru.fsp.talent.privacy.ContactAccessJournal
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class AtsExportController(
    private val auth: AuthAccess,
    private val contracts: Contracts,
    private val store: Store,
    private val accessJournal: ContactAccessJournal,
    private val consents: ConsentService,
) {

    @GetMapping("/recruiting/export/ats")
    fun exportToAts(
        request: HttpServletRequest,
        @RequestParam(name = "format", defaultValue = "json") format: String,
    ): ResponseEntity<Map<String, Any?>> {
        if (format != "json") {
            throw ApiException(400, "VALIDATION_ERROR", "Поддерживается только JSON-экспорт", "format")
        }
        val actor = auth.verified(auth.employer(request))
        val companyId = actor.companyId?.let(UUID::fromString)
            ?: throw ApiException(403, "FORBIDDEN", "Доступно только компании")

        val items = store.tx.execute {
            val candidateIds = store.rows(
                """SELECT candidate_id FROM contact_grants
                   WHERE company_id = :companyId AND revoked_at IS NULL
                   ORDER BY granted_at DESC, id DESC""".trimIndent(),
                mapOf("companyId" to companyId),
            )
            candidateIds.mapNotNull { grantCandidate ->
                val candidateId = grantCandidate.getValue("candidate_id").toString()
                if (!hasCurrentProcessingConsent(candidateId)) return@mapNotNull null

                val grant = accessJournal.lockActiveGrant(companyId.toString(), candidateId)
                    ?: return@mapNotNull null
                val profileRow = store.one(
                    """SELECT p.data, q.grade AS verified_grade
                       FROM candidate_profiles p
                       LEFT JOIN qualifications q ON q.user_id = p.user_id
                           AND q.specialization_id = 'backend-python-v1'
                       WHERE p.user_id = :candidateId""".trimIndent(),
                    mapOf("candidateId" to candidateId),
                ) ?: return@mapNotNull null

                val profile = store.objectValue(profileRow["data"])
                val contactData = store.objectValue(grant["contacts"])
                val rawChannels = store.listValue(contactData["channels"])
                val channels = ContactRules.grantedChannels(rawChannels)
                val email = channels.firstOrNull { it["type"] == "email" }?.get("value")
                val phone = channels.firstOrNull { it["type"] == "phone" }?.get("value")
                val fullName = profile["display_name"]?.toString().orEmpty()
                val parts = fullName.trim().split(Regex("\\s+")).filter(String::isNotBlank)
                val item = linkedMapOf<String, Any?>(
                    "external_id" to candidateId,
                    "first_name" to parts.firstOrNull(),
                    "last_name" to parts.drop(1).takeIf { it.isNotEmpty() }?.joinToString(" "),
                    "contacts" to channels,
                    "verified_grade" to profileRow["verified_grade"],
                    "declared_grade" to profile["declared_grade"],
                    "skills" to profile["skills"],
                    "source" to "FSP_Talent_Platform",
                    "granted_at" to grant.getValue("granted_at").toString(),
                )
                profile["headline"]?.toString()?.takeIf(String::isNotBlank)?.let { item["position"] = it }
                email?.let { item["email"] = it }
                phone?.let { item["phone"] = it }
                accessJournal.record(candidateId, companyId.toString(), actor.id, grant.getValue("grant_id").toString(), ContactAccessJournal.PROFILE_EXPORT)
                item
            }
        } ?: throw IllegalStateException("ATS export transaction returned no result")

        val result = mapOf(
            "format" to "json",
            "export_version" to "1.0",
            "total_candidates" to items.size,
            "candidates" to items,
        )
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=fsp-ats-export.json")
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .contentType(MediaType.APPLICATION_JSON)
            .body(contracts.envelope(result))
    }

    private fun hasCurrentProcessingConsent(candidateId: String): Boolean = try {
        consents.requireProcessing(candidateId)
        true
    } catch (error: ApiException) {
        if (error.code == "CONSENT_REQUIRED") false else throw error
    }
}
