package ru.fsp.talent.proctoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase

class ProctoringConfigIntegrationTest : DbTestBase() {
    @Test
    fun `configuration requires an authenticated verified actor and exposes bounded contract`() {
        val anonymous = request(method = HttpMethod.GET, path = "/api/v1/proctoring/config")
        assertEquals(401, anonymous.statusCode.value())

        val candidate = seedActor()
        val response = request(candidate, HttpMethod.GET, "/api/v1/proctoring/config")
        assertEquals(200, response.statusCode.value())
        val data = response.body!!.path("data")
        assertEquals("1.0", data.path("consent_version").asText())
        assertEquals(4 * 1024 * 1024, data.path("max_chunk_bytes").asInt())
        assertEquals(256 * 1024 * 1024, data.path("max_session_bytes").asInt())
        assertEquals(7, data.path("retention_days").asInt())
        assertTrue(data.path("consent_document_sha256").asText().matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `employer cannot inspect candidate proctoring configuration`() {
        val employer = seedActor(role = "employer")
        val response = request(employer, HttpMethod.GET, "/api/v1/proctoring/config")
        assertEquals(403, response.statusCode.value())
    }
}
