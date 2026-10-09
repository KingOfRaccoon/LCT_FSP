package ru.fsp.talent.invitations

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.MutationService
import ru.fsp.talent.core.Store
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/invitations/{invitation_id}/next-step")
class InvitationNextStepController(
    private val auth: AuthAccess,
    private val contracts: Contracts,
    private val mutations: MutationService,
    private val store: Store,
) {
    @GetMapping
    fun get(
        request: HttpServletRequest,
        @PathVariable("invitation_id") invitationId: String,
    ): Map<String, Any?> {
        requireUuid(invitationId)
        return store.tx.execute<Map<String, Any?>> {
            val actor = auth.current(request)
            val invitation = lockInvitation(invitationId, actor)
            val grant = lockOriginalGrant(invitation)
            if (actor.role == "employer" && (grant == null || grant["revoked_at"] != null)) unavailable()
            contracts.envelope(dto(invitation, grant, actor))
        } ?: throw IllegalStateException("Next-step read transaction returned no response")
    }

    @PutMapping
    fun put(
        request: HttpServletRequest,
        @PathVariable("invitation_id") invitationId: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        requireUuid(invitationId)
        contracts.validate("InvitationNextStepPutRequest", body)
        val expectedVersion = expectedVersion(body)
        val action = body.getValue("action").toString().trim()
        if (action.isEmpty()) throw ApiException(400, "VALIDATION_ERROR", "Action cannot be blank", "action")
        val dueAt = body["due_at"]?.let { raw ->
            runCatching { Instant.parse(raw.toString()) }.getOrElse {
                throw ApiException(400, "VALIDATION_ERROR", "due_at must be an ISO timestamp with a timezone", "due_at")
            }
        }
        return store.tx.execute<ResponseEntity<Map<String, Any?>>> {
            val actor = auth.verified(auth.current(request))
            val invitation = lockInvitation(invitationId, actor)
            val grant = lockOriginalGrant(invitation)
            if (actor.role != "employer" || grant == null || grant["revoked_at"] != null) unavailable()

            // Authorization and the original grant lock must precede idempotency replay.
            mutations.run(request, "user:${actor.id}", body) {
                val existing = store.one(
                    "SELECT version FROM invitation_next_steps WHERE invitation_id = :invitationId",
                    mapOf("invitationId" to invitationId),
                )
                val currentVersion = (existing?.get("version") as? Number)?.toInt() ?: 0
                if (currentVersion != expectedVersion) {
                    throw ApiException(
                        409,
                        "VERSION_CONFLICT",
                        "The next step changed; reload it before retrying",
                        currentVersion = currentVersion,
                    )
                }
                if (currentVersion == Int.MAX_VALUE) {
                    throw ApiException(409, "INVALID_STATE", "The next-step version cannot be increased further")
                }
                val nextVersion = currentVersion + 1
                store.exec(
                    """INSERT INTO invitation_next_steps(invitation_id, version, action, responsible_party, due_at, completed, updated_at)
                       VALUES (:invitationId, :version, :action, :responsibleParty, :dueAt, :completed, now())
                       ON CONFLICT (invitation_id) DO UPDATE SET
                         version = EXCLUDED.version,
                         action = EXCLUDED.action,
                         responsible_party = EXCLUDED.responsible_party,
                         due_at = EXCLUDED.due_at,
                         completed = EXCLUDED.completed,
                         updated_at = now()""".trimIndent(),
                    mapOf(
                        "invitationId" to invitationId,
                        "version" to nextVersion,
                        "action" to action,
                        "responsibleParty" to body.getValue("responsible_party"),
                        "dueAt" to dueAt,
                        "completed" to body.getValue("completed"),
                    ),
                )
                val refreshed = lockInvitation(invitationId, actor)
                dto(refreshed, grant, actor)
            }
        } ?: throw IllegalStateException("Next-step write transaction returned no response")
    }

    private fun lockInvitation(invitationId: String, actor: Actor): Map<String, Any?> = store.one(
        """SELECT i.id, i.company_id, i.candidate_id, i.version, i.snapshot, i.contact_grant_id
           FROM invitations i
           JOIN users candidate ON candidate.id = i.candidate_id AND candidate.deleted_at IS NULL
           JOIN companies company ON company.id = i.company_id
           WHERE i.id = :invitationId AND i.state = 'accepted'
             AND ((:actorRole = 'candidate' AND i.candidate_id = :actorId)
               OR (:actorRole = 'employer' AND i.company_id = :companyId
                   AND EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = :actorId
                       AND m.company_id = i.company_id AND m.role = 'owner' AND m.active = true)))
           FOR UPDATE OF i""".trimIndent(),
        mapOf(
            "invitationId" to invitationId,
            "actorRole" to actor.role,
            "actorId" to actor.id,
            "companyId" to actor.companyId,
        ),
    ) ?: unavailable()

    private fun lockOriginalGrant(invitation: Map<String, Any?>): Map<String, Any?>? {
        val grantId = invitation["contact_grant_id"]?.toString() ?: return null
        return store.one(
            """SELECT id, company_id, candidate_id, revoked_at FROM contact_grants
               WHERE id = :grantId AND company_id = :companyId AND candidate_id = :candidateId FOR UPDATE""".trimIndent(),
            mapOf(
                "grantId" to grantId,
                "companyId" to invitation["company_id"],
                "candidateId" to invitation["candidate_id"],
            ),
        )
    }

    private fun dto(invitation: Map<String, Any?>, grant: Map<String, Any?>?, actor: Actor): Map<String, Any?> {
        val invitationId = invitation.getValue("id").toString()
        val row = store.one(
            """SELECT version, action, responsible_party, due_at, completed, updated_at
               FROM invitation_next_steps WHERE invitation_id = :invitationId""".trimIndent(),
            mapOf("invitationId" to invitationId),
        )
        return mapOf(
            "invitation_id" to invitationId,
            "invitation_version" to (invitation.getValue("version") as Number).toInt(),
            "hiring_plan" to store.objectValue(invitation["snapshot"])["hiring_plan"],
            "can_update" to (actor.role == "employer" && grant != null && grant["revoked_at"] == null),
            "archived" to (grant == null || grant["revoked_at"] != null),
            "step" to row?.let {
                mapOf(
                    "version" to (it.getValue("version") as Number).toInt(),
                    "action" to it.getValue("action").toString(),
                    "responsible_party" to it.getValue("responsible_party").toString(),
                    "due_at" to it["due_at"].asInstant()?.toString(),
                    "completed" to it.getValue("completed"),
                    "updated_at" to it.getValue("updated_at").asInstant()?.toString(),
                )
            },
        )
    }

    private fun requireUuid(value: String) {
        runCatching { UUID.fromString(value) }.getOrElse {
            throw ApiException(400, "VALIDATION_ERROR", "Invalid invitation_id", "invitation_id")
        }
    }

    private fun expectedVersion(body: Map<String, Any?>): Int {
        val raw = body["expected_version"] as? Number
            ?: throw ApiException(400, "VALIDATION_ERROR", "expected_version must be a non-negative 32-bit integer", "expected_version")
        val value = try {
            BigDecimal(raw.toString()).toBigIntegerExact().longValueExact()
        } catch (_: ArithmeticException) {
            throw ApiException(400, "VALIDATION_ERROR", "expected_version must be a non-negative 32-bit integer", "expected_version")
        } catch (_: NumberFormatException) {
            throw ApiException(400, "VALIDATION_ERROR", "expected_version must be a non-negative 32-bit integer", "expected_version")
        }
        if (value !in 0..Int.MAX_VALUE.toLong()) {
            throw ApiException(400, "VALIDATION_ERROR", "expected_version must be a non-negative 32-bit integer", "expected_version")
        }
        return value.toInt()
    }

    private fun unavailable(): Nothing = throw ApiException(404, "NOT_FOUND", "Invitation is unavailable")

    private fun Any?.asInstant(): Instant? = when (this) {
        null -> null
        is Instant -> this
        is java.sql.Timestamp -> toInstant()
        is java.time.OffsetDateTime -> toInstant()
        is String -> Instant.parse(this)
        else -> throw IllegalArgumentException("Unexpected timestamp value")
    }
}
