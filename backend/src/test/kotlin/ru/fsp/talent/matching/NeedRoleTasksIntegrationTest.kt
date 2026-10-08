package ru.fsp.talent.matching

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.DbTestBase
import java.util.UUID

class NeedRoleTasksIntegrationTest : DbTestBase() {
    @Autowired
    private lateinit var needs: NeedService

    @Test
    fun `role tasks default empty and adding them to confirmed need returns it to draft`() {
        val owner = owner()
        val created = needs.create(owner, createBody())
        assertEquals(emptyList<Any>(), created["role_tasks"])

        val confirmed = needs.confirm(owner, created.getValue("id").toString(), mapOf(
            "expected_version" to 1,
            "confirmed_skill_ids" to listOf("python-basics"),
        ))
        assertEquals("active", confirmed["status"])

        val taskId = UUID.randomUUID().toString()
        val patched = needs.patch(owner, created.getValue("id").toString(), mapOf(
            "expected_version" to 2,
            "role_tasks" to listOf(mapOf(
                "id" to taskId,
                "description" to "Implement and test a Python API endpoint",
                "skill_ids" to listOf("python-basics"),
            )),
        ))
        assertEquals("draft", patched["status"])
        assertEquals(3, patched["version"])
        assertEquals(taskId, (patched["role_tasks"] as List<Map<String, Any?>>).single()["id"])

        val preserved = needs.patch(owner, created.getValue("id").toString(), mapOf("expected_version" to 3))
        assertEquals(taskId, (preserved["role_tasks"] as List<Map<String, Any?>>).single()["id"])
        val cleared = needs.patch(owner, created.getValue("id").toString(), mapOf(
            "expected_version" to 4,
            "role_tasks" to emptyList<Any>(),
        ))
        assertEquals(emptyList<Any>(), cleared["role_tasks"])
    }

    @Test
    fun `role task cannot reference a skill absent from the need requirements`() {
        val owner = owner()
        val created = needs.create(owner, createBody())
        val error = assertThrows(ApiException::class.java) {
            needs.patch(owner, created.getValue("id").toString(), mapOf(
                "expected_version" to 1,
                "role_tasks" to listOf(mapOf(
                    "id" to UUID.randomUUID().toString(),
                    "description" to "Run a PostgreSQL migration",
                    "skill_ids" to listOf("postgres"),
                )),
            ))
        }
        assertEquals("VALIDATION_ERROR", error.code)
    }

    private fun owner(): Actor {
        val testActor = seedActor(role = "employer")
        return Actor(testActor.id, testActor.email, testActor.role, testActor.companyId,
            UUID.randomUUID().toString(), emailVerified = true)
    }

    private fun createBody() = mapOf<String, Any?>(
        "title" to "Python backend developer",
        "description" to "Build and maintain backend services.",
        "specialization_id" to MatchingPolicy.SPECIALIZATION_ID,
        "target_grade" to "junior",
        "work_mode" to "remote",
        "city" to null,
        "workload_hours_per_week" to 40,
        "salary" to mapOf(
            "min_kopecks" to 10_000_000,
            "max_kopecks" to 20_000_000,
            "currency" to "RUB",
            "period" to "month",
            "basis" to "gross",
            "workload_type" to "full_time",
            "hours_per_week" to 40,
        ),
        "requirements" to listOf(mapOf("skill_id" to "python-basics", "importance" to "required")),
    )
}
