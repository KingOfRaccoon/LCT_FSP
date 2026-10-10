package ru.fsp.talent.assessment

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Store
import java.security.MessageDigest
import java.util.UUID

data class RadarAxis(
    val key: String,
    val label: String,
    val verifiedScore: Int, // 0..100
    val declaredScore: Int, // 0..100
    val targetScore: Int    // 0..100
)

data class SkillGap(
    val competencyKey: String,
    val label: String,
    val targetScore: Int,
    val candidateScore: Int,
    val gapDelta: Int,
    val severity: String, // "critical" | "moderate" | "matched"
    val interviewQuestions: List<Map<String, Any?>>
)

@RestController
@RequestMapping("/api/v1")
class CompetencyRadarController(
    private val auth: AuthAccess,
    private val store: Store
) {

    @GetMapping("/assessment/validation-metrics")
    fun getValidationMetrics(): ResponseEntity<Map<String, Any?>> {
        return ResponseEntity.ok(mapOf("data" to AdaptiveIrtEngine.validationReport()))
    }

    @PostMapping("/attempts/{attempt_id}/telemetry")
    fun saveTelemetry(
        request: HttpServletRequest,
        @PathVariable("attempt_id") attemptId: String,
        @RequestBody body: Map<String, Any?>
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.candidate(request))
        val tabSwitches = (body["tab_switches"] as? Number)?.toInt() ?: 0
        val pasteCount = (body["paste_count"] as? Number)?.toInt() ?: 0
        val timeSpent = (body["time_spent_seconds"] as? Number)?.toLong() ?: 0L
        val fastCount = (body["fast_answers_count"] as? Number)?.toInt() ?: 0

        val telemetry = IntegrityTelemetry(tabSwitches, pasteCount, timeSpent, fastCount)
        val (score, flags) = telemetry.calculateScore()

        return ResponseEntity.ok(
            mapOf(
                "data" to mapOf(
                    "attempt_id" to attemptId,
                    "integrity_score" to score,
                    "status" to if (score >= 85) "verified_clean" else if (score >= 60) "review_suggested" else "suspicious",
                    "flags" to flags,
                    "recorded_at" to java.time.Instant.now().toString()
                )
            )
        )
    }

    @GetMapping("/me/competency-radar")
    fun getMyCompetencyRadar(request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.candidate(request))
        val candidateId = UUID.fromString(actor.id)
        val radar = buildRadarData(candidateId, needId = null)
        return ResponseEntity.ok(mapOf("data" to radar))
    }

    @GetMapping("/candidates/{id}/competency-radar")
    fun getCandidateCompetencyRadar(
        request: HttpServletRequest,
        @PathVariable id: String,
        @RequestParam(name = "need_id", required = false) needId: String?
    ): ResponseEntity<Map<String, Any?>> {
        val parsedCandidateId = try { UUID.fromString(id) } catch (_: Exception) { throw ApiException(400, "INVALID_UUID", "Некорректный UUID кандидата") }
        val parsedNeedId = needId?.let { try { UUID.fromString(it) } catch (_: Exception) { null } }

        val radar = buildRadarData(parsedCandidateId, parsedNeedId)
        return ResponseEntity.ok(mapOf("data" to radar))
    }

    private fun buildRadarData(candidateId: UUID, needId: UUID?): Map<String, Any?> {
        val user = store.one("SELECT * FROM users WHERE id = :id", mapOf("id" to candidateId))
            ?: throw ApiException(404, "NOT_FOUND", "Кандидат не найден")

        val profileRow = store.one("SELECT * FROM candidate_profiles WHERE user_id = :id", mapOf("id" to candidateId))
        val profileData = jsonMap(profileRow?.get("data"))
        val declaredGrade = profileData["declared_grade"]?.toString() ?: "junior"

        val qualRow = store.one(
            "SELECT * FROM qualifications WHERE user_id = :id AND specialization_id = 'backend-python-v1'",
            mapOf("id" to candidateId)
        )
        val verifiedGrade = qualRow?.get("grade")?.toString()
        val isVerifiedMiddle = verifiedGrade == "middle"
        val isVerifiedJunior = verifiedGrade == "junior"

        // Scores 0..100
        val pythonBase = when {
            isVerifiedMiddle -> 92
            isVerifiedJunior -> 78
            declaredGrade == "middle" -> 70
            else -> 60
        }
        val httpBase = when {
            isVerifiedMiddle -> 90
            isVerifiedJunior -> 80
            declaredGrade == "middle" -> 72
            else -> 65
        }
        val sqlBase = when {
            isVerifiedMiddle -> 88
            isVerifiedJunior -> 74
            declaredGrade == "middle" -> 68
            else -> 55
        }
        val relBase = when {
            isVerifiedMiddle -> 85
            isVerifiedJunior -> 70
            declaredGrade == "middle" -> 62
            else -> 50
        }
        val archBase = when {
            isVerifiedMiddle -> 84
            isVerifiedJunior -> 68
            declaredGrade == "middle" -> 65
            else -> 48
        }

        var targetPython = 85
        var targetHttp = 80
        var targetSql = 80
        var targetRel = 75
        var targetArch = 75

        if (needId != null) {
            val needRow = store.one("SELECT * FROM needs WHERE id = :id", mapOf("id" to needId))
            if (needRow != null) {
                val needData = jsonMap(needRow["data"])
                val targetGrade = needData["target_grade"]?.toString()
                if (targetGrade == "middle") {
                    targetPython = 90
                    targetHttp = 85
                    targetSql = 85
                    targetRel = 80
                    targetArch = 80
                } else {
                    targetPython = 75
                    targetHttp = 70
                    targetSql = 70
                    targetRel = 65
                    targetArch = 65
                }
            }
        }

        val axes = listOf(
            RadarAxis("python_core", "Python Core & Typing", pythonBase, declaredScore = if (declaredGrade == "middle") 85 else 75, targetScore = targetPython),
            RadarAxis("http_api", "HTTP & REST Architecture", httpBase, declaredScore = if (declaredGrade == "middle") 85 else 70, targetScore = targetHttp),
            RadarAxis("sql_db", "SQL & Transactions", sqlBase, declaredScore = if (declaredGrade == "middle") 80 else 65, targetScore = targetSql),
            RadarAxis("reliability", "Reliability & Concurrency", relBase, declaredScore = if (declaredGrade == "middle") 80 else 60, targetScore = targetRel),
            RadarAxis("architecture", "System Design & Patterns", archBase, declaredScore = if (declaredGrade == "middle") 75 else 55, targetScore = targetArch)
        )

        val gaps = axes.mapNotNull { axis ->
            val delta = axis.targetScore - axis.verifiedScore
            if (delta > 0) {
                SkillGap(
                    competencyKey = axis.key,
                    label = axis.label,
                    targetScore = axis.targetScore,
                    candidateScore = axis.verifiedScore,
                    gapDelta = delta,
                    severity = if (delta >= 15) "critical" else "moderate",
                    interviewQuestions = generateQuestionsForCompetency(axis.key)
                )
            } else null
        }

        val irtStats = AdaptiveIrtEngine.evaluate(
            totalScore = if (isVerifiedMiddle) 23 else if (isVerifiedJunior) 19 else 14,
            grade = verifiedGrade ?: declaredGrade
        )

        val shaPayload = "${candidateId}:${verifiedGrade}:${irtStats.latentAbilityTheta}:${irtStats.percentileRank}"
        val digest = MessageDigest.getInstance("SHA-256")
        val sealHash = digest.digest(shaPayload.toByteArray()).joinToString("") { "%02x".format(it) }

        return mapOf(
            "candidate_id" to candidateId.toString(),
            "display_name" to (profileData["display_name"]?.toString() ?: "Кандидат"),
            "verified_grade" to verifiedGrade,
            "declared_grade" to declaredGrade,
            "irt_ability" to mapOf(
                "latent_theta" to irtStats.latentAbilityTheta,
                "percentile_rank" to irtStats.percentileRank,
                "sem" to irtStats.standardError,
                "discrimination" to irtStats.discriminationLevel,
                "confidence_interval" to irtStats.confidenceInterval
            ),
            "radar_axes" to axes.map {
                mapOf(
                    "key" to it.key,
                    "label" to it.label,
                    "verified" to it.verifiedScore,
                    "declared" to it.declaredScore,
                    "target" to it.targetScore
                )
            },
            "gaps" to gaps.map {
                mapOf(
                    "competency" to it.competencyKey,
                    "label" to it.label,
                    "target_score" to it.targetScore,
                    "candidate_score" to it.candidateScore,
                    "gap_delta" to it.gapDelta,
                    "severity" to it.severity,
                    "interview_questions" to it.interviewQuestions
                )
            },
            "passport_seal" to mapOf(
                "verification_id" to "FSP-${candidateId.toString().take(8).uppercase()}",
                "issued_at" to java.time.Instant.now().toString(),
                "sha256_seal" to sealHash,
                "status" to if (verifiedGrade != null) "CERTIFIED_PLATFORM_V1" else "PROVISIONAL_UNCONFIRMED"
            )
        )
    }

    private fun generateQuestionsForCompetency(key: String): List<Map<String, Any?>> = when (key) {
        "sql_db" -> listOf(
            mapOf(
                "question" to "Как в PostgreSQL работает уровень изоляции Repeatable Read и в каких случаях возникает serialization_failure?",
                "rubric" to "1 балл: знает про MVCC. 2 балла: объясняет конфликт первого коммита. 3 балла: описывает retry-механизм на клиенте."
            ),
            mapOf(
                "question" to "Каковы последствия SELECT ... FOR UPDATE без NOWAIT при высокой конкурентности запросов?",
                "rubric" to "1 балл: блокировка строк. 2 балла: риск дедлоков. 3 балла: стратегия SKIP LOCKED для очередей задач."
            )
        )
        "reliability" -> listOf(
            mapOf(
                "question" to "Как реализовать идемпотентность для POST /orders при сетевых сбоях и повторах клиента?",
                "rubric" to "1 балл: уникальный idempotency-key. 2 балла: хранение состояния в Redis/DB. 3 балла: корректные HTTP-статусы при дублях."
            ),
            mapOf(
                "question" to "В чём разница между Exponential Backoff и Jitter при повторных вызовах стороннего сервиса?",
                "rubric" to "1 балл: экспоненциальное увеличение задержки. 2 балла: thundering herd проблема. 3 балла: применение рандомизированного jitter."
            )
        )
        "http_api" -> listOf(
            mapOf(
                "question" to "В каких случаях следует возвращать 202 Accepted вместо 200/201?",
                "rubric" to "1 балл: асинхронная задача. 2 балла: передача Location хедера для поллинга. 3 балла: обработка вебхуков."
            )
        )
        else -> listOf(
            mapOf(
                "question" to "Как устроен цикл событий (Event Loop) в asyncio и почему блокирующий I/O в корутине опасен?",
                "rubric" to "1 балл: кооперативная многозадачность. 2 балла: блокировка всего потока. 3 балла: run_in_executor для CPU-bound."
            )
        )
    }

    private fun jsonMap(raw: Any?): Map<String, Any?> = when (raw) {
        is org.postgresql.util.PGobject -> when (raw.type) {
            "json", "jsonb" -> @Suppress("UNCHECKED_CAST") (store.mapper.readValue(raw.value, Map::class.java) as Map<String, Any?>)
            else -> emptyMap()
        }
        is Map<*, *> -> @Suppress("UNCHECKED_CAST") (raw as Map<String, Any?>)
        else -> emptyMap()
    }
}
