package ru.fsp.talent.identity

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.*
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.SessionSupport

class IdentityControllerTest : DbTestBase() {
    @Autowired private lateinit var identity: IdentityService

    @BeforeEach
    fun resetRateLimitsForIsolatedIdentityTests() {
        jdbc.update("DELETE FROM rate_limit_events")
        jdbc.update("DELETE FROM rate_limit_blocks")
    }

    @Test
    fun `registration creates restricted candidate and stores only password session and verification hashes`() {
        val csrf = bootstrap()
        val email = "candidate-${System.nanoTime()}@example.test"
        val body = mapOf(
            "email" to email, "password" to "DemoPassword123!", "account_type" to "candidate",
            "display_name" to "Ada Candidate", "company_name" to null,
        )
        val key = "register-${System.nanoTime()}"
        val response = post("/api/v1/auth/register", body, csrf.cookie, csrf.token, key)

        assertEquals(HttpStatus.CREATED, response.statusCode)
        val data = responseData(response)
        assertEquals("candidate", data["account_type"])
        assertEquals(true, data["email_verification_required"])
        val authCookie = response.headers[HttpHeaders.SET_COOKIE]!!.single().substringBefore(';').substringAfter('=')
        val user = jdbc.queryForMap("SELECT id, password_hash, email_verified_at FROM users WHERE email = ?", email)
        assertNotEquals("DemoPassword123!", user["password_hash"])
        assertNull(user["email_verified_at"])
        val session = jdbc.queryForMap("SELECT token_hash, csrf_token_hash FROM sessions WHERE user_id = ? ORDER BY created_at DESC LIMIT 1", user["id"])
        assertEquals(SessionSupport.digest(authCookie), session["token_hash"].toString().trim())
        assertNotEquals(authCookie, session["token_hash"].toString().trim())
        val verificationId = requireNotNull(jdbc.queryForObject(
            "SELECT id::text FROM email_verification WHERE user_id = ?", String::class.java, user["id"],
        ))
        assertEquals(SessionSupport.digest(identity.verificationToken(verificationId)), jdbc.queryForObject("SELECT token_hash FROM email_verification WHERE user_id = ?", String::class.java, user["id"])!!.trim())
        val outbox = jdbc.queryForMap(
            """SELECT payload FROM outbox_events
               WHERE type = 'email_verification'
                 AND payload ->> 'verification_id' = (SELECT id::text FROM email_verification WHERE user_id = ?)""",
            user["id"],
        )
        assertTrue(outbox["payload"].toString().contains("verification_id"))
        assertFalse(outbox["payload"].toString().contains("token"))
        assertFalse(outbox["payload"].toString().contains("DemoPassword123!"))

        val replay = post("/api/v1/auth/register", body, csrf.cookie, csrf.token, key)
        assertEquals(HttpStatus.CREATED, replay.statusCode)
        assertEquals(data, responseData(replay))
        assertEquals(authCookie, replayCookie(replay))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Int::class.java, email))

        val conflict = post("/api/v1/auth/register", body + ("display_name" to "Different Name"), csrf.cookie, csrf.token, key)
        assertEquals(HttpStatus.CONFLICT, conflict.statusCode)
        assertEquals("IDEMPOTENCY_CONFLICT", responseError(conflict)["code"])

        val authenticatedCsrf = bootstrap(authCookie)
        val logout = post(
            "/api/v1/auth/logout", emptyMap<String, Any>(), authenticatedCsrf.cookie,
            authenticatedCsrf.token, "logout-registration-session-${System.nanoTime()}",
        )
        assertEquals(HttpStatus.OK, logout.statusCode)
        val expiredReplay = post("/api/v1/auth/register", body, csrf.cookie, csrf.token, key)
        assertEquals(HttpStatus.CONFLICT, expiredReplay.statusCode)
        assertEquals("INVALID_STATE", responseError(expiredReplay)["code"])
    }

    @Test
    fun `profile editing is allowed before email confirmation while visibility is blocked`() {
        val email = "unverified-profile-${System.nanoTime()}@example.test"
        val session = register(email)
        assertEquals(HttpStatus.OK, acceptConsent(session, "processing").statusCode)
        assertEquals(HttpStatus.OK, acceptConsent(session, "publication").statusCode)
        val profile = responseData(get("/api/v1/me/profile", session.cookie))
        assertEquals(false, profile["profile_complete"])
        val changed = patch("/api/v1/me/profile", mapOf(
            "expected_version" to profile["version"], "display_name" to "Test Candidate", "declared_grade" to "junior",
            "allowed_work_modes" to listOf("remote"), "max_weekly_hours" to 40,
            "cities" to listOf("Moscow"),
        ), session, "profile-${System.nanoTime()}")
        assertEquals(HttpStatus.OK, changed.statusCode)
        val updated = responseData(changed)
        assertEquals(true, updated["profile_complete"])

        val visibility = patch("/api/v1/me/visibility", mapOf(
            "expected_version" to updated["version"], "visibility" to "public",
        ), session, "visibility-${System.nanoTime()}")
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, visibility.statusCode)
        assertEquals("EMAIL_UNCONFIRMED", responseError(visibility)["code"])

        val verificationId = requireNotNull(jdbc.queryForObject(
            "SELECT id::text FROM email_verification WHERE email = ? AND used_at IS NULL ORDER BY expires_at DESC LIMIT 1",
            String::class.java,
            email,
        ))
        val confirmed = post(
            "/api/v1/auth/confirm-email",
            mapOf("token" to identity.verificationToken(verificationId)),
            session.cookie,
            session.token,
            "confirm-profile-${System.nanoTime()}",
        )
        assertEquals(HttpStatus.OK, confirmed.statusCode)
        val visible = patch("/api/v1/me/visibility", mapOf(
            "expected_version" to updated["version"], "visibility" to "public",
        ), session, "visibility-confirmed-${System.nanoTime()}")
        assertEquals(HttpStatus.OK, visible.statusCode, visible.body.toString())
    }

    @Test
    fun `mock FSP evidence is coarse demo data and excludes external id`() {
        val actor = seedActor()
        val session = Session(actor.sessionCookie, actor.csrfToken)
        val linked = post("/api/v1/me/fsp-link", mapOf(
            "external_id" to "private-fsp-123", "binding_method" to "test_fixture",
        ), session.cookie, session.token, "fsp-link-${System.nanoTime()}")
        assertEquals(HttpStatus.CREATED, linked.statusCode)
        assertFalse(linked.body.toString().contains("private-fsp-123"))
        assertTrue(linked.body.toString().contains("demo"))
        val evidence = get("/api/v1/me/fsp-evidence", session.cookie)
        assertEquals(HttpStatus.OK, evidence.statusCode)
        val data = responseData(evidence)
        assertEquals(true, data["demo"])
        assertTrue(data["evidence"] is List<*>)
    }

    @Test
    fun `login rotates preauth session and me contains account DTO`() {
        val actor = seedActor()
        val email = actor.email
        val preAuth = bootstrap()
        val body = mapOf(
            "email" to email, "password" to "DemoPassword123!",
        )
        val key = "login-${System.nanoTime()}"
        val response = post("/api/v1/auth/login", body, preAuth.cookie, preAuth.token, key)
        assertEquals(HttpStatus.OK, response.statusCode)
        val account = responseData(response)
        assertEquals(email, account["email"])
        assertNull(account["csrf_token"])
        val cookie = response.headers[HttpHeaders.SET_COOKIE]!!.single().substringBefore(';').substringAfter('=')
        assertNotEquals(preAuth.cookie, cookie)
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/v1/auth/me", preAuth.cookie).statusCode)
        assertEquals(HttpStatus.OK, get("/api/v1/auth/me", cookie).statusCode)
        val replay = post("/api/v1/auth/login", body, preAuth.cookie, preAuth.token, key)
        assertEquals(HttpStatus.OK, replay.statusCode)
        assertEquals(cookie, replayCookie(replay))
        assertEquals(2, jdbc.queryForObject(
            "SELECT count(*) FROM sessions WHERE user_id = (SELECT id FROM users WHERE email = ?)",
            Int::class.java,
            email,
        ))
        val conflict = post("/api/v1/auth/login", body + ("password" to "WrongPassword123!"), preAuth.cookie, preAuth.token, key)
        assertEquals(HttpStatus.CONFLICT, conflict.statusCode)
        assertEquals("IDEMPOTENCY_CONFLICT", responseError(conflict)["code"])
    }

    @Test
    fun `employer registration creates owner company and company patch is versioned`() {
        val preAuth = bootstrap()
        val email = "employer-${System.nanoTime()}@example.test"
        val response = post("/api/v1/auth/register", mapOf(
            "email" to email, "password" to "DemoPassword123!", "account_type" to "employer",
            "display_name" to "Company Owner", "company_name" to "Northwind",
        ), preAuth.cookie, preAuth.token, "employer-${System.nanoTime()}")
        assertEquals(HttpStatus.CREATED, response.statusCode)
        val cookie = response.headers[HttpHeaders.SET_COOKIE]!!.single().substringBefore(';').substringAfter('=')
        val account = responseData(get("/api/v1/auth/me", cookie))
        assertEquals("owner", account["membership_role"])
        val company = account["company"] as Map<*, *>
        assertEquals("Northwind", company["name"])
        val session = bootstrap(cookie)
        assertEquals(HttpStatus.OK, acceptConsent(session, "processing").statusCode)
        val updated = patch("/api/v1/me/company", mapOf(
            "expected_version" to company["version"], "name" to "Northwind Labs",
            "description" to "A demo employer",
        ), session, "company-${System.nanoTime()}")
        assertEquals(HttpStatus.OK, updated.statusCode)
        assertEquals("Northwind Labs", responseData(updated)["name"])
    }

    @Test
    fun `email confirmation consumes derived one time token`() {
        val session = register("verify-${System.nanoTime()}@example.test")
        val email = requireNotNull(jdbc.queryForObject(
            "SELECT email FROM users WHERE id = (SELECT user_id FROM sessions WHERE token_hash = ?)",
            String::class.java,
            SessionSupport.digest(session.cookie),
        ))
        val csrf = bootstrap()
        val resend = post(
            "/api/v1/auth/resend-confirmation",
            mapOf("email" to email),
            csrf.cookie,
            csrf.token,
            "resend-confirm-${System.nanoTime()}",
        )
        assertEquals(HttpStatus.ACCEPTED, resend.statusCode)
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM email_verification WHERE user_id = (SELECT user_id FROM sessions WHERE token_hash = ?) AND used_at IS NULL",
            Int::class.java,
            SessionSupport.digest(session.cookie),
        ))
        val verificationId = requireNotNull(jdbc.queryForObject(
            "SELECT id::text FROM email_verification WHERE email = ? AND used_at IS NULL ORDER BY expires_at DESC LIMIT 1",
            String::class.java,
            email,
        ))
        val token = identity.verificationToken(verificationId)
        val confirmed = post("/api/v1/auth/confirm-email", mapOf("token" to token), csrf.cookie, csrf.token, "confirm-${System.nanoTime()}")
        assertEquals(HttpStatus.OK, confirmed.statusCode)
        assertEquals(true, responseData(confirmed)["email_verified"])
        assertEquals(true, responseData(get("/api/v1/auth/me", session.cookie))["email_verified"])
        val reused = post("/api/v1/auth/confirm-email", mapOf("token" to token), csrf.cookie, csrf.token, "confirm-reuse-${System.nanoTime()}")
        assertEquals(HttpStatus.BAD_REQUEST, reused.statusCode)
    }

    @Test
    fun `logout revokes session and clears browser cookie`() {
        val actor = seedActor()
        val session = Session(actor.sessionCookie, actor.csrfToken)
        val body = emptyMap<String, Any>()
        val key = "logout-${System.nanoTime()}"
        val response = post("/api/v1/auth/logout", body, session.cookie, session.token, key)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.headers[HttpHeaders.SET_COOKIE]!!.single().contains("Max-Age=0"))
        val replay = post("/api/v1/auth/logout", body, session.cookie, session.token, key)
        assertEquals(HttpStatus.OK, replay.statusCode)
        assertEquals(responseData(response), responseData(replay))
        assertTrue(replay.headers[HttpHeaders.SET_COOKIE]!!.single().contains("Max-Age=0"))
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/v1/auth/me", session.cookie).statusCode)
    }

    @Test
    fun `registration is limited to five requests per client IP per hour`() {
        val session = bootstrap()
        repeat(5) { index ->
            val response = post("/api/v1/auth/register", mapOf(
                "email" to "rate-register-$index-${System.nanoTime()}@example.test",
                "password" to "DemoPassword123!", "account_type" to "candidate",
                "display_name" to "Rate Test", "company_name" to null,
            ), session.cookie, session.token, "registration-$index-${System.nanoTime()}")
            assertEquals(HttpStatus.CREATED, response.statusCode)
        }
        val limited = post("/api/v1/auth/register", mapOf(
            "email" to "rate-register-over-${System.nanoTime()}@example.test",
            "password" to "DemoPassword123!", "account_type" to "candidate",
            "display_name" to "Rate Test", "company_name" to null,
        ), session.cookie, session.token, "registration-over-${System.nanoTime()}")
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, limited.statusCode)
        assertEquals("RATE_LIMITED", responseError(limited)["code"])
    }

    @Test
    fun `confirmation resend has a sixty second minimum interval`() {
        val session = bootstrap()
        val body = mapOf("email" to "unknown-resend-${System.nanoTime()}@example.test")
        val firstKey = "resend-first-${System.nanoTime()}"
        val first = post("/api/v1/auth/resend-confirmation", body, session.cookie, session.token, firstKey)
        assertEquals(HttpStatus.ACCEPTED, first.statusCode)
        val replay = post("/api/v1/auth/resend-confirmation", body, session.cookie, session.token, firstKey)
        assertEquals(HttpStatus.ACCEPTED, replay.statusCode)
        assertEquals(responseData(first), responseData(replay))
        val second = post("/api/v1/auth/resend-confirmation", body, session.cookie, session.token, "resend-second-${System.nanoTime()}")
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.statusCode)
        assertEquals("RATE_LIMITED", responseError(second)["code"])
    }

    @Test
    fun `sixth failed login attempt is blocked for the rate window`() {
        val session = bootstrap()
        val body = mapOf("email" to "unknown-login-${System.nanoTime()}@example.test", "password" to "WrongPassword123!")
        repeat(5) { index ->
            val response = post("/api/v1/auth/login", body, session.cookie, session.token, "failed-login-$index-${System.nanoTime()}")
            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        }
        val blocked = post("/api/v1/auth/login", body, session.cookie, session.token, "failed-login-blocked-${System.nanoTime()}")
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, blocked.statusCode)
        assertEquals("RATE_LIMITED", responseError(blocked)["code"])
    }

    private data class Session(val cookie: String, val token: String)

    private fun register(email: String): Session {
        val preAuth = bootstrap()
        val response = post("/api/v1/auth/register", mapOf(
            "email" to email, "password" to "DemoPassword123!", "account_type" to "candidate",
            "display_name" to "Test Candidate", "company_name" to null,
        ), preAuth.cookie, preAuth.token, "register-${System.nanoTime()}")
        assertEquals(HttpStatus.CREATED, response.statusCode)
        val cookie = response.headers[HttpHeaders.SET_COOKIE]!!.single().substringBefore(';').substringAfter('=')
        val authenticatedCsrf = bootstrap(cookie)
        return Session(authenticatedCsrf.cookie, authenticatedCsrf.token)
    }

    private fun bootstrap(cookie: String? = null): Session {
        val headers = HttpHeaders().apply { cookie?.let { add(HttpHeaders.COOKIE, "fsp_session=$it") } }
        val response = http.exchange(url("/api/v1/auth/csrf"), HttpMethod.GET, HttpEntity<Void>(headers), Map::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
        val data = responseData(response)
        val nextCookie = response.headers[HttpHeaders.SET_COOKIE]?.singleOrNull()
            ?.substringBefore(';')?.substringAfter('=') ?: cookie
        return Session(nextCookie!!, data["csrf_token"].toString())
    }

    private fun get(path: String, cookie: String) = http.exchange(
        url(path), HttpMethod.GET, HttpEntity<Void>(headers(cookie)), Map::class.java,
    )

    private fun post(path: String, body: Any, cookie: String, token: String, key: String) = http.exchange(
        url(path), HttpMethod.POST, HttpEntity(body, headers(cookie, token, key)), Map::class.java,
    )

    private fun patch(path: String, body: Any, session: Session, key: String) = http.exchange(
        url(path), HttpMethod.PATCH, HttpEntity(body, headers(session.cookie, session.token, key)), Map::class.java,
    )

    private fun acceptConsent(session: Session, scope: String) = post(
        "/api/v1/me/consents",
        mapOf("scope" to scope, "version" to "1.0", "action" to "accept"),
        session.cookie,
        session.token,
        "consent-$scope-${System.nanoTime()}",
    )

    private fun headers(cookie: String, token: String? = null, key: String? = null) = HttpHeaders().apply {
        add(HttpHeaders.COOKIE, "fsp_session=$cookie")
        add("Origin", "http://testserver")
        token?.let { add("X-CSRF-Token", it) }
        key?.let { add("Idempotency-Key", it.padEnd(16, 'x')) }
        contentType = MediaType.APPLICATION_JSON
    }

    private fun responseData(response: ResponseEntity<*>): Map<*, *> {
        val envelope = requireNotNull(response.body) as? Map<*, *> ?: error("Expected response envelope")
        return envelope["data"] as? Map<*, *> ?: error("Expected data object")
    }

    private fun responseError(response: ResponseEntity<*>): Map<*, *> {
        val envelope = requireNotNull(response.body) as? Map<*, *> ?: error("Expected error envelope")
        return envelope["error"] as? Map<*, *> ?: error("Expected error object")
    }

    private fun replayCookie(response: ResponseEntity<*>): String =
        requireNotNull(response.headers[HttpHeaders.SET_COOKIE]).single().substringBefore(';').substringAfter('=')

    private fun url(path: String) = "http://localhost:$port$path"
}
