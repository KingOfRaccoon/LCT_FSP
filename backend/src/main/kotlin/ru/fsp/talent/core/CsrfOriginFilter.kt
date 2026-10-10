package ru.fsp.talent.core

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI

@Component
class CsrfOriginFilter(
    private val store: Store,
    private val mapper: ObjectMapper,
    @Value("\${fsp.public-origin}") private val publicOrigin: String,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/api/v1/") ||
            request.method in SAFE_METHODS ||
            request.requestURI == "/api/v1/auth/csrf"

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val origin = request.getHeader("Origin") ?: request.getHeader("Referer")?.let {
            runCatching { URI(it).let { uri -> "${uri.scheme}://${uri.rawAuthority}" } }.getOrNull()
        }
        val baseOrigin = publicOrigin.removeSuffix(":8080").removeSuffix(":80")
        val allowedOrigins = setOf(
            publicOrigin,
            baseOrigin,
            "$baseOrigin:8080",
            "$baseOrigin:80",
            "http://localhost:8080",
            "http://localhost:5173",
            "http://127.0.0.1:8080",
        )
        if (origin !in allowedOrigins) {
            reject(response, 403, "CSRF_ORIGIN_INVALID", "Request origin is not allowed")
            return
        }
        val sessionToken = request.cookies?.firstOrNull { it.name == SessionSupport.COOKIE_NAME }?.value
        if (sessionToken == null) {
            reject(response, 401, "UNAUTHENTICATED", "Session required")
            return
        }
        val session = store.one(
            """SELECT id, csrf_token_hash,revoked_at FROM sessions
               WHERE token_hash = :tokenHash AND expires_at > now()""".trimIndent(),
            mapOf("tokenHash" to SessionSupport.digest(sessionToken)),
        )
        if (session == null) {
            reject(response, 401, "UNAUTHENTICATED", "Session required")
            return
        }
        if (session["revoked_at"] != null) {
            val sid = session.getValue("id").toString()
            val prior = if (request.method == "POST" && request.requestURI == "/api/v1/auth/logout") {
                store.one("SELECT response FROM idempotency_records WHERE scope=:scope AND operation=:operation AND key=:key",
                    mapOf("scope" to "identity-logout:$sid", "operation" to "POST /api/v1/auth/logout",
                        "key" to request.getHeader("Idempotency-Key").orEmpty()))
            } else null
            @Suppress("UNCHECKED_CAST")
            val priorEnvelope = prior?.get("response") as? Map<String, Any?>
            val priorData = priorEnvelope?.get("data") as? Map<*, *>
            if (priorData?.get("logged_out") != true) {
                reject(response, 401, "UNAUTHENTICATED", "Session required")
                return
            }
        }
        val supplied = request.getHeader("X-CSRF-Token")
        val expectedHash = session["csrf_token_hash"]?.toString().orEmpty()
        if (supplied == null || !SessionSupport.constantTimeEquals(expectedHash, SessionSupport.digest(supplied))) {
            reject(response, 403, "CSRF_INVALID", "A valid CSRF token is required")
            return
        }
        request.setAttribute(SESSION_ID_ATTRIBUTE, session["id"].toString())
        chain.doFilter(request, response)
    }

    private fun reject(response: HttpServletResponse, status: Int, code: String, message: String) {
        response.status = status
        response.contentType = "application/json"
        mapper.writeValue(response.outputStream, mapOf("error" to mapOf(
            "code" to code, "message" to message, "retryable" to false, "eligible_at" to null, "active_attempt_id" to null,
        )))
    }

    companion object {
        const val SESSION_ID_ATTRIBUTE = "fsp.sessionId"
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
