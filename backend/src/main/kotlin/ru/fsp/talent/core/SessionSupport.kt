package ru.fsp.talent.core

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

data class IssuedSession(
    val id: String,
    val csrfToken: String,
    val expiresAt: Instant,
)

@Component
class SessionSupport(
    private val store: Store,
    @Value("\${fsp.session-cookie-secure:false}") private val secureCookie: Boolean,
    @Value("\${fsp.session-secret}") private val sessionSecret: String,
) {

    fun csrf(request: HttpServletRequest, response: HttpServletResponse): IssuedSession {
        val raw = request.cookies?.firstOrNull { it.name == COOKIE_NAME }?.value
        val current = raw?.let { store.one(
            """SELECT id, csrf_token_hash, expires_at FROM sessions
               WHERE token_hash = :tokenHash AND revoked_at IS NULL AND expires_at > now()""".trimIndent(),
            mapOf("tokenHash" to digest(it)),
        ) }
        if (current != null) {
            val sessionId = current.getValue("id").toString()
            val token = csrfFor(sessionId)
            val csrfHash = digest(token)
            if (!constantTimeEquals(current["csrf_token_hash"].toString().trim(), csrfHash)) {
                store.exec("UPDATE sessions SET csrf_token_hash = :hash WHERE id = :id", mapOf(
                    "hash" to csrfHash, "id" to sessionId,
                ))
            }
            return IssuedSession(sessionId, token, current["expires_at"].asInstant())
        }
        return issue(userId = null, response = response, ttl = Duration.ofHours(1))
    }

    fun createAuthenticated(userId: String, response: HttpServletResponse): IssuedSession =
        issue(userId, response, Duration.ofDays(7))

    fun csrfFor(sessionId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal("csrf:$sessionId".toByteArray(Charsets.UTF_8)))
    }

    fun rotate(request: HttpServletRequest, userId: String, response: HttpServletResponse): IssuedSession {
        // An anonymous bootstrap session retains no authenticated privilege. Keeping it
        // for its existing one-hour lifetime permits safe replay after a lost response.
        request.cookies?.firstOrNull { it.name == COOKIE_NAME }?.value?.let { raw ->
            store.exec("UPDATE sessions SET revoked_at=now() WHERE token_hash=:hash AND user_id IS NOT NULL AND revoked_at IS NULL",
                mapOf("hash" to digest(raw)))
        }
        return createAuthenticated(userId, response)
    }

    fun sessionId(request: HttpServletRequest): String? = request.cookies
        ?.firstOrNull { it.name == COOKIE_NAME }?.value?.let { raw ->
            store.one("SELECT id FROM sessions WHERE token_hash=:hash AND revoked_at IS NULL AND expires_at>:now",
                mapOf("hash" to digest(raw), "now" to store.now()))?.get("id")?.toString()
        }

    fun sessionTokenFor(sessionId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal("session-token:$sessionId".toByteArray(Charsets.UTF_8)))
    }

    fun restore(sessionId: String, response: HttpServletResponse) {
        val row = store.one("SELECT s.expires_at,s.token_hash FROM sessions s JOIN users u ON u.id=s.user_id " +
            "WHERE s.id=:id AND s.revoked_at IS NULL AND s.expires_at>:now AND u.deleted_at IS NULL",
            mapOf("id" to sessionId, "now" to store.now()))
            ?: throw ApiException(409, "INVALID_STATE", "Повторяемая сессия уже закрыта. Войдите снова.")
        val token = sessionTokenFor(sessionId)
        if (!constantTimeEquals(row.getValue("token_hash").toString().trim(), digest(token))) {
            throw ApiException(409, "INVALID_STATE", "Повторяемая сессия недоступна. Войдите снова.")
        }
        setCookie(response, token, Duration.between(store.now(), row["expires_at"].asInstant()))
    }

    fun revoke(request: HttpServletRequest) {
        request.cookies?.firstOrNull { it.name == COOKIE_NAME }?.value?.let { raw ->
            store.exec(
                "UPDATE sessions SET revoked_at = now() WHERE token_hash = :tokenHash AND revoked_at IS NULL",
                mapOf("tokenHash" to digest(raw)),
            )
        }
    }

    private fun issue(userId: String?, response: HttpServletResponse, ttl: Duration): IssuedSession {
        val id = store.id()
        val token = sessionTokenFor(id)
        val csrf = csrfFor(id)
        val expiry = store.now().plus(ttl)
        store.exec(
            """INSERT INTO sessions(id, token_hash, csrf_token_hash, user_id, expires_at)
               VALUES (:id, :tokenHash, :csrfHash, :userId, :expiresAt)""".trimIndent(),
            mapOf(
                "id" to id,
                "tokenHash" to digest(token),
                "csrfHash" to digest(csrf),
                "userId" to userId,
                "expiresAt" to expiry,
            ),
        )
        setCookie(response, token, ttl)
        return IssuedSession(id, csrf, expiry)
    }

    private fun setCookie(response: HttpServletResponse, token: String, ttl: Duration) {
        response.addHeader(
            "Set-Cookie",
            ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/")
                .maxAge(ttl).build().toString(),
        )
    }

    companion object {
        const val COOKIE_NAME = "fsp_session"

        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

        fun constantTimeEquals(left: String, right: String): Boolean =
            MessageDigest.isEqual(left.toByteArray(), right.toByteArray())

        private fun Any?.asInstant(): Instant = when (this) {
            is Instant -> this
            is java.sql.Timestamp -> toInstant()
            else -> Instant.parse(this.toString())
        }
    }
}
