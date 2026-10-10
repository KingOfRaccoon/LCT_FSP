package ru.fsp.talent.vacancies

import org.springframework.stereotype.Service
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.Store
import java.util.UUID

@Service
class VacancyService(private val store: Store) {

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
        val title = body["title"]?.toString()?.trim() ?: throw ApiException(400, "VALIDATION_ERROR", "title обязателен")
        val description = body["description"]?.toString()?.trim() ?: throw ApiException(400, "VALIDATION_ERROR", "description обязателен")
        val specializationId = body["specialization_id"]?.toString() ?: "backend-python-v1"
        val targetGrade = body["target_grade"]?.toString() ?: "junior"
        val workMode = body["work_mode"]?.toString() ?: "remote"
        val city = body["city"]?.toString()
        val hours = (body["workload_hours_per_week"] as? Number)?.toInt() ?: 40
        val minKopecks = (body["salary_min_kopecks"] as? Number)?.toLong() ?: 10_000_000L
        val maxKopecks = (body["salary_max_kopecks"] as? Number)?.toLong() ?: 20_000_000L
        val salaryBasis = body["salary_basis"]?.toString() ?: "gross"
        val needId = body["need_id"]?.toString()?.let { parseUuid(it) }

        if (minKopecks <= 0 || maxKopecks < minKopecks) {
            throw ApiException(400, "VALIDATION_ERROR", "Верхняя граница зарплаты должна быть не меньше нижней")
        }

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

    fun applyToVacancy(actor: Actor, vacancyId: String, coverLetter: String?): Map<String, Any?> {
        val candidateId = parseUuid(actor.id)
        val parsedVacancyId = parseUuid(vacancyId)

        store.one(
            "SELECT id FROM public_vacancies WHERE id = :id AND status = 'active'",
            mapOf("id" to parsedVacancyId)
        ) ?: throw ApiException(404, "NOT_FOUND", "Открытая вакансия не найдена")

        val existing = store.one(
            "SELECT id FROM candidate_applications WHERE vacancy_id = :vId AND candidate_id = :cId",
            mapOf("vId" to parsedVacancyId, "cId" to candidateId)
        )
        if (existing != null) {
            throw ApiException(409, "ALREADY_APPLIED", "Вы уже отправили отклик на эту вакансию")
        }

        val id = UUID.randomUUID()
        store.exec(
            """
            INSERT INTO candidate_applications(id, vacancy_id, candidate_id, cover_letter, status)
            VALUES (:id, :vId, :cId, :coverLetter, 'submitted')
            """.trimIndent(),
            mapOf(
                "id" to id,
                "vId" to parsedVacancyId,
                "cId" to candidateId,
                "coverLetter" to coverLetter
            )
        )

        return mapOf(
            "id" to id.toString(),
            "vacancy_id" to vacancyId,
            "status" to "submitted",
            "created_at" to store.now().toString()
        )
    }

    fun listCandidateApplications(actor: Actor): List<Map<String, Any?>> {
        val rows = store.rows(
            """
            SELECT a.id, a.vacancy_id, a.status, a.cover_letter, a.created_at, a.updated_at,
                   v.title as vacancy_title, v.specialization_id, v.target_grade,
                   v.salary_min_kopecks, v.salary_max_kopecks, v.work_mode,
                   c.data->>'name' as company_name
            FROM candidate_applications a
            JOIN public_vacancies v ON v.id = a.vacancy_id
            JOIN companies c ON c.id = v.company_id
            WHERE a.candidate_id = :cId
            ORDER BY a.created_at DESC
            """.trimIndent(),
            mapOf("cId" to parseUuid(actor.id))
        )
        return rows.map { row ->
            mapOf(
                "id" to row["id"].toString(),
                "vacancy_id" to row["vacancy_id"].toString(),
                "vacancy_title" to row["vacancy_title"],
                "company_name" to (row["company_name"] ?: "Компания"),
                "specialization_id" to row["specialization_id"],
                "target_grade" to row["target_grade"],
                "work_mode" to row["work_mode"],
                "salary" to mapOf(
                    "min_kopecks" to row["salary_min_kopecks"],
                    "max_kopecks" to row["salary_max_kopecks"]
                ),
                "status" to row["status"],
                "cover_letter" to row["cover_letter"],
                "created_at" to row["created_at"].toString()
            )
        }
    }

    fun listCompanyApplications(actor: Actor, vacancyId: String?): List<Map<String, Any?>> {
        val companyId = parseUuid(actor.companyId ?: throw ApiException(403, "FORBIDDEN", "Доступно только компании"))
        val query = StringBuilder(
            """
            SELECT a.id, a.vacancy_id, a.candidate_id, a.status, a.cover_letter, a.created_at,
                   v.title as vacancy_title,
                   u.email as candidate_email,
                   p.data->>'display_name' as candidate_name,
                   p.data->>'headline' as candidate_headline,
                   p.data->>'declared_grade' as declared_grade,
                   q.grade as verified_grade
            FROM candidate_applications a
            JOIN public_vacancies v ON v.id = a.vacancy_id
            JOIN users u ON u.id = a.candidate_id
            LEFT JOIN candidate_profiles p ON p.user_id = a.candidate_id
            LEFT JOIN qualifications q ON q.user_id = a.candidate_id AND q.specialization_id = v.specialization_id
            WHERE v.company_id = :companyId
            """.trimIndent()
        )
        val params = mutableMapOf<String, Any?>("companyId" to companyId)
        if (!vacancyId.isNullOrBlank()) {
            query.append(" AND a.vacancy_id = :vId")
            params["vId"] = parseUuid(vacancyId)
        }
        query.append(" ORDER BY a.created_at DESC")

        return store.rows(query.toString(), params).map { row ->
            mapOf(
                "id" to row["id"].toString(),
                "vacancy_id" to row["vacancy_id"].toString(),
                "vacancy_title" to row["vacancy_title"],
                "candidate_id" to row["candidate_id"].toString(),
                "candidate_name" to (row["candidate_name"] ?: "Кандидат"),
                "candidate_headline" to row["candidate_headline"],
                "declared_grade" to row["declared_grade"],
                "verified_grade" to row["verified_grade"],
                "cover_letter" to row["cover_letter"],
                "status" to row["status"],
                "created_at" to row["created_at"].toString()
            )
        }
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

    private fun formatVacancy(row: Map<String, Any?>): Map<String, Any?> {
        return mapOf(
            "id" to row["id"].toString(),
            "need_id" to row["need_id"]?.toString(),
            "company_id" to row["company_id"].toString(),
            "company_name" to (row["company_name"] ?: "Компания"),
            "company_industry" to row["company_industry"],
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
}
