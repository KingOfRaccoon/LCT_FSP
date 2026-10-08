package ru.fsp.talent.matching

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class InterviewKitFactoryTest {
    private val mapper = ObjectMapper()
    private val templateFile = listOf(
        File("../docs/contracts/interview-kit.v1.json"),
        File("docs/contracts/interview-kit.v1.json"),
    ).first(File::isFile)
    private val template = mapper.readValue(templateFile, object : TypeReference<Map<String, Any?>>() {})

    @Test
    fun `common questions and grade rubrics are stable across candidate evidence`() {
        val need = need()
        val first = InterviewKitFactory.build(template, need, "candidate-a", fit("unknown"))
        val second = InterviewKitFactory.build(template, need, "candidate-b", fit("declared"))

        assertEquals(first["common_questions"], second["common_questions"])
        assertEquals(listOf("http-api", "python-basics"),
            (first["common_questions"] as List<Map<String, Any?>>).map { it["skill_id"] })
        assertEquals(InterviewKitFactory.TEMPLATE_VERSION, first["template_version"])
        assertEquals(InterviewKitFactory.NOT_ASSESSED,
            (first["common_questions"] as List<Map<String, Any?>>).first()["not_assessed_label"])
    }

    @Test
    fun `question links only role tasks for the same skill`() {
        val kit = InterviewKitFactory.build(template, need(), "candidate-a", fit("unknown"))
        val questions = kit["common_questions"] as List<Map<String, Any?>>
        assertEquals(listOf("22222222-2222-4222-8222-222222222222"), questions[0]["task_ids"])
        assertEquals(listOf("11111111-1111-4111-8111-111111111111"), questions[1]["task_ids"])
    }

    @Test
    fun `personal followups are capped and ordered required then preferred then unknown conditions`() {
        val fit = mapOf("checks" to listOf(
            check("workload", null, null, "unknown"),
            check("skill", "postgres", "preferred", "unknown"),
            check("skill", "python-basics", "required", "declared"),
            check("skill", "http-api", "required", "unknown"),
        ))
        val kit = InterviewKitFactory.build(template, need(), "candidate-a", fit)
        val followups = kit["personal_followups"] as List<Map<String, Any?>>
        assertEquals(3, followups.size)
        assertEquals(listOf("http-api", "python-basics", "postgres"), followups.map { it["skill_id"] })
        assertTrue(followups.all { it["evidence_status"] in setOf("unknown", "candidate_declared") })
    }

    private fun need() = mapOf(
        "id" to "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        "version" to 4,
        "target_grade" to "junior",
        "requirements" to listOf(
            mapOf("skill_id" to "python-basics", "importance" to "required"),
            mapOf("skill_id" to "http-api", "importance" to "preferred"),
        ),
        "role_tasks" to listOf(
            mapOf("id" to "22222222-2222-4222-8222-222222222222", "description" to "API endpoint", "skill_ids" to listOf("http-api")),
            mapOf("id" to "11111111-1111-4111-8111-111111111111", "description" to "Python service", "skill_ids" to listOf("python-basics")),
        ),
    )

    private fun fit(status: String) = mapOf("checks" to listOf(
        check("skill", "python-basics", "required", status),
        check("skill", "http-api", "preferred", "unknown"),
    ))

    private fun check(type: String, skill: String?, importance: String?, status: String) = mapOf<String, Any?>(
        "type" to type,
        "skill_id" to skill,
        "importance" to importance,
        "status" to status,
    )
}
