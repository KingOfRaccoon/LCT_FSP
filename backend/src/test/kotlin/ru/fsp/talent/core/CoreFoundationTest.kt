package ru.fsp.talent.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpEntity
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.ClassPathResource
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID

class CoreFoundationTest : DbTestBase() {
    @Autowired
    private lateinit var contracts: Contracts

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private lateinit var mappings: RequestMappingHandlerMapping

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Test
    fun `every frozen API operation has an MVC handler`() {
        fun normalize(path: String) = path.replace(Regex("\\{[^}]+}"), "{}")
        val actual = mappings.handlerMethods.keys.flatMap { info ->
            info.patternValues.flatMap { path -> info.methodsCondition.methods.map { method ->
                "${method.name} ${normalize(path)}"
            } }
        }.toSet()
        val spec = ClassPathResource("contracts/openapi.v1.yaml").inputStream.use(mapper::readTree)
        val missing = spec.path("paths").fields().asSequence().flatMap { (path, methods) ->
            methods.fieldNames().asSequence().filter { it in setOf("get", "post", "patch", "put", "delete") }
                .map { "${it.uppercase()} ${normalize("/api/v1$path")}" }
        }.filter { it !in actual }.toList()
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `unknown API route returns a documented not found error`() {
        val response = request(method = HttpMethod.GET, path = "/api/v1/no-such-route")
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("NOT_FOUND", response.body?.path("error")?.path("code")?.asText())
        val payload = mapper.convertValue(response.body, Map::class.java) as Map<String, Any?>
        contracts.validate("ErrorEnvelope", payload)
    }

    @Test
    fun `health is a minimal data envelope`() {
        val response = request(method = HttpMethod.GET, path = "/api/v1/health")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("ok", response.body?.path("data")?.path("status")?.asText())
    }

    @Test
    fun `API successes and privacy errors cannot be reused from HTTP caches`() {
        for (path in listOf("/api/v1/health", "/api/v1/auth/me", "/api/v1/no-such-route")) {
            val response = request(method = HttpMethod.GET, path = path)
            assertEquals("private, no-store", response.headers.getFirst("Cache-Control"))
        }
    }

    @Test
    fun `PDF with unacceptable Accept returns 406 without releasing or journaling granted contacts`() {
        val employer = seedActor(role = "employer")
        val candidate = seedActor(role = "candidate")
        val email = "private-${UUID.randomUUID()}@example.test"
        jdbc.update(
            "UPDATE candidate_profiles SET visibility='public', data=?::jsonb, contacts=?::jsonb WHERE user_id=?::uuid",
            """{"display_name":"Public candidate","specialization_id":"backend-python-v1","allowed_work_modes":["remote"],"cities":[],"max_weekly_hours":40,"skills":[]}""",
            mapper.writeValueAsString(mapOf("channels" to listOf(mapOf("type" to "email", "value" to email, "enabled" to true)))),
            candidate.id,
        )
        val invitationId = UUID.randomUUID().toString()
        val grantId = UUID.randomUUID().toString()
        jdbc.update(
            """INSERT INTO invitations(id, company_id, candidate_id, version, state, snapshot, expires_at)
               VALUES (?::uuid, ?::uuid, ?::uuid, 1, 'accepted', '{}'::jsonb, now() + interval '7 days')""".trimIndent(),
            invitationId, employer.companyId, candidate.id,
        )
        jdbc.update(
            """INSERT INTO contact_grants(id, company_id, candidate_id, basis_invitation_id, basis_version)
               VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 1)""".trimIndent(),
            grantId, employer.companyId, candidate.id, invitationId,
        )
        jdbc.update("UPDATE invitations SET contact_grant_id=?::uuid WHERE id=?::uuid", grantId, invitationId)
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM contact_grants WHERE id=?::uuid AND revoked_at IS NULL",
            Int::class.java,
            grantId,
        ))

        val response = request(
            actor = employer,
            method = HttpMethod.GET,
            path = "/api/v1/candidates/${candidate.id}/profile.pdf",
        )
        assertEquals(HttpStatus.NOT_ACCEPTABLE, response.statusCode)
        assertEquals("VALIDATION_ERROR", response.body?.path("error")?.path("code")?.asText())
        assertEquals("private, no-store", response.headers.getFirst("Cache-Control"))
        assertFalse(response.body.toString().contains(email))
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM contact_access_events WHERE candidate_id=?::uuid",
            Int::class.java,
            candidate.id,
        ))
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM contact_grants WHERE id=?::uuid AND revoked_at IS NULL",
            Int::class.java,
            grantId,
        ))
        val payload = mapper.convertValue(response.body, Map::class.java) as Map<String, Any?>
        contracts.validate("ErrorEnvelope", payload)
    }

    @Test
    fun `csrf bootstrap creates an opaque session cookie`() {
        val response = http.getForEntity("http://localhost:$port/api/v1/auth/csrf", Map::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull((response.body?.get("data") as Map<*, *>)["csrf_token"])
        val cookie = response.headers["Set-Cookie"]?.singleOrNull()
        assertNotNull(cookie)
        check(cookie!!.contains("HttpOnly") && cookie.contains("SameSite=Lax"))

        val cookiePair = cookie.substringBefore(";")
        val headers = HttpHeaders().apply { set("Cookie", cookiePair) }
        val second = http.exchange(
            "http://localhost:$port/api/v1/auth/csrf",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
        assertEquals(
            (response.body?.get("data") as Map<*, *>)["csrf_token"],
            (second.body?.get("data") as Map<*, *>)["csrf_token"],
        )
    }

    @Test
    fun `request DTO validation rejects unknown fields`() {
        contracts.validate(
            "RegisterRequest",
            mapOf(
                "email" to "candidate@example.test",
                "password" to "DemoPassword123!",
                "account_type" to "candidate",
                "display_name" to "Candidate",
                "company_name" to null,
            ),
        )

        assertThrows(ApiException::class.java) {
            contracts.validate(
                "RegisterRequest",
                mapOf(
                    "email" to "candidate@example.test",
                    "password" to "DemoPassword123!",
                    "account_type" to "candidate",
                    "display_name" to "Candidate",
                    "company_name" to null,
                    "unexpected" to true,
                ),
            )
        }
    }

    @Test
    fun `v1 schema tables are migrated`() {
        val count = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'attempts'",
            Int::class.java,
        )
        assertEquals(1, count)
        assertEquals("fsp_test", jdbc.queryForObject("SELECT current_database()", String::class.java))
    }
}
