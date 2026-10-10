package ru.fsp.talent.profiles

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import java.util.UUID

class SalaryPreferenceIntegrationTest : DbTestBase() {
    @Test
    fun `optional expectations are saved as declaration without publishing or changing qualifications`() {
        val candidate = seedActor()
        val salary = mapOf("min_kopecks" to 10000000, "max_kopecks" to 15000000,
            "currency" to "RUB", "period" to "month", "basis" to "gross",
            "workload_type" to "full_time", "hours_per_week" to 40)
        val saved = request(candidate, HttpMethod.PATCH, "/api/v1/me/profile",
            mapOf("expected_version" to 1, "expected_salary" to salary), UUID.randomUUID().toString())
        assertEquals(200, saved.statusCode.value(), saved.body.toString())
        assertEquals(10000000, saved.body!!.path("data").path("expected_salary").path("min_kopecks").asInt())
        assertEquals("hidden", saved.body!!.path("data").path("visibility").asText())
        assertTrue(saved.body!!.path("data").path("verified_grade").isNull)
        val cleared = request(candidate, HttpMethod.PATCH, "/api/v1/me/profile",
            mapOf("expected_version" to 2, "expected_salary" to null), UUID.randomUUID().toString())
        assertEquals(200, cleared.statusCode.value(), cleared.body.toString())
        assertTrue(cleared.body!!.path("data").path("expected_salary").isNull)
    }

    @Test
    fun `expectations reject reversed and inconsistent workload ranges`() {
        val candidate = seedActor()
        for (salary in listOf(
            mapOf("min_kopecks" to 200, "max_kopecks" to 100, "hours_per_week" to 40, "workload_type" to "full_time"),
            mapOf("min_kopecks" to 100, "max_kopecks" to 200, "hours_per_week" to 20, "workload_type" to "full_time")
        )) {
            val response = request(candidate, HttpMethod.PATCH, "/api/v1/me/profile", mapOf(
                "expected_version" to 1, "expected_salary" to (salary + mapOf("currency" to "RUB", "period" to "month", "basis" to "net"))
            ), UUID.randomUUID().toString())
            assertEquals(400, response.statusCode.value(), response.body.toString())
        }
    }
}
