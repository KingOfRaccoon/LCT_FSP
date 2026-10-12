package ru.fsp.talent.operations

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Store

/** Operational permissions are provisioned separately; registration and assessment roles cannot grant them. */
@Component
class OperationsAccess(private val auth: AuthAccess, private val store: Store) {
    fun require(request: HttpServletRequest, permission: String): Actor {
        val actor = auth.verified(auth.current(request))
        if (!has(actor.id, permission)) throw ApiException(403, "FORBIDDEN", "Operational permission required")
        return actor
    }

    fun has(userId: String, permission: String): Boolean = permission in PERMISSIONS && store.one(
        "SELECT active FROM operations_grants WHERE user_id=:userId AND permission=:permission",
        mapOf("userId" to userId, "permission" to permission),
    )?.get("active") == true

    fun getPermissions(userId: String): List<String> = store.rows(
        "SELECT permission FROM operations_grants WHERE user_id=:userId AND active=true ORDER BY permission",
        mapOf("userId" to userId),
    ).mapNotNull { it["permission"]?.toString()?.takeIf(PERMISSIONS::contains) }

    companion object {
        val PERMISSIONS = setOf("company_review", "monitor_read", "proctoring_review")
    }
}
