package ru.fsp.talent.qualifications

import org.springframework.stereotype.Service
import ru.fsp.talent.assessment.ASSESSMENT_BANK_VERSION
import ru.fsp.talent.assessment.ASSESSMENT_POLICY_VERSION
import ru.fsp.talent.assessment.ASSESSMENT_SPECIALIZATION
import ru.fsp.talent.core.Store

@Service
class QualificationPortfolioService(private val store: Store) {
    fun getOwn(userId: String): Map<String, Any?> {
        val items = store.rows(
            """SELECT q.specialization_id, q.grade, q.assessed_at, q.policy_version, q.bank_version,
                      q.data, a.id AS completed_attempt_id
               FROM qualifications q
               LEFT JOIN attempts a ON a.id = q.evidence_attempt_id
                   AND a.user_id = q.user_id
                   AND a.specialization_id = q.specialization_id
                   AND a.policy_version = q.policy_version
                   AND a.bank_version = q.bank_version
                   AND a.status = 'evaluated'
                   AND a.evaluated_at IS NOT NULL
                   AND a.result <> '{}'::jsonb
                   AND a.tasks->>'demo' = 'true'
               WHERE q.user_id = :user
               ORDER BY q.assessed_at DESC, q.specialization_id""".trimIndent(),
            mapOf("user" to userId),
        ).map(::portfolioItem)

        return mapOf(
            "items" to items,
            "available_specializations" to listOf(
                mapOf(
                    "id" to ASSESSMENT_SPECIALIZATION,
                    "label" to SPECIALIZATION_LABEL,
                    "grades" to listOf("junior", "middle"),
                ),
            ),
            "catalog_note" to CATALOG_NOTE,
        )
    }

    private fun portfolioItem(row: Map<String, Any?>): Map<String, Any?> {
        val specializationId = row.getValue("specialization_id").toString()
        val metadata = row["data"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val isReleasedEvidence = specializationId == ASSESSMENT_SPECIALIZATION &&
            row["completed_attempt_id"] != null &&
            row["policy_version"] == ASSESSMENT_POLICY_VERSION &&
            row["bank_version"] == ASSESSMENT_BANK_VERSION &&
            metadata["demo"] == true

        return mapOf(
            "specialization_id" to specializationId,
            "label" to (SPECIALIZATION_LABELS[specializationId] ?: "Специализация $specializationId"),
            "grade" to if (isReleasedEvidence) row["grade"]?.toString() else null,
            "assessed_at" to row.getValue("assessed_at").toString(),
            "policy_version" to if (isReleasedEvidence) row["policy_version"]?.toString() else null,
            "bank_version" to if (isReleasedEvidence) row["bank_version"]?.toString() else null,
            "demo" to (metadata["demo"] == true),
            "source" to "platform_assessment",
            "method_status" to if (isReleasedEvidence) "published_demo" else "unavailable",
        )
    }

    companion object {
        private const val SPECIALIZATION_LABEL = "Backend Python"
        private const val CATALOG_NOTE = "Текущий каталог включает только Backend Python. Опубликованная методика демонстрационная и не подтверждает профессиональную пригодность."
        private val SPECIALIZATION_LABELS = mapOf(ASSESSMENT_SPECIALIZATION to SPECIALIZATION_LABEL)
    }
}
