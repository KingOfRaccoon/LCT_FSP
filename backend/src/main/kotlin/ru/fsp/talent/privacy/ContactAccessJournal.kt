package ru.fsp.talent.privacy

import org.springframework.stereotype.Component
import ru.fsp.talent.core.Store

@Component
class ContactAccessJournal(private val store: Store) {
    /** Locks the current grant through the response-producing transaction, serializing with revoke. */
    fun lockActiveGrant(companyId: String, candidateId: String): Map<String, Any?>? = store.one(
        """SELECT g.id AS grant_id, g.company_id, g.candidate_id, g.granted_at, cp.contacts
           FROM contact_grants g
           JOIN candidate_profiles cp ON cp.user_id = g.candidate_id
           JOIN users u ON u.id = g.candidate_id AND u.deleted_at IS NULL
           WHERE g.company_id = :companyId AND g.candidate_id = :candidateId AND g.revoked_at IS NULL
           FOR UPDATE OF g""".trimIndent(),
        mapOf("companyId" to companyId, "candidateId" to candidateId),
    )

    fun record(candidateId: String, companyId: String, actorUserId: String, grantId: String, kind: String) {
        require(kind == CONTACT_VIEW || kind == PROFILE_EXPORT)
        store.exec(
            """INSERT INTO contact_access_events(id, candidate_id, company_id, actor_user_id, grant_id, kind)
               VALUES (:id, :candidateId, :companyId, :actorUserId, :grantId, :kind)""".trimIndent(),
            mapOf(
                "id" to store.id(), "candidateId" to candidateId, "companyId" to companyId,
                "actorUserId" to actorUserId, "grantId" to grantId, "kind" to kind,
            ),
        )
    }

    companion object {
        const val CONTACT_VIEW = "contact_view"
        const val PROFILE_EXPORT = "profile_export"
    }
}
