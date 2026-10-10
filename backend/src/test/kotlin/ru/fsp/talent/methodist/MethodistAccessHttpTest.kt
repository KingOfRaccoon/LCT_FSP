package ru.fsp.talent.methodist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase

class MethodistAccessHttpTest : DbTestBase() {
    @Test
    fun `unauthenticated request cannot read the methodist bank monitor`() {
        val response = request(
            method = HttpMethod.GET,
            path = "/api/v1/methodist/bank/monitor",
        )

        assertEquals(401, response.statusCode.value())
    }

    @Test
    fun `candidate without a provisioned methodist grant is forbidden`() {
        val candidate = seedActor("candidate")

        val response = request(
            actor = candidate,
            method = HttpMethod.GET,
            path = "/api/v1/methodist/bank/monitor",
        )

        assertEquals(403, response.statusCode.value())
    }

    @Test
    fun `employer without a provisioned methodist grant is forbidden`() {
        val employer = seedActor("employer")

        val response = request(
            actor = employer,
            method = HttpMethod.GET,
            path = "/api/v1/methodist/drafts",
        )

        assertEquals(403, response.statusCode.value())
    }
}
