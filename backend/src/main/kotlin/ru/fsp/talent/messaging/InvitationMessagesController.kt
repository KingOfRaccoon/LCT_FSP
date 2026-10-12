package ru.fsp.talent.messaging

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.postgresql.util.PGobject
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.MutationService
import ru.fsp.talent.core.Store
import java.time.Instant
import java.util.Base64
import java.util.UUID

@RestController
@RequestMapping("/api/v1/invitations/{invitation_id}/messages")
class InvitationMessagesController(
    private val auth: AuthAccess,
    private val contracts: Contracts,
    private val mutations: MutationService,
    private val store: Store,
) {
    @GetMapping
    fun list(
        request: HttpServletRequest,
        @PathVariable("invitation_id") invitationId: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): Map<String, Any?> {
        requireUuid(invitationId, "invitation_id")
        if (limit !in 1..100 || (cursor?.length ?: 0) > 512) {
            throw ApiException(400, "VALIDATION_ERROR", "Invalid page limit or cursor")
        }
        val after = MessageCursor.decode(cursor, invitationId)
        return store.tx.execute<Map<String, Any?>> {
            val actor = auth.verified(auth.current(request))
            val thread = findThread(invitationId, actor)
            val cursorClause = if (after == null) "" else "AND (created_at, id) < (:cursorAt, :cursorId)"
            val rows = store.rows(
                """SELECT id, sender_role, body, created_at
                   FROM invitation_messages
                   WHERE invitation_id = :invitationId $cursorClause
                   ORDER BY created_at DESC, id DESC
                   LIMIT :limit""".trimIndent(),
                mapOf(
                    "invitationId" to invitationId,
                    "limit" to limit + 1,
                ) + after?.let { mapOf("cursorAt" to it.at, "cursorId" to it.id) }.orEmpty(),
            )
            val page = rows.take(limit)
            val snapshot = jsonMap(thread["snapshot"])
            val response = mapOf(
                "invitation_id" to invitationId,
                "company_name" to snapshot["company_name"],
                "description" to snapshot["description"],
                "salary" to snapshot["salary"],
                "can_send" to hasActiveOriginalGrant(thread),
                "items" to page.map(::messageDto),
                "next_cursor" to if (rows.size > limit) page.lastOrNull()?.let { row ->
                    MessageCursor(instant(row.getValue("created_at")), row.getValue("id").toString(), invitationId).encode()
                } else null,
            )
            contracts.envelope(response)
        } ?: throw IllegalStateException("Message page transaction returned no response")
    }

    @PostMapping
    fun send(
        request: HttpServletRequest,
        @PathVariable("invitation_id") invitationId: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        requireUuid(invitationId, "invitation_id")
        contracts.validate("InvitationMessageCreateRequest", body)
        val text = body.getValue("text").toString()
        if (text.trim().isEmpty()) {
            throw ApiException(400, "VALIDATION_ERROR", "Message text cannot be empty", "text")
        }
        return store.tx.execute<ResponseEntity<Map<String, Any?>>> {
            val actor = auth.verified(auth.current(request))
            val thread = findThread(invitationId, actor, lock = true)
            val grant = lockActiveOriginalGrant(thread)
                ?: throw ApiException(404, "NOT_FOUND", "Invitation is unavailable")
            val result = mutations.run(request, "user:${actor.id}", body, status = 201) {
                val id = store.id()
                val senderRole = if (actor.role == "candidate") "candidate" else "employer"
                store.exec(
                    """INSERT INTO invitation_messages(id, invitation_id, sender_user_id, sender_role, body)
                       VALUES (:id, :invitationId, :senderUserId, :senderRole, :body)""".trimIndent(),
                    mapOf(
                        "id" to id,
                        "invitationId" to invitationId,
                        "senderUserId" to actor.id,
                        "senderRole" to senderRole,
                        "body" to text,
                    ),
                )
                messageDto(
                    store.one(
                        "SELECT id, sender_role, body, created_at FROM invitation_messages WHERE id = :id",
                        mapOf("id" to id),
                    ) ?: throw IllegalStateException("Inserted invitation message was not found")
                )
            }
            // Keep the exact grant row locked through idempotency replay and the message write.
            check(grant["id"] == thread["contact_grant_id"])
            result
        } ?: throw IllegalStateException("Message send transaction returned no response")
    }

    /** Candidate history survives revocation; employers must still hold this invitation's grant. */
    private fun findThread(invitationId: String, actor: Actor, lock: Boolean = false): Map<String, Any?> {
        val lockClause = if (lock) "FOR UPDATE OF i" else ""
        val row = store.one(
            """SELECT i.id, i.company_id, i.candidate_id, i.state, i.contact_grant_id, i.snapshot
               FROM invitations i
               JOIN users candidate ON candidate.id = i.candidate_id AND candidate.deleted_at IS NULL
               JOIN companies company ON company.id = i.company_id
               WHERE i.id = :invitationId AND i.state = 'accepted'
                 AND ((:actorRole = 'candidate' AND i.candidate_id = :actorId)
                   OR (:actorRole = 'employer' AND i.company_id = :companyId))
               $lockClause""".trimIndent(),
            mapOf(
                "invitationId" to invitationId,
                "actorRole" to actor.role,
                "actorId" to actor.id,
                "companyId" to actor.companyId,
            ),
        ) ?: throw ApiException(404, "NOT_FOUND", "Invitation is unavailable")
        if (actor.role == "employer" && lockActiveOriginalGrant(row) == null) {
            throw ApiException(404, "NOT_FOUND", "Invitation is unavailable")
        }
        return row
    }

    private fun lockActiveOriginalGrant(thread: Map<String, Any?>): Map<String, Any?>? {
        val grantId = thread["contact_grant_id"]?.toString() ?: return null
        return store.one(
            """SELECT id, company_id, candidate_id FROM contact_grants
               WHERE id = :grantId AND company_id = :companyId AND candidate_id = :candidateId
                 AND revoked_at IS NULL FOR UPDATE""".trimIndent(),
            mapOf(
                "grantId" to grantId,
                "companyId" to thread["company_id"],
                "candidateId" to thread["candidate_id"],
            ),
        )
    }

    private fun hasActiveOriginalGrant(thread: Map<String, Any?>): Boolean = lockActiveOriginalGrant(thread) != null

    private fun messageDto(row: Map<String, Any?>): Map<String, Any?> = mapOf(
        "id" to row.getValue("id").toString(),
        "sender_role" to row.getValue("sender_role").toString(),
        "text" to row.getValue("body").toString(),
        "created_at" to instant(row.getValue("created_at")).toString(),
    )

    private fun instant(value: Any?): Instant = when (value) {
        is Instant -> value
        is java.sql.Timestamp -> value.toInstant()
        is java.time.OffsetDateTime -> value.toInstant()
        is String -> Instant.parse(value)
        else -> throw IllegalArgumentException("Unexpected message timestamp")
    }

    private fun jsonMap(value: Any?): Map<String, Any?> = when (value) {
        is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
        is PGobject -> runCatching { store.mapper.readValue(value.value, Map::class.java) }
            .getOrNull()?.entries?.associate { it.key.toString() to it.value }.orEmpty()
        else -> emptyMap()
    }

    private fun requireUuid(value: String, field: String) {
        runCatching { UUID.fromString(value) }.getOrElse {
            throw ApiException(400, "VALIDATION_ERROR", "Invalid $field", field)
        }
    }

    private data class MessageCursor(val at: Instant, val id: String, val invitationId: String) {
        fun encode(): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$invitationId|$at|$id".toByteArray(Charsets.UTF_8))

        companion object {
            fun decode(value: String?, invitationId: String): MessageCursor? {
                if (value.isNullOrBlank()) return null
                return runCatching {
                    val parts = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8).split('|', limit = 3)
                    require(parts.size == 3 && UUID.fromString(parts[0]).toString() == invitationId)
                    MessageCursor(Instant.parse(parts[1]), UUID.fromString(parts[2]).toString(), invitationId)
                }.getOrElse {
                    throw ApiException(400, "VALIDATION_ERROR", "Invalid page cursor", "cursor")
                }
            }
        }
    }
}
