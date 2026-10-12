package ru.fsp.talent.core

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ProductionGuardTest {
    private val validProperties = arrayOf(
        "spring.profiles.active=production",
        "fsp.demo-mode=false",
        "fsp.seed-demo=false",
        "fsp.session-secret=L8vQ2eR7mX4pN9zT6cW1bH5kJ3sD0aF2",
        "fsp.session-cookie-secure=true",
        "fsp.public-origin=https://talent.example.org",
        "spring.datasource.password=production-database-password",
    )

    @Test
    fun `production accepts secure non-demo configuration`() {
        runner().withPropertyValues(*validProperties).run { context ->
            assertTrue(context.startupFailure == null)
        }
    }

    @Test
    fun `production rejects unsafe properties with field names and no values`() {
        val cases = listOf(
            "fsp.demo-mode=true" to "fsp.demo-mode",
            "fsp.seed-demo=true" to "fsp.seed-demo",
            "spring.flyway.enabled=true" to "spring.flyway.enabled",
            "fsp.session-secret=short" to "fsp.session-secret",
            "fsp.session-secret=${"a".repeat(48)}" to "fsp.session-secret",
            "fsp.session-secret=${"0123456789abcdef".repeat(3)}" to "fsp.session-secret",
            "fsp.session-secret=local-demo-session-secret-change-before-deploy-2026" to "fsp.session-secret",
            "fsp.session-cookie-secure=false" to "fsp.session-cookie-secure",
            "fsp.public-origin=http://talent.example.org" to "fsp.public-origin",
            "fsp.public-origin=https://talent.example.org/app" to "fsp.public-origin",
            "fsp.public-origin=https://127.0.0.1" to "fsp.public-origin",
            "spring.datasource.password=DemoPassword123!" to "spring.datasource.password",
            "spring.datasource.password=" to "spring.datasource.password",
        )
        cases.forEach { (unsafe, field) ->
            runner().withPropertyValues(*(validProperties + unsafe)).run { context ->
                assertFalse(context.startupFailure == null, unsafe)
                val messages = mutableListOf<String>()
                var cause = context.startupFailure
                while (cause != null) {
                    cause.message?.let(messages::add)
                    cause = cause.cause
                }
                val message = messages.joinToString(" ")
                assertTrue(message.contains(field), message)
                val rejectedValue = unsafe.substringAfter('=')
                if (rejectedValue.isNotEmpty()) assertFalse(message.contains(rejectedValue), message)
            }
        }
    }

    @Test
    fun `test profile bypasses production-only guard`() {
        runner().withPropertyValues("spring.profiles.active=test", "fsp.demo-mode=true").run { context ->
            assertTrue(context.startupFailure == null)
        }
    }

    @Test
    fun `readiness reports database health without exposing driver errors`() {
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        `when`(jdbc.queryForObject("SELECT 1", Int::class.java)).thenReturn(1)
        val controller = HealthController(ObjectMapper(), jdbc)

        assertEquals(200, controller.ready().statusCode.value())
        `when`(jdbc.queryForObject("SELECT 1", Int::class.java)).thenThrow(IllegalStateException("password=secret"))
        val failed = controller.ready()
        assertEquals(503, failed.statusCode.value())
        assertFalse(failed.body.toString().contains("secret"))
        assertEquals("ok", controller.health()["data"].let { (it as Map<*, *>)["status"] })
    }

    @Test
    fun `API responses include browser security headers and HSTS only for HTTPS`() {
        val filter = ApiResponseHeadersFilter()
        val http = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", "/api/v1/health"), http, MockFilterChain())
        assertEquals("nosniff", http.getHeader("X-Content-Type-Options"))
        assertEquals("DENY", http.getHeader("X-Frame-Options"))
        assertEquals("strict-origin-when-cross-origin", http.getHeader("Referrer-Policy"))
        assertTrue(http.getHeader("Content-Security-Policy")?.contains("default-src 'self'") == true)
        assertEquals(null, http.getHeader("Strict-Transport-Security"))

        val https = MockHttpServletRequest("GET", "/api/v1/health").apply { isSecure = true }
        val secure = MockHttpServletResponse()
        filter.doFilter(https, secure, MockFilterChain())
        assertEquals("max-age=31536000", secure.getHeader("Strict-Transport-Security"))
    }

    private fun runner() = ApplicationContextRunner()
        .withUserConfiguration(ProductionGuardConfiguration::class.java)
}
