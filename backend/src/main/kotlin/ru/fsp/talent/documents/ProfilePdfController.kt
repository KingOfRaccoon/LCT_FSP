package ru.fsp.talent.documents

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Store
import ru.fsp.talent.invitations.listValue
import ru.fsp.talent.invitations.objectValue
import ru.fsp.talent.invitations.requireUuid
import ru.fsp.talent.matching.MatchingService
import ru.fsp.talent.privacy.ContactAccessJournal

@RestController
@RequestMapping("/api/v1")
class ProfilePdfController(
    private val auth: AuthAccess,
    private val store: Store,
    private val matching: MatchingService,
    private val accessJournal: ContactAccessJournal,
    private val pdf: ProfilePdfService,
) {
    @GetMapping("/me/profile.pdf", produces = ["application/pdf"])
    fun ownProfile(request: HttpServletRequest): ResponseEntity<ByteArray> {
        val actor = auth.candidate(request)
        val candidate = ownCandidateProjection(actor.id)
        val ownChannels = store.one("SELECT contacts FROM candidate_profiles WHERE user_id = :id", mapOf("id" to actor.id))
            ?.let { store.objectValue(it["contacts"]) }
            ?.let { ContactRulesForPdf.channels(store.listValue(it["channels"])) }
            .orEmpty()
        return response(pdf.render(candidate, ownChannels))
    }

    @GetMapping("/candidates/{candidate_id}/profile.pdf", produces = ["application/pdf"])
    fun publicProfile(
        request: HttpServletRequest,
        @PathVariable("candidate_id") candidateId: String,
    ): ResponseEntity<ByteArray> {
        val actor = auth.current(request).let { if (it.role == "employer") auth.verified(it) else it }
        requireUuid(candidateId, "candidate_id")
        return store.tx.execute<ResponseEntity<ByteArray>> {
            store.one(
                """SELECT cp.user_id FROM candidate_profiles cp JOIN users u ON u.id = cp.user_id
                   WHERE cp.user_id = :id AND cp.visibility = 'public'
                     AND u.email_verified_at IS NOT NULL AND u.deleted_at IS NULL
                   FOR SHARE OF cp""".trimIndent(),
                mapOf("id" to candidateId),
            ) ?: throw ApiException(404, "NOT_FOUND", "Candidate not found")
            val candidate = matching.publicCandidateById(candidateId)
            val grant = actor.companyId?.let { accessJournal.lockActiveGrant(it, candidateId) }
            val channels = grant?.let { row ->
                val contactObject = store.objectValue(row["contacts"])
                ContactRulesForPdf.channels(store.listValue(contactObject["channels"]))
            }.orEmpty()
            val bytes = pdf.render(candidate, channels)
            if (channels.isNotEmpty() && grant != null && actor.companyId != null) {
                accessJournal.record(candidateId, actor.companyId, actor.id, grant.getValue("grant_id").toString(), ContactAccessJournal.PROFILE_EXPORT)
            }
            response(bytes)
        } ?: throw IllegalStateException("PDF transaction returned no response")
    }

    private fun ownCandidateProjection(candidateId: String): Map<String, Any?> {
        val profile = store.one(
            """SELECT cp.user_id, cp.visibility, cp.data, u.email_verified_at
               FROM candidate_profiles cp JOIN users u ON u.id = cp.user_id
               WHERE cp.user_id = :id AND u.deleted_at IS NULL""".trimIndent(),
            mapOf("id" to candidateId),
        ) ?: throw ApiException(404, "NOT_FOUND", "Candidate profile not found")
        if (profile["visibility"]?.toString() == "public") return matching.publicCandidate(profile)
        val data = store.objectValue(profile["data"])
        return data + mapOf(
            "id" to candidateId,
            "display_name" to data["display_name"],
            "specialization_id" to (data["specialization_id"] ?: "backend-python-v1"),
            "declared_grade" to data["declared_grade"],
            "verified_grade" to null,
            "allowed_work_modes" to simpleList(data["allowed_work_modes"]),
            "cities" to simpleList(data["cities"]),
            "max_weekly_hours" to data["max_weekly_hours"],
            "competency_evidence" to emptyList<Any>(),
            "fsp_evidence" to emptyList<Any>(),
            "qualification" to null,
        )
    }

    private fun simpleList(value: Any?): List<String> = (value as? Collection<*>)?.mapNotNull { it?.toString() }.orEmpty()

    private fun response(bytes: ByteArray): ResponseEntity<ByteArray> = ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"candidate-profile.pdf\"")
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(bytes)
}

private object ContactRulesForPdf {
    fun channels(values: List<Map<String, Any?>>): List<Map<String, String>> = values
        .filter { it["enabled"] == true }
        .mapNotNull { channel ->
            val type = channel["type"]?.toString() ?: return@mapNotNull null
            val value = channel["value"]?.toString() ?: return@mapNotNull null
            mapOf("type" to type, "value" to value)
        }
}
