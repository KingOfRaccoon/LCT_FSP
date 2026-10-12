package ru.fsp.talent.operations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import ru.fsp.talent.core.DbTestBase
import java.util.UUID

@TestPropertySource(properties = [
    "fsp.monitoring.scrape-token=valid-local-scrape-token-with-at-least-32-characters",
])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OperationsScrapeTokenHttpTest : DbTestBase() {
    @Test
    fun `metrics endpoint accepts configured bearer token only for metrics`() {
        val metrics = exchange("/api/v1/operations/metrics", "Bearer $SCRAPE_TOKEN")
        assertEquals(200, metrics.statusCode.value())
        assertTrue(metrics.headers.contentType.toString().startsWith("text/plain"))
        assertTrue(metrics.body.orEmpty().contains("# TYPE fsp_http_requests_total counter"))

        val summary = exchange("/api/v1/operations/summary", "Bearer $SCRAPE_TOKEN")
        assertEquals(401, summary.statusCode.value())
    }

    @Test
    fun `metrics rejects missing and incorrect bearer token`() {
        assertEquals(401, exchange("/api/v1/operations/metrics", null).statusCode.value())
        assertEquals(401, exchange("/api/v1/operations/metrics", "Bearer wrong-token").statusCode.value())
        assertEquals(401, exchange("/api/v1/operations/metrics?token=$SCRAPE_TOKEN", null).statusCode.value())
    }

    @Test
    fun `metrics use matched route templates and omit path identifiers and query values`() {
        val pathId = UUID.randomUUID().toString()
        val queryPii = "private-${UUID.randomUUID()}@example.test"
        val ordinaryResponse = exchange("/api/v1/invitations/$pathId?email=$queryPii", null)
        assertEquals(401, ordinaryResponse.statusCode.value())

        val metrics = exchange("/api/v1/operations/metrics", "Bearer $SCRAPE_TOKEN").body.orEmpty()
        assertTrue(metrics.contains("route=\"/api/v1/invitations/{invitation_id}\""))
        assertFalse(metrics.contains(pathId))
        assertFalse(metrics.contains(queryPii))
    }

    private fun exchange(path: String, authorization: String?) = http.exchange(
        "http://localhost:$port$path",
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply {
            accept = listOf(MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON)
            authorization?.let { set(HttpHeaders.AUTHORIZATION, it) }
        }),
        String::class.java,
    )

    companion object {
        private const val SCRAPE_TOKEN = "valid-local-scrape-token-with-at-least-32-characters"
    }
}
