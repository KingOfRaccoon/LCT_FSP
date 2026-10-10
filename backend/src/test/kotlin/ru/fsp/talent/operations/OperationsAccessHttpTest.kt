package ru.fsp.talent.operations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase

class OperationsAccessHttpTest : DbTestBase() {
    @Test
    fun `operations endpoints require a signed in operator`() {
        for (path in ENDPOINTS) {
            assertEquals(401, request(method = HttpMethod.GET, path = path).statusCode.value(), path)
        }
    }

    @Test
    fun `verified candidate and employer cannot read operations endpoints`() {
        val actors = listOf(seedActor("candidate"), seedActor("employer"))
        for (actor in actors) {
            for (path in ENDPOINTS) {
                assertEquals(403, request(actor, HttpMethod.GET, path).statusCode.value(), "${actor.role}: $path")
            }
        }
    }

    companion object {
        private val ENDPOINTS = listOf(
            "/api/v1/operations/summary",
            "/api/v1/operations/metrics",
        )
    }
}
