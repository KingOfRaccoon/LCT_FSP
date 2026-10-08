package ru.fsp.talent.matching

/** Pure, versioned matching rules. Input maps are policy snapshots, never public DTOs. */
object MatchingPolicy {
    const val SPECIALIZATION_ID = "backend-python-v1"
    const val POLICY_VERSION = "1.0.0"

    fun fspEvidenceOrdinal(value: Any?): Int = fspOrdinal(value)

    fun categoryEvidence(profile: Map<String, Any?>): String = when {
        profile["verified_grade"]?.toString() != null -> "confirmed"
        profile["declared_grade"]?.toString() != null -> "unconfirmed"
        else -> "unknown"
    }

    /** Explain the existing classification and hard conditions without introducing another rank. */
    fun explainFit(profile: Map<String, Any?>, need: Map<String, Any?>): Map<String, Any?> {
        val normalizedNeed = if (need.containsKey("requirements")) need else NeedService.needDto(need)
        val id = (profile["id"] ?: profile["user_id"])?.toString().orEmpty()
        val result = computeMatches(listOf(profile), normalizedNeed)
        val match = (result.groups.confirmed + result.groups.needsClarification + result.groups.needsConfirmation)
            .firstOrNull { it.candidateId == id }
        val requirements = (normalizedNeed["requirements"] as? Iterable<*>)?.mapNotNull { it.asStringMap() }.orEmpty()
        val testSkills = profile["test_evidenced_skills"].asStrings().toSet()
        val declaredSkills = profile["candidate_declared_skills"].asStrings().toSet()
        val checks = buildList {
            val specialization = profile["specialization_id"]?.toString()
            add(fitCheck(
                type = "specialization",
                expected = SPECIALIZATION_ID,
                observed = specialization,
                status = when {
                    specialization == null -> "unknown"
                    specialization == SPECIALIZATION_ID -> "met"
                    else -> "mismatch"
                },
                reason = when {
                    specialization == null -> "Специализация в профиле не указана"
                    specialization == SPECIALIZATION_ID -> "Специализация совпадает"
                    else -> "Специализация профиля не совпадает с ролью"
                },
            ))
            requirements.forEach { requirement ->
                val skillId = requirement["skill_id"]?.toString() ?: return@forEach
                val observed = when {
                    skillId in testSkills -> "Подтверждён тестом"
                    skillId in declaredSkills -> "Указан кандидатом"
                    else -> null
                }
                add(fitCheck(
                    type = "skill",
                    skillId = skillId,
                    importance = requirement["importance"]?.toString(),
                    status = when {
                        skillId in testSkills -> "met"
                        skillId in declaredSkills -> "declared"
                        else -> "unknown"
                    },
                    expected = if (requirement["importance"] == "required") "Обязательный навык" else "Желательный навык",
                    observed = observed,
                    reason = when {
                        skillId in testSkills -> "Есть подтверждение в тесте платформы"
                        skillId in declaredSkills -> "Декларация не заменяет тестовое подтверждение"
                        else -> "Подтверждение отсутствует; способность неизвестна"
                    },
                ))
            }

            val targetGrade = normalizedNeed["target_grade"]?.toString()
            val verifiedGrade = profile["verified_grade"]?.toString()
            val declaredGrade = profile["declared_grade"]?.toString()
            add(fitCheck("grade", expected = targetGrade.orEmpty(), observed = verifiedGrade,
                status = when {
                    verifiedGrade == null -> if (declaredGrade == null) "unknown" else "declared"
                    targetGrade == verifiedGrade -> "met"
                    else -> "mismatch"
                }, reason = when {
                    verifiedGrade == null -> if (declaredGrade == null) "Подтверждённая категория неизвестна" else "Категория только указана кандидатом"
                    targetGrade == verifiedGrade -> "Подтверждённая категория совпадает"
                    else -> "Подтверждённая категория не совпадает с потребностью"
                }))

            val mode = normalizedNeed["work_mode"]?.toString()
            val allowedModes = profile["allowed_work_modes"].asStrings()
            add(fitCheck("work_mode", expected = mode.orEmpty(), observed = allowedModes.takeIf { it.isNotEmpty() }?.joinToString(", "),
                status = when {
                    mode == null -> "unknown"
                    allowedModes.isEmpty() -> "unknown"
                    mode in allowedModes -> "met"
                    else -> "mismatch"
                }, reason = when {
                    mode == null || allowedModes.isEmpty() -> "Предпочтение по режиму работы неизвестно"
                    mode in allowedModes -> "Режим работы подтверждён профилем"
                    else -> "Указанный режим работы не входит в доступные"
                }))

            val city = normalizedNeed["city"]?.toString()?.trim()?.takeIf(String::isNotEmpty)
            val cities = profile["cities"].asStrings()
            if (mode != "remote" && city != null) {
                add(fitCheck("city", expected = city, observed = cities.takeIf { it.isNotEmpty() }?.joinToString(", "),
                    status = when {
                        cities.isEmpty() -> "unknown"
                        cities.any { it.equals(city, ignoreCase = true) } -> "met"
                        else -> "mismatch"
                    }, reason = when {
                        cities.isEmpty() -> "Город кандидата не указан"
                        cities.any { it.equals(city, ignoreCase = true) } -> "Город совпадает"
                        else -> "В профиле нет совпадающего города"
                    }))
            }

            val hours = normalizedNeed["workload_hours_per_week"].asInt()
            val maxHours = profile["max_weekly_hours"].asInt()
            add(fitCheck("workload", expected = hours?.let { "$it ч/нед." }.orEmpty(),
                observed = maxHours?.let { "до $it ч/нед." },
                status = when {
                    hours == null || maxHours == null -> "unknown"
                    maxHours >= hours -> "met"
                    else -> "mismatch"
                }, reason = when {
                    hours == null || maxHours == null -> "Доступная занятость неизвестна"
                    maxHours >= hours -> "Доступная занятость покрывает потребность"
                    else -> "Доступно меньше часов, чем требуется роли"
                }))
        }
        val mismatches = checks.filter { it["status"] == "mismatch" }
        val state = match?.state?.wireValue ?: "excluded"
        val summary = when {
            mismatches.isNotEmpty() -> "Не соответствует условиям: ${mismatches.joinToString { it["expected"].toString() }}"
            state == MatchState.CONFIRMED.wireValue -> "Категория, обязательные навыки и известные условия соответствуют потребности"
            state == MatchState.NEEDS_CLARIFICATION.wireValue -> "Есть совпадения; часть обязательных сведений требует уточнения"
            state == MatchState.NEEDS_CONFIRMATION.wireValue -> "Есть совпадения; категория ещё не подтверждена платформой"
            else -> "Профиль не проходит текущее правило подбора"
        }
        return mapOf("state" to state, "checks" to checks, "summary" to summary)
    }

    private fun fitCheck(
        type: String,
        expected: String,
        status: String,
        reason: String,
        observed: String? = null,
        skillId: String? = null,
        importance: String? = null,
    ): Map<String, Any?> = mapOf(
        "type" to type,
        "skill_id" to skillId,
        "importance" to importance,
        "status" to status,
        "expected" to expected,
        "observed" to observed,
        "reason" to reason,
    )

    fun computeMatches(
        profiles: List<Map<String, Any?>>,
        need: Map<String, Any?>,
        strictConfirmedOnly: Boolean = false,
    ): MatchingResult {
        val requirements = (need["requirements"] as? Iterable<*>)?.toList()
            .orEmpty().mapNotNull { it.asStringMap() }
        val required = requirements.filter { it["importance"] == "required" }.mapNotNull { it["skill_id"]?.toString() }.toSet()
        val preferred = requirements.filter { it["importance"] == "preferred" }.mapNotNull { it["skill_id"]?.toString() }.toSet()
        val targetGrade = need["target_grade"]?.toString()
        val workMode = need["work_mode"]?.toString()
        val city = need["city"]?.toString()?.trim()?.takeIf(String::isNotEmpty)
        val hours = need["workload_hours_per_week"].asInt()

        val grouped = profiles.mapNotNull { profile -> classify(profile, targetGrade, workMode, city, hours, required, preferred) }
            .groupBy { it.state }
        val confirmed = grouped[MatchState.CONFIRMED].orEmpty().sortedWith(
            compareByDescending<MatchCandidate> { it.testVerifiedPreferredCount }
                .thenByDescending { it.candidateDeclaredOnlyPreferredCount }
                .thenByDescending { it.fspOrdinal }
                .thenBy { it.candidateId },
        )
        val clarification = grouped[MatchState.NEEDS_CLARIFICATION].orEmpty().sortedWith(
            compareByDescending<MatchCandidate> { it.testVerifiedRequiredCount }
                .thenByDescending { it.testVerifiedPreferredCount }
                .thenByDescending { it.candidateDeclaredOnlyPreferredCount }
                .thenBy { it.candidateId },
        )
        val confirmation = grouped[MatchState.NEEDS_CONFIRMATION].orEmpty().sortedWith(
            compareByDescending<MatchCandidate> { it.testVerifiedRequiredCount }
                .thenByDescending { it.testVerifiedPreferredCount }
                .thenByDescending { it.candidateDeclaredOnlyPreferredCount }
                .thenBy { it.candidateId },
        )
        val eligibleIds = (confirmed + clarification + confirmation).mapTo(mutableSetOf()) { it.candidateId }
        val excluded = profiles.mapNotNull { profile ->
            (profile["id"] ?: profile["user_id"])?.toString()?.takeIf { it !in eligibleIds }
        }
        return MatchingResult(
            groups = MatchGroups(
                confirmed = confirmed,
                needsClarification = clarification,
                needsConfirmation = if (strictConfirmedOnly) emptyList() else confirmation,
            ),
            groupCounts = MatchGroupCounts(confirmed.size, clarification.size, confirmation.size),
            excludedCandidateIds = excluded,
        )
    }

    private fun classify(
        profile: Map<String, Any?>,
        targetGrade: String?,
        workMode: String?,
        city: String?,
        workloadHours: Int?,
        required: Set<String>,
        preferred: Set<String>,
    ): MatchCandidate? {
        val id = profile["id"]?.toString() ?: profile["user_id"]?.toString() ?: return null
        if (profile["visibility"] != "visible" || profile["email_confirmed"] != true) return null
        if (profile["specialization_id"]?.toString() != SPECIALIZATION_ID) return null

        val verifiedGrade = profile["verified_grade"]?.toString()
        if (verifiedGrade != null && targetGrade != null && verifiedGrade != targetGrade) return null

        val allowedModes = profile["allowed_work_modes"].asStrings()
        if (workMode != null && allowedModes.isNotEmpty() && workMode !in allowedModes) return null
        val candidateCities = profile["cities"].asStrings()
        if (workMode != "remote" && city != null && candidateCities.isNotEmpty() &&
            candidateCities.none { it.equals(city, ignoreCase = true) }
        ) return null
        val maxHours = profile["max_weekly_hours"].asInt()
        if (maxHours != null && workloadHours != null && maxHours < workloadHours) return null

        val testSkills = profile["test_evidenced_skills"].asStrings().toSet()
        val declaredSkills = profile["candidate_declared_skills"].asStrings().toSet()
        val verifiedRequiredCount = required.count(testSkills::contains)
        val verifiedPreferredCount = preferred.count(testSkills::contains)
        val declaredPreferredCount = preferred.count { it in declaredSkills && it !in testSkills }
        val rawFspResults = (profile["fsp_results"] as? Iterable<*>)?.mapNotNull { it.asStringMap() }.orEmpty()
        val demoEvidenceAllowed = profile["mock_evidence_demo"] as? Boolean ?: (profile["demo"] == true)
        val fspResults = if (demoEvidenceAllowed) rawFspResults.map { record ->
            if (record.containsKey("demo")) record else record + ("demo" to true)
        } else rawFspResults
        val fspOrdinal = fspOrdinal(fspResults)

        val noVerifiedGrade = verifiedGrade == null
        val unknownHardCondition = (workMode != null && allowedModes.isEmpty()) ||
            (workMode != "remote" && city != null && candidateCities.isEmpty()) ||
            (workloadHours != null && maxHours == null) ||
            verifiedRequiredCount < required.size
        val state = when {
            noVerifiedGrade -> MatchState.NEEDS_CONFIRMATION
            unknownHardCondition -> MatchState.NEEDS_CLARIFICATION
            else -> MatchState.CONFIRMED
        }
        val reasons = buildList {
            if (verifiedGrade != null) add("Подтверждённая категория: $verifiedGrade")
            else add("Категория ещё не подтверждена")
            if (required.isNotEmpty()) add("Обязательные навыки подтверждены тестами: $verifiedRequiredCount из ${required.size}")
            if (preferred.isNotEmpty()) add("Предпочтительные навыки подтверждены тестами: $verifiedPreferredCount из ${preferred.size}")
            if (declaredPreferredCount > 0) add("Дополнительно заявлено предпочтительных навыков: $declaredPreferredCount")
            if (fspOrdinal > 0) add(if (fspOrdinal == 2) "Есть демонстрационный связанный призовой результат ФСП" else "Есть демонстрационное связанное участие в ФСП")
            if (workloadHours != null && maxHours != null) add("Доступно до $maxHours ч/нед. при запросе $workloadHours ч/нед.")
        }
        return MatchCandidate(
            candidateId = id,
            profile = profile,
            state = state,
            rankReasons = reasons,
            testVerifiedRequiredCount = verifiedRequiredCount,
            testVerifiedPreferredCount = verifiedPreferredCount,
            candidateDeclaredOnlyPreferredCount = declaredPreferredCount,
            fspOrdinal = fspOrdinal,
        )
    }

    private fun fspOrdinal(value: Any?): Int {
        val records = (value as? Iterable<*>)?.mapNotNull { it.asStringMap() }.orEmpty()
        val seen = mutableSetOf<String>()
        return records.mapNotNull { record ->
            val sourceId = record["source_record_id"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (record["demo"] != true || record["valid"] != true || record["participant_linked"] != true) return@mapNotNull null
            val team = record["team"] == true
            if (team && record["membership_confirmed"] != true) return@mapNotNull null
            if (!seen.add(sourceId)) return@mapNotNull null
            when (record["result"]?.toString()) {
                "prize" -> 2
                "participant" -> 1
                else -> null
            }
        }.maxOrNull() ?: 0
    }

    private fun Any?.asInt(): Int? = when (this) {
        is Number -> toInt()
        else -> null
    }

    private fun Any?.asStrings(): List<String> = (this as? Iterable<*>)?.mapNotNull { it?.toString() }.orEmpty()

    private fun Any?.asStringMap(): Map<String, Any?>? = (this as? Map<*, *>)?.entries
        ?.associate { it.key.toString() to it.value }
}

enum class MatchState(val wireValue: String) {
    CONFIRMED("confirmed"),
    NEEDS_CLARIFICATION("needs_clarification"),
    NEEDS_CONFIRMATION("needs_confirmation"),
}

data class MatchCandidate(
    val candidateId: String,
    val profile: Map<String, Any?>,
    val state: MatchState,
    val rankReasons: List<String>,
    val testVerifiedRequiredCount: Int,
    val testVerifiedPreferredCount: Int,
    val candidateDeclaredOnlyPreferredCount: Int,
    val fspOrdinal: Int,
)

data class MatchGroups(
    val confirmed: List<MatchCandidate>,
    val needsClarification: List<MatchCandidate>,
    val needsConfirmation: List<MatchCandidate>,
)

data class MatchGroupCounts(val confirmed: Int, val needsClarification: Int, val needsConfirmation: Int)

data class MatchingResult(
    val groups: MatchGroups,
    val groupCounts: MatchGroupCounts,
    val excludedCandidateIds: List<String>,
)
