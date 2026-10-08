package ru.fsp.talent.matching

import java.nio.charset.StandardCharsets
import java.util.UUID

/** Builds the same reviewed core questions for every candidate on a need/version. */
object InterviewKitFactory {
    const val TEMPLATE_VERSION = "interview-kit-v1.0"
    const val NOT_ASSESSED = "Не оценено"
    const val DISCLAIMER = "Проверьте вопросы перед интервью; оценки 1–3 не являются грейдом платформы."

    fun build(
        template: Map<String, Any?>,
        need: Map<String, Any?>,
        candidateId: String,
        fit: Map<String, Any?>,
    ): Map<String, Any?> {
        val templateVersion = template["version"]?.toString() ?: TEMPLATE_VERSION
        val skills = (template["skills"] as? Iterable<*>)?.mapNotNull { it.asOptionalMap() }
            .orEmpty().associateBy { it["skill_id"]?.toString() }
        val rubricByGrade = template["rubrics_by_grade"].asMap()
        val gradeRubric = (rubricByGrade[need["target_grade"]?.toString()] as? Iterable<*>)
            ?.mapNotNull { it.asOptionalMap() }.orEmpty().associateBy { (it["level"] as? Number)?.toInt() }
        val roleTasks = (need["role_tasks"] as? Iterable<*>)?.mapNotNull { it.asOptionalMap() }.orEmpty()
        val requirements = (need["requirements"] as? Iterable<*>)?.mapNotNull { it.asOptionalMap() }
            .orEmpty().sortedBy { it["skill_id"]?.toString().orEmpty() }
        val needId = need["id"]?.toString().orEmpty()
        val needVersion = need["version"]?.toString().orEmpty()
        val questions = requirements.map { requirement ->
            val skillId = requirement["skill_id"]?.toString().orEmpty()
            val source = skills[skillId] ?: error("Interview template is missing skill $skillId")
            val linkedTasks = roleTasks.filter { task ->
                (task["skill_ids"] as? Iterable<*>)?.any { it?.toString() == skillId } == true
            }
            val taskIds = linkedTasks.mapNotNull { it["id"]?.toString() }.distinct().sorted()
            val criteria = (1..3).map { level ->
                gradeRubric[level] ?: error("Interview template is missing $level-level rubric for ${need["target_grade"]}")
            }
            mapOf(
                "id" to stableId("common|$templateVersion|$needId|$needVersion|$skillId"),
                "task_id" to taskIds.firstOrNull(),
                "question" to source["question"],
                "criteria" to criteria,
                "not_assessed_label" to NOT_ASSESSED,
                "skill_id" to skillId,
                "importance" to requirement["importance"],
                "task_ids" to taskIds,
            )
        }
        return mapOf(
            "common_questions" to questions,
            "personal_followups" to followups(fit, candidateId, needId, needVersion),
            "disclaimer" to (template["disclaimer"]?.toString() ?: DISCLAIMER),
            "template_version" to templateVersion,
        )
    }

    private fun followups(fit: Map<String, Any?>, candidateId: String, needId: String, needVersion: String): List<Map<String, Any?>> {
        val checks = (fit["checks"] as? Iterable<*>)?.mapNotNull { it.asOptionalMap() }.orEmpty()
        val skillGaps = checks.filter { it["type"] == "skill" && it["status"] in setOf("declared", "unknown") }
            .sortedWith(compareBy<Map<String, Any?>> {
                if (it["importance"] == "required") 0 else 1
            }.thenBy { it["skill_id"]?.toString().orEmpty() })
        val conditionGaps = checks.filter {
            it["type"] in CONDITION_TYPES && it["status"] in setOf("declared", "unknown")
        }.sortedWith(compareBy<Map<String, Any?>> { it["skill_id"]?.toString().orEmpty() }
            .thenBy { it["type"]?.toString().orEmpty() })
        val selected = (skillGaps + conditionGaps).take(3)
        return selected.map { check ->
            val skillId = check["skill_id"]?.toString()
            val conditionType = check["type"]?.toString()?.takeIf { it in CONDITION_TYPES }
            val declared = check["status"] == "declared"
            val prompt = when {
                skillId != null && declared -> "Вы указали навык $skillId. Расскажите о конкретной задаче, где применяли его, и как проверили результат."
                skillId != null -> "Расскажите о конкретной задаче с навыком $skillId и о способе проверки результата."
                conditionType == "grade" -> "Какой уровень задач и ответственности вы сейчас рассматриваете?"
                conditionType == "work_mode" -> "Какой формат работы вам подходит?"
                conditionType == "city" -> "В каком городе вы готовы работать?"
                else -> "Сколько часов в неделю вы готовы выделять на эту роль?"
            }
            mapOf(
                "id" to stableId("followup|$needId|$needVersion|$candidateId|${skillId.orEmpty()}|${conditionType.orEmpty()}"),
                "skill_id" to skillId,
                "evidence_status" to if (declared) "candidate_declared" else "unknown",
                "prompt" to prompt,
                "condition_type" to conditionType,
            )
        }
    }

    private fun stableId(seed: String): String = UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()

    private fun Any?.asOptionalMap(): Map<String, Any?>? = (this as? Map<*, *>)?.entries
        ?.associate { it.key.toString() to it.value }

    private fun Any?.asMap(): Map<String, Any?> = asOptionalMap() ?: emptyMap()

    private val CONDITION_TYPES = setOf("grade", "work_mode", "city", "workload")
}
