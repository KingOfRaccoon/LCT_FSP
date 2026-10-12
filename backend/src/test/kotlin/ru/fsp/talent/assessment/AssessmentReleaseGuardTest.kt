package ru.fsp.talent.assessment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import java.util.UUID

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "fsp.demo-mode=false",
        "fsp.assessment.real-grade-enabled=true",
    ],
)
class AssessmentReleaseGuardTest : DbTestBase() {
    @Test
    fun `real grade route stays blocked while release review and validation are unperformed`() {
        val candidate = seedActor()
        val response = request(
            actor = candidate,
            method = HttpMethod.POST,
            path = "/api/v1/attempts",
            body = mapOf(
                "specialization_id" to "backend-python-v1",
                "requested_grade" to "junior",
                "policy_id" to "backend-python-policy-v1",
                "policy_version" to "1.0.0",
            ),
            idempotencyKey = UUID.randomUUID().toString(),
        )

        assertEquals(503, response.statusCode.value())
        assertEquals("GRADE_RELEASE_BLOCKED", response.body!!.path("error").path("code").asText())
    }
}
