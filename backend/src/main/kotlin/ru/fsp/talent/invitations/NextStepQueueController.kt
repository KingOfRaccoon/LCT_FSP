package ru.fsp.talent.invitations

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.consents.ConsentService
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.Store
import ru.fsp.talent.privacy.PublicTextRedactor
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

@RestController
@RequestMapping("/api/v1/me/next-steps")
class NextStepQueueController(
    private val auth: AuthAccess,
    private val contracts: Contracts,
    private val store: Store,
    private val consents: ConsentService,
) {
    @GetMapping
    fun list(
        request: HttpServletRequest,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.current(request)
        val queryLimit = parseLimit(limit)
        if ((cursor?.length ?: 0) > MAX_CURSOR_LENGTH) invalidCursor()
        val decodedCursor = QueueCursor.decode(cursor)
        if (decodedCursor != null && (decodedCursor.viewerId != actor.id || decodedCursor.viewerRole != actor.role)) {
            invalidCursor()
        }

        val responsibleParty = when (actor.role) {
            "candidate" -> "candidate"
            "employer" -> {
                auth.verified(actor)
                requireOwner(actor)
                "company"
            }
            else -> throw ApiException(403, "FORBIDDEN", "Candidate or employer access required")
        }

        val parameters = mutableMapOf<String, Any?>(
            "actorId" to actor.id,
            "companyId" to actor.companyId,
            "actorRole" to actor.role,
            "responsibleParty" to responsibleParty,
            "limit" to queryLimit + 1,
        )
        val cursorClause = if (decodedCursor == null) "" else {
            parameters.putAll(
                mapOf(
                    "cursorPriority" to decodedCursor.priority,
                    "cursorDueAt" to decodedCursor.dueAt,
                    "cursorUpdatedAt" to decodedCursor.updatedAt,
                    "cursorInvitationId" to decodedCursor.invitationId,
                ),
            )
            """
            AND (
                CASE WHEN n.responsible_party = :responsibleParty THEN 0 ELSE 1 END,
                COALESCE(n.due_at, 'infinity'::timestamptz), n.updated_at, i.id
            ) > (
                :cursorPriority,
                COALESCE(CAST(:cursorDueAt AS timestamptz), 'infinity'::timestamptz),
                CAST(:cursorUpdatedAt AS timestamptz), CAST(:cursorInvitationId AS uuid)
            )
            """.trimIndent()
        }

        val rows = store.rows(
            """
            SELECT i.id, i.version AS invitation_version, i.snapshot ->> 'company_name' AS company_name,
                   CASE WHEN cp.visibility = 'public' AND u.email_verified_at IS NOT NULL
                                  AND u.deleted_at IS NULL ${consents.publicationGateSql()}
                        THEN 'public' ELSE 'hidden' END AS candidate_visibility,
                   CASE WHEN cp.visibility = 'public' AND u.email_verified_at IS NOT NULL
                                  AND u.deleted_at IS NULL ${consents.publicationGateSql()}
                        THEN cp.data ->> 'display_name' ELSE NULL END AS candidate_display_name,
                   n.version AS step_version, n.action, n.responsible_party, n.due_at,
                   n.completed, n.updated_at,
                   CASE WHEN n.responsible_party = :responsibleParty THEN 0 ELSE 1 END AS action_priority,
                   (n.responsible_party = :responsibleParty) AS is_my_action
            FROM invitations i
            JOIN users u ON u.id = i.candidate_id AND u.deleted_at IS NULL
            JOIN contact_grants original_grant ON original_grant.id = i.contact_grant_id
                 AND original_grant.company_id = i.company_id AND original_grant.candidate_id = i.candidate_id
                 AND original_grant.revoked_at IS NULL
            JOIN invitation_next_steps n ON n.invitation_id = i.id AND n.completed = false
            LEFT JOIN candidate_profiles cp ON cp.user_id = i.candidate_id
            WHERE i.state = 'accepted'
              AND ((:actorRole = 'candidate' AND i.candidate_id = :actorId)
                OR (:actorRole = 'employer' AND i.company_id = :companyId))
              $cursorClause
            ORDER BY action_priority ASC, COALESCE(n.due_at, 'infinity'::timestamptz) ASC,
                     n.updated_at ASC, i.id ASC
            LIMIT :limit
            """.trimIndent(),
            parameters,
        )
        val pageRows = rows.take(queryLimit)
        val nextCursor = if (rows.size > queryLimit) pageRows.lastOrNull()?.let { QueueCursor.from(it, actor).encode() } else null
        val page = mapOf(
            "items" to pageRows.map(::itemDto),
            "next_cursor" to nextCursor,
            "limit" to queryLimit,
        )
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(contracts.envelope(page))
    }

    private fun requireOwner(actor: Actor) {
        val companyId = actor.companyId ?: throw ApiException(403, "FORBIDDEN", "Company owner access required")
        val owner = store.one(
            """SELECT 1 FROM memberships WHERE user_id = :actorId AND company_id = :companyId
               AND role = 'owner' AND active = true""".trimIndent(),
            mapOf("actorId" to actor.id, "companyId" to companyId),
        )
        if (owner == null) throw ApiException(403, "FORBIDDEN", "Company owner access required")
    }

    private fun parseLimit(raw: String?): Int {
        if (raw == null) return DEFAULT_LIMIT
        if (!raw.matches(Regex("0|[1-9][0-9]*"))) invalidLimit()
        val parsed = raw.toIntOrNull() ?: invalidLimit()
        if (parsed !in 1..MAX_PAGE_SIZE) invalidLimit()
        return parsed
    }

    private fun itemDto(row: Map<String, Any?>): Map<String, Any?> {
        val responsibleParty = row.getValue("responsible_party").toString()
        return linkedMapOf(
            "invitation_id" to row.getValue("id").toString(),
            "invitation_version" to (row.getValue("invitation_version") as Number).toInt(),
            "company_name" to PublicTextRedactor.redact(row["company_name"] as? String, 160).orEmpty(),
            "candidate_display_name" to PublicTextRedactor.redact(row["candidate_display_name"] as? String, 120),
            "candidate_visibility" to row.getValue("candidate_visibility").toString(),
            "is_my_action" to row.getValue("is_my_action"),
            "step" to linkedMapOf(
                "version" to (row.getValue("step_version") as Number).toInt(),
                "action" to row.getValue("action").toString(),
                "responsible_party" to responsibleParty,
                "due_at" to row["due_at"].asInstant()?.toString(),
                "completed" to row.getValue("completed"),
                "updated_at" to row["updated_at"].asInstant()?.toString(),
            ),
        )
    }

    private fun invalidLimit(): Nothing = throw ApiException(400, "VALIDATION_ERROR", "limit must be between 1 and 100", "limit")

    private fun invalidCursor(): Nothing = throw ApiException(400, "VALIDATION_ERROR", "Invalid cursor", "cursor")

    private data class QueueCursor(
        val viewerId: String,
        val viewerRole: String,
        val priority: Int,
        val dueAt: Instant?,
        val updatedAt: Instant,
        val invitationId: String,
    ) {
        fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "$viewerId|$viewerRole|$priority|${dueAt?.toString() ?: "~"}|$updatedAt|$invitationId".toByteArray(StandardCharsets.UTF_8),
        )

        companion object {
            fun from(row: Map<String, Any?>, actor: Actor): QueueCursor = QueueCursor(
                viewerId = actor.id,
                viewerRole = actor.role,
                priority = (row.getValue("action_priority") as Number).toInt(),
                dueAt = row["due_at"].asInstant(),
                updatedAt = row["updated_at"].asInstant()
                    ?: throw IllegalStateException("Next-step queue updated_at was null"),
                invitationId = row.getValue("id").toString(),
            )

            fun decode(value: String?): QueueCursor? {
                if (value == null) return null
                return runCatching {
                    require(value.isNotEmpty() && value.matches(Regex("[A-Za-z0-9_-]+")))
                    val bytes = Base64.getUrlDecoder().decode(value)
                    val payload = String(bytes, StandardCharsets.UTF_8)
                    require(payload.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes))
                    require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value)
                    val parts = payload.split('|')
                    require(parts.size == 6)
                    val viewerId = strictUuid(parts[0])
                    val viewerRole = parts[1].also { require(it == "candidate" || it == "employer") }
                    require(parts[2] == "0" || parts[2] == "1")
                    val priority = parts[2].toInt()
                    val dueAt = if (parts[3] == "~") null else strictInstant(parts[3])
                    val updatedAt = strictInstant(parts[4])
                    val invitationId = strictUuid(parts[5])
                    QueueCursor(viewerId, viewerRole, priority, dueAt, updatedAt, invitationId)
                }.getOrElse { throw ApiException(400, "VALIDATION_ERROR", "Invalid cursor", "cursor") }
            }

            private fun strictUuid(value: String): String = UUID.fromString(value).toString().also { require(it == value) }

            private fun strictInstant(value: String): Instant = Instant.parse(value).also { require(it.toString() == value) }
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 5
        const val MAX_PAGE_SIZE = 100
        const val MAX_CURSOR_LENGTH = 512
    }
}
