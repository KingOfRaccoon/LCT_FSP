package ru.fsp.talent.core

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

@Component
class AuthAccess(private val store: Store) {
    fun current(request: HttpServletRequest): Actor {
        val raw = request.cookies?.firstOrNull { it.name == SessionSupport.COOKIE_NAME }?.value
            ?: throw ApiException(401, "UNAUTHENTICATED", "Sign in to continue")
        val session = store.one(
            """SELECT s.id AS session_id, u.id AS user_id, u.email, u.role,
                      u.email_verified_at,
                      (SELECT m.company_id FROM memberships m
                       WHERE m.user_id = u.id AND m.active = true
                       ORDER BY m.company_id LIMIT 1) AS company_id
               FROM sessions s JOIN users u ON u.id = s.user_id
               WHERE s.token_hash = :tokenHash AND s.revoked_at IS NULL
                 AND s.expires_at > now() AND u.deleted_at IS NULL""".trimIndent(),
            mapOf("tokenHash" to SessionSupport.digest(raw)),
        ) ?: throw ApiException(401, "UNAUTHENTICATED", "Sign in to continue")
        return Actor(
            id = session.getValue("user_id").toString(),
            email = session.getValue("email").toString(),
            role = session.getValue("role").toString(),
            companyId = session["company_id"]?.toString(),
            sessionId = session.getValue("session_id").toString(),
            emailVerified = session["email_verified_at"] != null,
        )
    }

    fun candidate(request: HttpServletRequest): Actor = current(request).also {
        if (it.role != "candidate") throw ApiException(403, "FORBIDDEN", "Candidate access required")
    }

    fun employer(request: HttpServletRequest): Actor = current(request).also {
        if (it.role != "employer" || it.companyId == null) {
            throw ApiException(403, "FORBIDDEN", "Employer access required")
        }
    }

    fun verified(actor: Actor): Actor = actor.also {
        if (!it.emailVerified) throw ApiException(422, "EMAIL_UNCONFIRMED", "Verify your email to continue")
    }
}
