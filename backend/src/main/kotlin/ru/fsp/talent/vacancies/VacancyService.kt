package ru.fsp.talent.vacancies

import org.springframework.stereotype.Service
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.Store
import ru.fsp.talent.consents.ConsentService
import ru.fsp.talent.invitations.ContactRules
import ru.fsp.talent.invitations.listValue
import ru.fsp.talent.invitations.objectValue
import java.util.UUID

@Service
class VacancyService(private val store: Store, private val consents: ConsentService, private val companyVerification: ru.fsp.talent.companyverification.CompanyVerificationService) {

    fun listVacancies(
        specializationId: String?,
        grade: String?,
        limit: Int = 20,
        offset: Int = 0
    ): List<Map<String, Any?>> {
        val query = StringBuilder(
            """
            SELECT v.id, v.need_id, v.company_id, v.title, v.description,
                   v.specialization_id, v.target_grade, v.work_mode, v.city,
                   v.workload_hours_per_week, v.salary_min_kopecks, v.salary_max_kopecks,
                   v.salary_basis, v.status, v.created_at, v.updated_at,
                   c.data->>'name' as company_name,
                   c.data->>'industry' as company_industry
            FROM public_vacancies v
            JOIN companies c ON c.id = v.company_id
            WHERE v.status = 'active'
            """.trimIndent()
        )
        val params = mutableMapOf<String, Any?>("limit" to limit, "offset" to offset)
        if (!specializationId.isNullOrBlank()) {
            query.append(" AND v.specialization_id = :specializationId")
            params["specializationId"] = specializationId
        }
        if (!grade.isNullOrBlank()) {
            query.append(" AND v.target_grade = :grade")
            params["grade"] = grade
        }
        query.append(" ORDER BY v.created_at DESC LIMIT :limit OFFSET :offset")

        return store.rows(query.toString(), params).map { formatVacancy(it) }
    }

    fun getVacancy(id: String): Map<String, Any?> {
        val row = store.one(
            """
            SELECT v.*, c.data->>'name' as company_name, c.data->>'industry' as company_industry,
                   c.data->>'description' as company_description
            FROM public_vacancies v
            JOIN companies c ON c.id = v.company_id
            WHERE v.id = :id
            """.trimIndent(),
            mapOf("id" to parseUuid(id))
        ) ?: throw ApiException(404, "NOT_FOUND", "Вакансия не найдена")
        return formatVacancy(row)
    }

    fun publishVacancy(actor: Actor, body: Map<String, Any?>): Map<String, Any?> {
        val companyId = parseUuid(actor.companyId ?: throw ApiException(403, "FORBIDDEN", "Только работодатель может публиковать вакансии"))
        val requiredFields = setOf(
            "need_id", "title", "description", "specialization_id", "target_grade", "work_mode", "city",
            "workload_hours_per_week", "salary_min_kopecks", "salary_max_kopecks", "salary_basis",
        )
        val missingField = requiredFields.firstOrNull { it !in body }
        if (missingField != null) {
            throw ApiException(400, "VALIDATION_ERROR", "$missingField обязателен", missingField)
        }
        val unknownField = body.keys.firstOrNull { it !in requiredFields }
        if (unknownField != null) {
            throw ApiException(400, "VALIDATION_ERROR", "Неизвестное поле вакансии", unknownField)
        }
        val rawNeedId = (body["need_id"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw ApiException(400, "VALIDATION_ERROR", "need_id обязателен", "need_id")
        val needId = parseUuid(rawNeedId).toString()
        val suppliedTitle = (body["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 160 }
            ?: throw ApiException(400, "VALIDATION_ERROR", "title должен содержать от 1 до 160 символов", "title")
        val suppliedDescription = (body["description"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 5_000 }
            ?: throw ApiException(400, "VALIDATION_ERROR", "description должен содержать от 1 до 5000 символов", "description")
        val suppliedSpecialization = body["specialization_id"] as? String
            ?: throw ApiException(400, "VALIDATION_ERROR", "specialization_id должен быть строкой", "specialization_id")
        val suppliedGrade = body["target_grade"] as? String
        if (suppliedGrade !in setOf("junior", "middle", "senior")) {
            throw ApiException(400, "VALIDATION_ERROR", "Недопустимый грейд", "target_grade")
        }
        val suppliedWorkMode = body["work_mode"] as? String
        if (suppliedWorkMode !in setOf("remote", "hybrid", "office")) {
            throw ApiException(400, "VALIDATION_ERROR", "Недопустимый формат работы", "work_mode")
        }
        val suppliedCity = when (val value = body["city"]) {
            null -> null
            is String -> value.trim().takeIf { it.isNotEmpty() }
            else -> throw ApiException(400, "VALIDATION_ERROR", "city должен быть строкой или null", "city")
        }
        if (suppliedCity != null && suppliedCity.length > 100) {
            throw ApiException(400, "VALIDATION_ERROR", "Название города слишком длинное", "city")
        }
        if (suppliedWorkMode != "remote" && suppliedCity.isNullOrBlank()) {
            throw ApiException(400, "VALIDATION_ERROR", "Для гибридного и офисного формата нужен город", "city")
        }
        val suppliedHours = body["workload_hours_per_week"].intValue()
            ?.takeIf { it in 1..40 }
            ?: throw ApiException(400, "VALIDATION_ERROR", "workload_hours_per_week должен быть целым числом от 1 до 40", "workload_hours_per_week")
        val suppliedSalaryMin = body["salary_min_kopecks"].longValue()
            ?.takeIf { it in 1..MAX_SALARY_KOPECKS }
            ?: throw ApiException(400, "VALIDATION_ERROR", "salary_min_kopecks должен быть положительным целым числом", "salary_min_kopecks")
        val suppliedSalaryMax = body["salary_max_kopecks"].longValue()
            ?.takeIf { it in 1..MAX_SALARY_KOPECKS }
            ?: throw ApiException(400, "VALIDATION_ERROR", "salary_max_kopecks должен быть положительным целым числом", "salary_max_kopecks")
        if (suppliedSalaryMax < suppliedSalaryMin) {
            throw ApiException(400, "VALIDATION_ERROR", "Верхняя граница зарплаты должна быть не меньше нижней", "salary_max_kopecks")
        }
        val suppliedSalaryBasis = body["salary_basis"] as? String
        if (suppliedSalaryBasis !in setOf("gross", "net")) {
            throw ApiException(400, "VALIDATION_ERROR", "Недопустимый формат указания зарплаты", "salary_basis")
        }
        val need = store.one(
            "SELECT id, version, data FROM employer_needs WHERE id = :needId AND company_id = :companyId AND status = 'active' FOR SHARE",
            mapOf("needId" to needId, "companyId" to companyId),
        ) ?: throw ApiException(404, "NOT_FOUND", "Активная потребность не найдена")
        val needData = store.objectValue(need["data"])
        val salary = store.objectValue(needData["salary"])
        val title = needData["title"]?.toString()?.trim().orEmpty()
        val description = needData["description"]?.toString()?.trim().orEmpty()
        val specializationId = needData["specialization_id"]?.toString().orEmpty()
        val targetGrade = needData["target_grade"]?.toString().orEmpty()
        val workMode = needData["work_mode"]?.toString().orEmpty()
        val city = needData["city"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val hours = needData["workload_hours_per_week"].intValue()
        val minKopecks = salary["min_kopecks"].longValue()
        val maxKopecks = salary["max_kopecks"].longValue()
        val salaryBasis = salary["basis"]?.toString().orEmpty()
        val salaryWorkload = salary["workload_type"]?.toString().orEmpty()
        val salaryHours = salary["hours_per_week"].intValue()

        if (title.isEmpty() || title.length > 160 || description.isEmpty() || description.length > 5_000) {
            throw ApiException(400, "VALIDATION_ERROR", "Потребность должна содержать название и описание допустимой длины", "need_id")
        }
        if (specializationId != "backend-python-v1") {
            throw ApiException(400, "VALIDATION_ERROR", "Неподдерживаемая специализация", "specialization_id")
        }
        if (targetGrade !in setOf("junior", "middle", "senior")) {
            throw ApiException(400, "VALIDATION_ERROR", "Недопустимый грейд", "target_grade")
        }
        if (workMode !in setOf("remote", "hybrid", "office")) {
            throw ApiException(400, "VALIDATION_ERROR", "Недопустимый формат работы", "work_mode")
        }
        if (workMode != "remote" && city.isNullOrBlank()) {
            throw ApiException(400, "VALIDATION_ERROR", "Для гибридного и офисного формата нужен город", "city")
        }
        if (city != null && city.length > 120) {
            throw ApiException(400, "VALIDATION_ERROR", "Название города слишком длинное", "city")
        }
        if (hours == null || hours !in 1..40) {
            throw ApiException(400, "VALIDATION_ERROR", "Часы в неделю должны быть в диапазоне 1–40", "workload_hours_per_week")
        }
        if (minKopecks == null || maxKopecks == null || minKopecks <= 0 || maxKopecks < minKopecks ||
            salary["currency"] != "RUB" || salary["period"] != "month" || salaryBasis !in setOf("gross", "net") ||
            salaryHours != hours ||
            (salaryWorkload == "full_time" && hours != 40) ||
            (salaryWorkload == "part_time" && hours !in 1..39) ||
            salaryWorkload !in setOf("full_time", "part_time")
        ) {
            throw ApiException(400, "VALIDATION_ERROR", "Вилка зарплаты и занятость должны соответствовать активной потребности", "salary")
        }

        fun conflict(field: String): Nothing = throw ApiException(
            409,
            "VERSION_CONFLICT",
            "Активная потребность изменилась. Обновите её и повторите публикацию.",
            field,
            currentVersion = (need["version"] as? Number)?.toInt(),
        )
        if (suppliedTitle != title) conflict("title")
        if (suppliedDescription != description) conflict("description")
        if (suppliedSpecialization != specializationId) conflict("specialization_id")
        if (suppliedGrade != targetGrade) conflict("target_grade")
        if (suppliedWorkMode != workMode) conflict("work_mode")
        if (suppliedCity != city) conflict("city")
        if (suppliedHours != hours) conflict("workload_hours_per_week")
        if (suppliedSalaryMin != minKopecks) conflict("salary_min_kopecks")
        if (suppliedSalaryMax != maxKopecks) conflict("salary_max_kopecks")
        if (suppliedSalaryBasis != salaryBasis) conflict("salary_basis")

        val id = UUID.randomUUID()
        store.exec(
            """
            INSERT INTO public_vacancies(
                id, need_id, company_id, title, description, specialization_id,
                target_grade, work_mode, city, workload_hours_per_week,
                salary_min_kopecks, salary_max_kopecks, salary_basis, status
            ) VALUES (
                :id, :needId, :companyId, :title, :description, :specializationId,
                :targetGrade, :workMode, :city, :hours,
                :minKopecks, :maxKopecks, :salaryBasis, 'active'
            )
            """.trimIndent(),
            mapOf(
                "id" to id,
                "needId" to needId,
                "companyId" to companyId,
                "title" to title,
                "description" to description,
                "specializationId" to specializationId,
                "targetGrade" to targetGrade,
                "workMode" to workMode,
                "city" to city,
                "hours" to hours,
                "minKopecks" to minKopecks,
                "maxKopecks" to maxKopecks,
                "salaryBasis" to salaryBasis
            )
        )

        return getVacancy(id.toString())
    }

    fun applyToVacancy(actor: Actor, vacancyId: String, body: Map<String, Any?>): Map<String, Any?> {
        val candidateId = parseUuid(actor.id)
        val parsedVacancyId = parseUuid(vacancyId)
        if (body.keys.any { it !in setOf("cover_letter", "contact_channel_types") }) {
            throw ApiException(400, "VALIDATION_ERROR", "Отклик содержит неизвестные поля")
        }
        val rawCoverLetter = body["cover_letter"]
        val coverLetter = rawCoverLetter as? String
        if (rawCoverLetter != null && coverLetter == null) {
            throw ApiException(400, "VALIDATION_ERROR", "cover_letter должен быть строкой", "cover_letter")
        }
        val rawChannelTypes = body["contact_channel_types"]
        val requestedTypes = (rawChannelTypes as? List<*>)
            ?: throw ApiException(400, "VALIDATION_ERROR", "contact_channel_types обязателен", "contact_channel_types")
        val channelTypes = requestedTypes.map { it as? String ?: "" }
        if (channelTypes.size > 3 || channelTypes.toSet().size != channelTypes.size ||
            channelTypes.any { it !in CONTACT_TYPES }
        ) {
            throw ApiException(400, "VALIDATION_ERROR", "Укажите уникальные поддерживаемые каналы контакта", "contact_channel_types")
        }
        if (coverLetter != null && coverLetter.length > MAX_COVER_LETTER_LENGTH) {
            throw ApiException(400, "VALIDATION_ERROR", "Письмо не должно превышать $MAX_COVER_LETTER_LENGTH символов", "cover_letter")
        }
        val normalizedLetter = coverLetter?.trim()?.takeIf { it.isNotEmpty() }

        // ConsentService locks the candidate row, serializing submission with processing-consent revocation.
        consents.requireProcessing(candidateId.toString())
        val vacancy = store.one(
            "SELECT id, company_id FROM public_vacancies WHERE id = :id AND status = 'active'",
            mapOf("id" to parsedVacancyId),
        ) ?: throw ApiException(404, "NOT_FOUND", "Открытая вакансия не найдена")
        val profile = store.one(
            "SELECT contacts FROM candidate_profiles WHERE user_id = :candidateId FOR SHARE",
            mapOf("candidateId" to candidateId),
        ) ?: throw ApiException(404, "NOT_FOUND", "Профиль кандидата не найден")
        val contacts = store.objectValue(profile["contacts"])
        val enabled = ContactRules.grantedChannels(store.listValue(contacts["channels"]))
            .mapNotNull { channel -> channel["type"]?.let { it to channel } }
            .toMap()
        if (channelTypes.any { it !in enabled }) {
            throw ApiException(400, "VALIDATION_ERROR", "Можно разрешить только включённые каналы контакта", "contact_channel_types")
        }

        val existing = store.one(
            "SELECT id FROM candidate_applications WHERE vacancy_id = :vId AND candidate_id = :cId",
            mapOf("vId" to parsedVacancyId, "cId" to candidateId),
        )
        if (existing != null) {
            throw ApiException(409, "ALREADY_APPLIED", "Вы уже отправили отклик на эту вакансию")
        }

        val id = store.id()
        val created = store.one(
            """INSERT INTO candidate_applications(id, vacancy_id, candidate_id, cover_letter, status)
               VALUES (:id, :vId, :cId, :coverLetter, 'submitted') RETURNING id, created_at""".trimIndent(),
            mapOf("id" to id, "vId" to parsedVacancyId, "cId" to candidateId, "coverLetter" to normalizedLetter),
        ) ?: throw IllegalStateException("Application insert returned no row")
        store.exec(
            """INSERT INTO vacancy_application_contact_access(application_id, channel_types)
               VALUES (:applicationId, :channelTypes)""".trimIndent(),
            mapOf("applicationId" to id, "channelTypes" to store.json(channelTypes)),
        )

        return mapOf(
            "id" to created.getValue("id").toString(),
            "vacancy_id" to parsedVacancyId.toString(),
            "status" to "submitted",
            "created_at" to created.getValue("created_at").toString(),
            "contact_access_revoked" to false,
        )
    }

    fun listCandidateApplications(actor: Actor): List<Map<String, Any?>> {
        val candidateId = parseUuid(actor.id)
        return store.tx.execute {
            store.one("SELECT id FROM users WHERE id = :candidateId FOR SHARE", mapOf("candidateId" to candidateId))
                ?: throw ApiException(404, "NOT_FOUND", "Аккаунт не найден")
            val processingActive = consents.processingConsentStatusForLockedUsers(listOf(candidateId.toString()))[candidateId.toString()] == true
            val rows = if (!processingActive) {
                store.rows(
                    """SELECT a.id, a.status, a.created_at,
                              (access.application_id IS NULL OR access.revoked_at IS NOT NULL) AS contact_access_revoked,
                              COALESCE(access.application_id IS NOT NULL AND access.revoked_at IS NULL
                                       AND jsonb_array_length(access.channel_types) > 0, FALSE) AS has_selected_contacts
                       FROM candidate_applications a
                       LEFT JOIN vacancy_application_contact_access access ON access.application_id = a.id
                       WHERE a.candidate_id = :cId ORDER BY a.created_at DESC""".trimIndent(),
                    mapOf("cId" to candidateId),
                )
            } else {
                store.rows(
                    """SELECT a.id, a.vacancy_id, a.status, a.cover_letter, a.created_at,
                              v.title as vacancy_title, v.specialization_id, v.target_grade,
                              v.salary_min_kopecks, v.salary_max_kopecks, v.work_mode,
                              c.data->>'name' as company_name,
                              (access.application_id IS NULL OR access.revoked_at IS NOT NULL) AS contact_access_revoked,
                              COALESCE(access.application_id IS NOT NULL AND access.revoked_at IS NULL
                                       AND jsonb_array_length(access.channel_types) > 0, FALSE) AS has_selected_contacts
                       FROM candidate_applications a
                       JOIN public_vacancies v ON v.id = a.vacancy_id
                       JOIN companies c ON c.id = v.company_id
                       LEFT JOIN vacancy_application_contact_access access ON access.application_id = a.id
                       WHERE a.candidate_id = :cId ORDER BY a.created_at DESC""".trimIndent(),
                    mapOf("cId" to candidateId),
                )
            }
            rows.map { row ->
                if (!processingActive) {
                    mapOf(
                        "id" to row["id"].toString(),
                        "status" to row["status"],
                        "created_at" to row["created_at"].toString(),
                        "contact_access_revoked" to row["contact_access_revoked"],
                        "has_selected_contacts" to row["has_selected_contacts"],
                    )
                } else {
                    mapOf(
                        "id" to row["id"].toString(),
                        "vacancy_id" to row["vacancy_id"].toString(),
                        "vacancy_title" to row["vacancy_title"],
                        "company_name" to (row["company_name"] ?: "Компания"),
                        "specialization_id" to row["specialization_id"],
                        "target_grade" to row["target_grade"],
                        "work_mode" to row["work_mode"],
                        "salary" to mapOf("min_kopecks" to row["salary_min_kopecks"], "max_kopecks" to row["salary_max_kopecks"]),
                        "status" to row["status"],
                        "cover_letter" to row["cover_letter"],
                        "created_at" to row["created_at"].toString(),
                        "contact_access_revoked" to row["contact_access_revoked"],
                        "has_selected_contacts" to row["has_selected_contacts"],
                    )
                }
            }
        } ?: throw IllegalStateException("Application query returned no result")
    }

    fun listCompanyApplications(actor: Actor, vacancyId: String?): List<Map<String, Any?>> {
        val companyId = parseUuid(actor.companyId ?: throw ApiException(403, "FORBIDDEN", "Доступно только компании"))
        val where = StringBuilder("v.company_id = :companyId")
        val params = mutableMapOf<String, Any?>("companyId" to companyId)
        if (!vacancyId.isNullOrBlank()) {
            where.append(" AND a.vacancy_id = :vId")
            params["vId"] = parseUuid(vacancyId)
        }

        return store.tx.execute {
            // Lock affected user rows first, then read current consent in later READ COMMITTED statements.
            // ConsentService records revocation under FOR UPDATE on the same user row.
            store.rows(
                """SELECT u.id FROM candidate_applications a
                   JOIN public_vacancies v ON v.id = a.vacancy_id
                   JOIN users u ON u.id = a.candidate_id
                   WHERE $where ORDER BY u.id FOR SHARE OF u""".trimIndent(),
                params,
            )
            val rows = store.rows(
                """SELECT a.id, a.vacancy_id, a.candidate_id, a.status, a.created_at,
                          (access.application_id IS NULL OR access.revoked_at IS NOT NULL) AS contact_access_revoked,
                          COALESCE(access.application_id IS NOT NULL AND access.revoked_at IS NULL
                                   AND jsonb_array_length(access.channel_types) > 0, FALSE) AS has_selected_contacts
                   FROM candidate_applications a
                   JOIN public_vacancies v ON v.id = a.vacancy_id
                   LEFT JOIN vacancy_application_contact_access access ON access.application_id = a.id
                   WHERE $where ORDER BY a.created_at DESC""".trimIndent(),
                params,
            )
            val candidateIds = rows.map { it.getValue("candidate_id").toString() }.distinct()
            val processingStatus = consents.processingConsentStatusForLockedUsers(candidateIds)
            val activeApplicationIds = rows
                .filter { processingStatus[it.getValue("candidate_id").toString()] == true }
                .map { it.getValue("id").toString() }
            val detailsByApplication = if (activeApplicationIds.isEmpty()) emptyMap() else store.rows(
                """SELECT a.id AS application_id, a.cover_letter, v.title as vacancy_title,
                          p.data->>'display_name' as candidate_name,
                          p.data->>'headline' as candidate_headline,
                          p.data->>'declared_grade' as declared_grade,
                          q.grade as verified_grade
                   FROM candidate_applications a
                   JOIN public_vacancies v ON v.id = a.vacancy_id
                   LEFT JOIN candidate_profiles p ON p.user_id = a.candidate_id
                   LEFT JOIN qualifications q ON q.user_id = a.candidate_id AND q.specialization_id = v.specialization_id
                   WHERE a.id IN (:applicationIds)""".trimIndent(),
                mapOf("applicationIds" to activeApplicationIds),
            ).associateBy { it.getValue("application_id").toString() }
            rows.map { row ->
                val processingActive = processingStatus[row.getValue("candidate_id").toString()] == true
                if (!processingActive) {
                    mapOf(
                        "id" to row["id"].toString(),
                        "status" to row["status"],
                        "created_at" to row["created_at"].toString(),
                        "candidate_processing_active" to false,
                        "contact_access_revoked" to row["contact_access_revoked"],
                        "has_selected_contacts" to row["has_selected_contacts"],
                    )
                } else {
                    val details = detailsByApplication[row.getValue("id").toString()] ?: emptyMap()
                    mapOf(
                        "id" to row["id"].toString(),
                        "vacancy_id" to row["vacancy_id"].toString(),
                        "vacancy_title" to details["vacancy_title"],
                        "candidate_id" to row["candidate_id"].toString(),
                        "candidate_name" to (details["candidate_name"] ?: "Кандидат"),
                        "candidate_headline" to details["candidate_headline"],
                        "declared_grade" to details["declared_grade"],
                        "verified_grade" to details["verified_grade"],
                        "cover_letter" to details["cover_letter"],
                        "status" to row["status"],
                        "created_at" to row["created_at"].toString(),
                        "candidate_processing_active" to true,
                        "contact_access_revoked" to row["contact_access_revoked"],
                        "has_selected_contacts" to row["has_selected_contacts"],
                    )
                }
            }
        } ?: throw IllegalStateException("Application list query returned no result")
    }

    fun applicationContacts(actor: Actor, applicationId: String): Map<String, Any?> {
        val companyId = parseUuid(actor.companyId ?: throw ApiException(403, "FORBIDDEN", "Доступно только компании"))
        val parsedApplicationId = parseUuid(applicationId)
        return store.tx.execute {
            val application = store.one(
                """SELECT a.id, a.candidate_id FROM candidate_applications a
                   JOIN public_vacancies v ON v.id = a.vacancy_id
                   JOIN users u ON u.id = a.candidate_id AND u.deleted_at IS NULL
                   WHERE a.id = :applicationId AND v.company_id = :companyId FOR SHARE OF u""".trimIndent(),
                mapOf("applicationId" to parsedApplicationId, "companyId" to companyId),
            ) ?: throw ApiException(404, "NOT_FOUND", "Отклик с доступом к контактам не найден")
            val candidateId = application.getValue("candidate_id").toString()
            if (consents.processingConsentStatusForLockedUsers(listOf(candidateId))[candidateId] != true) {
                throw ApiException(404, "NOT_FOUND", "Отклик с доступом к контактам не найден")
            }
            val permission = store.one(
                """SELECT channel_types FROM vacancy_application_contact_access
                   WHERE application_id = :applicationId AND revoked_at IS NULL""".trimIndent(),
                mapOf("applicationId" to parsedApplicationId),
            ) ?: throw ApiException(404, "NOT_FOUND", "Отклик с доступом к контактам не найден")
            val profile = store.one(
                "SELECT contacts FROM candidate_profiles WHERE user_id = :candidateId FOR SHARE",
                mapOf("candidateId" to candidateId),
            ) ?: throw ApiException(404, "NOT_FOUND", "Контактные данные недоступны")
            val allowedTypes = stringList(permission["channel_types"]).toSet()
            val channels = ContactRules.grantedChannels(store.listValue(store.objectValue(profile["contacts"])["channels"]))
                .filter { it["type"] in allowedTypes }
            if (channels.isNotEmpty()) {
                store.exec(
                    """INSERT INTO audit_events(id, actor_id, event_type, entity_id, data)
                       VALUES (:id, :actorId, 'vacancy_application_contacts_viewed', :applicationId, :data)""".trimIndent(),
                    mapOf("id" to store.id(), "actorId" to actor.id, "applicationId" to parsedApplicationId,
                        "data" to store.json(mapOf("candidate_id" to candidateId, "channels" to channels.mapNotNull { it["type"] }))),
                )
            }
            mapOf("application_id" to parsedApplicationId.toString(), "channels" to channels)
        } ?: throw IllegalStateException("Application contact query returned no result")
    }

    fun revokeApplicationContactAccess(actor: Actor, applicationId: String): Map<String, Any?> {
        val candidateId = parseUuid(actor.id)
        val parsedApplicationId = parseUuid(applicationId)
        store.one("SELECT id FROM users WHERE id = :candidateId FOR UPDATE", mapOf("candidateId" to candidateId))
            ?: throw ApiException(404, "NOT_FOUND", "Аккаунт не найден")
        store.one(
            "SELECT id FROM candidate_applications WHERE id = :applicationId AND candidate_id = :candidateId",
            mapOf("applicationId" to parsedApplicationId, "candidateId" to candidateId),
        ) ?: throw ApiException(404, "NOT_FOUND", "Отклик не найден")
        val revoked = store.one(
            """UPDATE vacancy_application_contact_access SET revoked_at = COALESCE(revoked_at, now())
               WHERE application_id = :applicationId RETURNING revoked_at""".trimIndent(),
            mapOf("applicationId" to parsedApplicationId),
        ) ?: throw ApiException(404, "NOT_FOUND", "Разрешение на контакты не найдено")
        return mapOf(
            "revoked" to true,
            "contact_access_revoked" to true,
            "revoked_at" to revoked.getValue("revoked_at").toString(),
        )
    }

    fun updateApplicationStatus(actor: Actor, applicationId: String, status: String): Map<String, Any?> {
        val companyId = parseUuid(actor.companyId ?: throw ApiException(403, "FORBIDDEN", "Доступно только компании"))
        val parsedAppId = parseUuid(applicationId)
        val app = store.one(
            """
            SELECT a.id, v.company_id
            FROM candidate_applications a
            JOIN public_vacancies v ON v.id = a.vacancy_id
            WHERE a.id = :appId
            """.trimIndent(),
            mapOf("appId" to parsedAppId)
        ) ?: throw ApiException(404, "NOT_FOUND", "Отклик не найден")

        val appCompanyId = parseUuid(app["company_id"].toString())
        if (appCompanyId != companyId) {
            throw ApiException(403, "FORBIDDEN", "Нельзя менять отклик чужой вакансии")
        }

        if (status !in setOf("reviewed", "accepted", "declined")) {
            throw ApiException(400, "INVALID_STATUS", "Недопустимый статус: $status")
        }

        store.exec(
            "UPDATE candidate_applications SET status = :status, updated_at = now() WHERE id = :id",
            mapOf("id" to parsedAppId, "status" to status)
        )

        return mapOf("id" to applicationId, "status" to status)
    }

    private fun Any?.intValue(): Int? = toIntegralLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private fun Any?.longValue(): Long? = toIntegralLong()

    private fun stringList(value: Any?): List<String> = (value as? List<*>)?.mapNotNull { it as? String }.orEmpty()

    private fun Any?.toIntegralLong(): Long? {
        val value = this as? Number ?: return null
        return runCatching { value.toString().toBigDecimal().longValueExact() }.getOrNull()
    }

    private fun formatVacancy(row: Map<String, Any?>): Map<String, Any?> {
        return mapOf(
            "id" to row["id"].toString(),
            "need_id" to row["need_id"]?.toString(),
            "company_id" to row["company_id"].toString(),
            "company_name" to (row["company_name"] ?: "Компания"),
            "company_industry" to row["company_industry"],
            "company_verification" to companyVerification.getPublicSummary(row["company_id"].toString()),
            "title" to row["title"],
            "description" to row["description"],
            "specialization_id" to row["specialization_id"],
            "target_grade" to row["target_grade"],
            "work_mode" to row["work_mode"],
            "city" to row["city"],
            "workload_hours_per_week" to row["workload_hours_per_week"],
            "salary" to mapOf(
                "min_kopecks" to row["salary_min_kopecks"],
                "max_kopecks" to row["salary_max_kopecks"],
                "currency" to "RUB",
                "basis" to row["salary_basis"]
            ),
            "status" to row["status"],
            "created_at" to row["created_at"].toString()
        )
    }

    private fun parseUuid(raw: String): UUID = try {
        UUID.fromString(raw)
    } catch (_: Exception) {
        throw ApiException(400, "INVALID_UUID", "Некорректный UUID: $raw")
    }

    companion object {
        private const val MAX_COVER_LETTER_LENGTH = 5_000
        private const val MAX_SALARY_KOPECKS = 100_000_000_000L
        private val CONTACT_TYPES = setOf("email", "phone", "website")
    }
}
