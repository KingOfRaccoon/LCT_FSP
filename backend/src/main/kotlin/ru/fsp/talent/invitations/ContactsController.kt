package ru.fsp.talent.invitations

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PostMapping
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.MutationService
import ru.fsp.talent.core.Store
import ru.fsp.talent.privacy.ContactAccessJournal

@RestController
@RequestMapping("/api/v1")
class ContactsController(
    private val auth: AuthAccess,
    private val contracts: Contracts,
    private val mutations: MutationService,
    private val store: Store,
    private val accessJournal: ContactAccessJournal,
) {
    @GetMapping("/me/contacts")
    fun ownContacts(request: HttpServletRequest): Map<String, Any?> {
        val actor = auth.candidate(request)
        val row = store.one(
            "SELECT version, contacts FROM candidate_profiles WHERE user_id = :id",
            mapOf("id" to actor.id),
        ) ?: throw ApiException(404, "NOT_FOUND", "Candidate profile not found")
        return contracts.envelope(ownContacts(row))
    }

    @PatchMapping("/me/contacts")
    fun patchOwnContacts(
        request: HttpServletRequest,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.candidate(request)
        contracts.validate("OwnContactsPatch", body)
        val channels = body["channels"] as? List<*> ?: emptyList<Any?>()
        val normalized = channels.map { (it as Map<*, *>).entries.associate { entry -> entry.key.toString() to entry.value } }
        try {
            ContactRules.validateChannels(normalized)
        } catch (e: IllegalArgumentException) {
            throw ApiException(400, "VALIDATION_ERROR", e.message ?: "Invalid contact channels", "channels")
        }
        val expectedVersion = (body["expected_version"] as Number).toInt()
        return mutations.run(request, "user:${actor.id}", body) {
            val current = store.one(
                "SELECT version FROM candidate_profiles WHERE user_id = :id FOR UPDATE",
                mapOf("id" to actor.id),
            ) ?: throw ApiException(404, "NOT_FOUND", "Candidate profile not found")
            val version = (current["version"] as Number).toInt()
            if (version != expectedVersion) {
                throw ApiException(409, "VERSION_CONFLICT", "Candidate profile has changed", currentVersion = version)
            }
            store.exec(
                """UPDATE candidate_profiles SET contacts = :contacts, version = version + 1, updated_at = now()
                   WHERE user_id = :id""".trimIndent(),
                mapOf("contacts" to store.json(mapOf("channels" to normalized)), "id" to actor.id),
            )
            mapOf("version" to version + 1, "channels" to normalized)
        }
    }

    @GetMapping("/candidates/{candidate_id}/contacts")
    fun candidateContacts(
        request: HttpServletRequest,
        @PathVariable("candidate_id") candidateId: String,
    ): Map<String, Any?> {
        val actor = auth.verified(auth.employer(request))
        requireUuid(candidateId, "candidate_id")
        val bundle = store.tx.execute {
            val row = accessJournal.lockActiveGrant(actor.companyId!!, candidateId)
                ?: throw ApiException(404, "NOT_FOUND", "Contact grant not found")
            val result = contactBundle(row, candidateId, actor.companyId!!)
            val channels = result["channels"] as? List<*>
            if (!channels.isNullOrEmpty()) {
                accessJournal.record(candidateId, actor.companyId!!, actor.id, result.getValue("grant_id").toString(), ContactAccessJournal.CONTACT_VIEW)
            }
            result
        }!!
        return contracts.envelope(bundle)
    }

    @GetMapping("/me/contact-grants")
    fun ownGrants(
        request: HttpServletRequest,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): Map<String, Any?> {
        val actor = auth.candidate(request)
        val pageSize = checkPageLimit(limit)
        val after = PageCursor.decode(cursor)
        val afterClause = if (after == null) "" else "AND (g.granted_at, g.id) < (:afterAt, :afterId)"
        val params = buildMap<String, Any?> {
            put("candidateId", actor.id)
            if (after != null) {
                put("afterAt", after.at)
                put("afterId", after.id)
            }
            put("fetchLimit", pageSize + 1)
        }
        val rows = store.rows(
            """SELECT g.id AS grant_id, g.company_id, g.candidate_id, g.granted_at,
                      c.data ->> 'name' AS company_name, cp.contacts
               FROM contact_grants g
               JOIN companies c ON c.id = g.company_id
               JOIN candidate_profiles cp ON cp.user_id = g.candidate_id
               WHERE g.candidate_id = :candidateId AND g.revoked_at IS NULL $afterClause
               ORDER BY g.granted_at DESC, g.id DESC LIMIT :fetchLimit""".trimIndent(),
            params,
        )
        return contracts.envelope(page(rows, pageSize) { row ->
            contactBundle(row, actor.id, row["company_id"].toString())
        })
    }

    @PostMapping("/me/contact-grants/{grant_id}/revoke")
    fun revoke(
        request: HttpServletRequest,
        @PathVariable("grant_id") grantId: String,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.candidate(request)
        requireUuid(grantId, "grant_id")
        val body = emptyMap<String, Any?>()
        return mutations.run(request, "user:${actor.id}", body) {
            val row = store.one(
                """UPDATE contact_grants SET revoked_at = now()
                   WHERE id = :id AND candidate_id = :candidateId AND revoked_at IS NULL
                   RETURNING id, company_id, candidate_id, revoked_at""".trimIndent(),
                mapOf("id" to grantId, "candidateId" to actor.id),
            ) ?: throw ApiException(404, "NOT_FOUND", "Active contact grant not found")
            store.exec(
                """INSERT INTO audit_events(id, actor_id, event_type, entity_id, data)
                   VALUES (:id, :actor, 'contact_grant_revoked', :entity, :data)""".trimIndent(),
                mapOf(
                    "id" to store.id(), "actor" to actor.id, "entity" to row["id"],
                    "data" to store.json(mapOf("company_id" to row["company_id"], "candidate_id" to row["candidate_id"])),
                ),
            )
            mapOf("revoked" to true, "revoked_at" to row["revoked_at"].asInstant().toString())
        }
    }

    internal fun contactBundle(companyId: String, candidateId: String): Map<String, Any?>? {
        val row = store.one(
            """SELECT g.id AS grant_id, g.company_id, g.candidate_id, g.granted_at, cp.contacts
               FROM contact_grants g JOIN candidate_profiles cp ON cp.user_id = g.candidate_id
               JOIN users u ON u.id = g.candidate_id AND u.deleted_at IS NULL
               WHERE g.company_id = :companyId AND g.candidate_id = :candidateId
                 AND g.revoked_at IS NULL""".trimIndent(),
            mapOf("companyId" to companyId, "candidateId" to candidateId),
        ) ?: return null
        return contactBundle(row, candidateId, companyId)
    }

    internal fun contactBundle(row: Map<String, Any?>, candidateId: String, companyId: String): Map<String, Any?> {
        val contactObject = store.objectValue(row["contacts"])
        val channels = ContactRules.grantedChannels(store.listValue(contactObject["channels"]))
        return mapOf(
            "candidate_id" to candidateId,
            "company_id" to companyId,
            "grant_id" to row["grant_id"].toString(),
            "channels" to channels,
            "granted_at" to row["granted_at"].asInstant().toString(),
        )
    }

    private fun ownContacts(row: Map<String, Any?>): Map<String, Any?> {
        val contacts = store.objectValue(row["contacts"])
        return mapOf("version" to (row["version"] as Number).toInt(), "channels" to store.listValue(contacts["channels"]))
    }

    private fun <T> page(rows: List<Map<String, Any?>>, size: Int, transform: (Map<String, Any?>) -> T): Map<String, Any?> {
        val hasMore = rows.size > size
        val selected = rows.take(size)
        val last = selected.lastOrNull()
        return mapOf(
            "items" to selected.map(transform),
            "page" to mapOf(
                "next_cursor" to if (hasMore && last != null) PageCursor(last["granted_at"].asInstant()!!, last["grant_id"].toString()).encode() else null,
                "limit" to size,
            ),
        )
    }
}
