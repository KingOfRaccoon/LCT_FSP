package ru.fsp.talent.core

import org.junit.jupiter.api.BeforeEach
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import java.security.SecureRandom
import java.time.Instant
import java.sql.Timestamp
import java.util.Base64
import java.util.UUID

data class TestActor(
    val id: String,
    val email: String,
    val role: String,
    val password: String,
    val sessionCookie: String,
    val csrfToken: String,
    val companyId: String? = null,
)

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class DbTestBase {
    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    @Autowired
    protected lateinit var http: TestRestTemplate

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    @Autowired
    protected lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    protected lateinit var sessions: SessionSupport

    @LocalServerPort
    protected var port: Int = 0

    @BeforeEach
    fun requireIsolatedTestDatabase() {
        val database = jdbc.queryForObject("SELECT current_database()", String::class.java)
        check(database == "fsp_test") {
            "Integration tests are restricted to fsp_test; connected to $database"
        }
        jdbc.execute("DELETE FROM rate_limit_events")
        jdbc.execute("DELETE FROM rate_limit_blocks")
    }

    protected fun seedActor(
        role: String = "candidate",
        verified: Boolean = true,
        defaultConsents: Boolean = true,
    ): TestActor {
        require(role == "candidate" || role == "employer")
        val id = UUID.randomUUID().toString()
        val companyId = if (role == "employer") UUID.randomUUID().toString() else null
        val email = "${role}-${UUID.randomUUID()}@example.test"
        val password = "DemoPassword123!"
        val passwordHash = passwordEncoder.encode(password)
        val sessionId = UUID.randomUUID().toString()
        val sessionCookie = ByteArray(32).also(SecureRandom()::nextBytes).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }
        val csrf = sessions.csrfFor(sessionId)
        val verifiedAt = if (verified) Instant.now() else null
        jdbc.update(
            """INSERT INTO users(id, email, password_hash, role, email_verified_at)
               VALUES (?::uuid, ?, ?, ?, ?)""".trimIndent(),
            id, email, passwordHash, role, verifiedAt?.let(Timestamp::from),
        )
        if (role == "candidate") {
            jdbc.update(
                "INSERT INTO candidate_profiles(user_id) VALUES (?::uuid)",
                id,
            )
        } else {
            jdbc.update(
                """INSERT INTO companies(id, owner_id, data)
                   VALUES (?::uuid, ?::uuid, '{}'::jsonb)""".trimIndent(),
                companyId, id,
            )
            jdbc.update(
                """INSERT INTO memberships(id, user_id, company_id, role, active)
                   VALUES (?::uuid, ?::uuid, ?::uuid, 'owner', true)""".trimIndent(),
                UUID.randomUUID().toString(), id, companyId,
            )
        }
        jdbc.update(
            """INSERT INTO sessions(id, token_hash, csrf_token_hash, user_id, expires_at)
               VALUES (?::uuid, ?, ?, ?::uuid, now() + interval '7 days')""".trimIndent(),
            sessionId, SessionSupport.digest(sessionCookie), SessionSupport.digest(csrf), id,
        )
        val actor = TestActor(id, email, role, password, sessionCookie, csrf, companyId)
        if (defaultConsents) {
            check(consentAccept(actor, "processing").statusCode.is2xxSuccessful) {
                "Unable to set up the processing consent fixture"
            }
            if (role == "candidate") {
                check(consentAccept(actor, "publication").statusCode.is2xxSuccessful) {
                    "Unable to set up the publication consent fixture"
                }
            }
        }
        return actor
    }

    protected fun request(
        actor: TestActor? = null,
        method: HttpMethod,
        path: String,
        body: Any? = null,
        idempotencyKey: String? = null,
    ): ResponseEntity<JsonNode> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            accept = listOf(MediaType.APPLICATION_JSON)
            set("Origin", "http://testserver")
            actor?.let {
                set("Cookie", "${SessionSupport.COOKIE_NAME}=${it.sessionCookie}")
                set("X-CSRF-Token", it.csrfToken)
            }
            idempotencyKey?.let { set("Idempotency-Key", it) }
        }
        val payload = body?.let { objectMapper.writeValueAsString(it) }
        return http.exchange(
            "http://localhost:$port$path",
            method,
            HttpEntity(payload, headers),
            JsonNode::class.java,
        )
    }

    protected fun consentAccept(actor: TestActor, scope: String, version: String = "1.0"): ResponseEntity<JsonNode> = request(
        actor = actor,
        method = HttpMethod.POST,
        path = "/api/v1/me/consents",
        body = mapOf("scope" to scope, "version" to version, "action" to "accept"),
        idempotencyKey = UUID.randomUUID().toString(),
    )
}
