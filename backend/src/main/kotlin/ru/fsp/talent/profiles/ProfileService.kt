package ru.fsp.talent.profiles

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Service
import ru.fsp.talent.assessment.AssessmentService
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.MutationService
import ru.fsp.talent.core.Store
import ru.fsp.talent.consents.ConsentService
import ru.fsp.talent.identity.ProfileReader
import ru.fsp.talent.companyverification.CompanyVerificationService
import ru.fsp.talent.companyverification.CompanyVerificationRules
import java.time.YearMonth

@Service
class ProfileService(
    private val store: Store,
    private val auth: AuthAccess,
    private val contracts: Contracts,
    private val mutations: MutationService,
    private val assessment: AssessmentService,
    private val consents: ConsentService,
    private val companyVerification: CompanyVerificationService,
) : ProfileReader {
    fun getOwnProfile(request: HttpServletRequest): Map<String, Any?> = privateProfile(auth.candidate(request).id)

    override fun privateProfile(userId: String): Map<String, Any?> {
        val row = profileRow(userId)
        val data = jsonMap(row["data"])
        val rawVisibility = row.getValue("visibility").toString()
        val effectiveVisibility = if (rawVisibility == "public" && !consents.publicationAllowed(userId)) "hidden" else rawVisibility
        val skills = (data["skills"] as? List<*>).orEmpty().mapNotNull { raw ->
            when (raw) {
                is String -> mapOf("skill_id" to raw, "provenance" to "candidate_declared")
                is Map<*, *> -> mapOf("skill_id" to raw["skill_id"], "provenance" to "candidate_declared")
                else -> null
            }
        }
        val activeAttempt = assessment.activeAttemptId(userId)
        val missing = missingRequired(data)
        val qualification = store.one(
            "SELECT grade FROM qualifications WHERE user_id = :id AND specialization_id = 'backend-python-v1'",
            mapOf("id" to userId),
        )
        return mapOf(
            "id" to userId,
            "version" to (row["version"] as Number).toInt(),
            "display_name" to data.string("display_name"),
            "headline" to data["headline"],
            "summary" to data["summary"],
            "industry" to data["industry"],
            "role_preferences" to data.list("role_preferences"),
            "soft_skills" to data.list("soft_skills"),
            "specialization_id" to SPECIALIZATION,
            "declared_grade" to data["declared_grade"],
            "verified_grade" to qualification?.get("grade"),
            "visibility" to effectiveVisibility,
            "allowed_work_modes" to data.list("allowed_work_modes"),
            "cities" to data.list("cities"),
            "desired_hours_per_week" to data["desired_hours_per_week"],
            "max_weekly_hours" to data["max_weekly_hours"],
            "expected_salary" to data["expected_salary"],
            "skills" to skills,
            "experience_entries" to CandidateProfileEntries.experience(data),
            "project_entries" to CandidateProfileEntries.projects(data),
            "version_updated_at" to asInstant(row["updated_at"]).toString(),
            "profile_complete" to missing.isEmpty(),
            "missing_required_fields" to missing,
            "active_attempt_id" to activeAttempt,
            "assessment_eligibility" to assessment.getEligibility(userId),
        )
    }

    fun patchOwnProfile(request: HttpServletRequest, body: Map<String, Any?>): Map<String, Any?> {
        contracts.validate("CandidateProfilePatch", body)
        val actor = auth.candidate(request)
        val expected = (body["expected_version"] as Number).toInt()
        val patch = body.filterKeys { it != "expected_version" }.toMutableMap()
        (patch["skills"] as? List<*>)?.let { values ->
            patch["skills"] = values.map { mapOf("skill_id" to it, "provenance" to "candidate_declared") }
        }
        return mutations.run(request, "candidate:${actor.id}", body) {
            consents.requireProcessing(actor.id)
            validateSalaryPreference(patch)
            validateProfileEntries(patch)
            updateProfile(actor.id, expected, patch)
            privateProfile(actor.id)
        }.body!!.getValue("data") as Map<String, Any?>
    }

    fun patchVisibility(request: HttpServletRequest, body: Map<String, Any?>): Map<String, Any?> {
        contracts.validate("VisibilityRequest", body)
        val actor = auth.candidate(request)
        val visibility = body.getValue("visibility").toString()
        if (visibility == "public") auth.verified(actor)
        val expected = (body["expected_version"] as Number).toInt()
        return mutations.run(request, "candidate:${actor.id}", body) {
            if (visibility == "public") {
                consents.requireProcessing(actor.id)
                consents.requirePublication(actor.id)
            }
            if (visibility == "public" && missingRequired(profileData(actor.id)).isNotEmpty()) {
                throw ApiException(422, "PROFILE_INCOMPLETE", "Complete the required profile fields before becoming visible")
            }
            val changed = store.exec(
                """UPDATE candidate_profiles SET visibility = :visibility, version = version + 1, updated_at = now()
                   WHERE user_id = :id AND version = :version""".trimIndent(),
                mapOf("visibility" to visibility, "id" to actor.id, "version" to expected),
            )
            ensureVersionChanged(changed, actor.id)
            privateProfile(actor.id)
        }.body!!.getValue("data") as Map<String, Any?>
    }

    fun getOwnCompany(request: HttpServletRequest): Map<String, Any?> {
        val actor = auth.employer(request)
        return companyDto(companyRow(actor.companyId!!))
    }

    fun patchOwnCompany(request: HttpServletRequest, body: Map<String, Any?>): Map<String, Any?> {
        contracts.validate("CompanyPatch", body)
        val actor = auth.employer(request)
        val expected = (body["expected_version"] as Number).toInt()
        val patch = body.filterKeys { it != "expected_version" }.toMutableMap()
        if (patch.containsKey("inn")) {
            val inn = (patch["inn"] as? String)?.let(CompanyVerificationRules::normalizeInn)?.takeIf(String::isNotBlank)
            if (inn != null && !CompanyVerificationRules.isValidInn(inn)) throw ApiException(400, "VALIDATION_ERROR", "INN has an invalid checksum", "inn")
            patch["inn"] = inn
        }
        if (patch.containsKey("legal_name")) patch["legal_name"] = (patch["legal_name"] as? String)?.trim()?.takeIf(String::isNotBlank)
        return mutations.run(request, "company:${actor.companyId}", body) {
            consents.requireProcessing(actor.id)
            val changed = store.exec(
                """UPDATE companies SET data = data || :patch, version = version + 1, updated_at = now()
                   WHERE id = :id AND version = :version""".trimIndent(),
                mapOf("patch" to store.json(patch), "id" to actor.companyId, "version" to expected),
            )
            if (changed == 0) {
                val current = companyRow(actor.companyId!!)
                throw ApiException(409, "VERSION_CONFLICT", "Company changed; reload before saving", currentVersion = (current["version"] as Number).toInt())
            }
            companyVerification.invalidateForCompany(actor.companyId!!)
            companyDto(companyRow(actor.companyId!!))
        }.body!!.getValue("data") as Map<String, Any?>
    }

    private fun updateProfile(userId: String, expected: Int, patch: Map<String, Any?>) {
        val changed = store.exec(
            """UPDATE candidate_profiles SET data = data || :patch, version = version + 1, updated_at = now()
               WHERE user_id = :id AND version = :version""".trimIndent(),
            mapOf("patch" to store.json(patch), "id" to userId, "version" to expected),
        )
        ensureVersionChanged(changed, userId)
    }

    private fun ensureVersionChanged(changed: Int, userId: String) {
        if (changed == 0) {
            val current = profileRow(userId)
            throw ApiException(409, "VERSION_CONFLICT", "Profile changed; reload before saving", currentVersion = (current["version"] as Number).toInt())
        }
    }

    private fun profileData(userId: String) = jsonMap(profileRow(userId)["data"])

    private fun validateProfileEntries(patch: Map<String, Any?>) {
        val experience = patch["experience_entries"] as? List<*> ?: emptyList<Any?>()
        val projects = patch["project_entries"] as? List<*> ?: emptyList<Any?>()
        listOf("experience_entries" to experience, "project_entries" to projects).forEach { (field, entries) ->
            val ids = entries.mapNotNull { (it as? Map<*, *>)?.get("id")?.toString() }
            if (ids.size != ids.toSet().size) invalidEntries("Duplicate entry IDs are not allowed", field)
        }
        experience.forEach { raw ->
            val entry = raw as? Map<*, *> ?: return@forEach
            val start = entry["start_month"]?.toString() ?: return@forEach
            val end = entry["end_month"]?.toString() ?: return@forEach
            if (end.isNotEmpty() && YearMonth.parse(start).isAfter(YearMonth.parse(end))) {
                invalidEntries("Experience start_month must not follow end_month", "experience_entries")
            }
        }
    }

    private fun validateSalaryPreference(patch: Map<String, Any?>) {
        val salary = patch["expected_salary"] as? Map<*, *> ?: return
        val minimum = (salary["min_kopecks"] as? Number)?.toLong()
        val maximum = (salary["max_kopecks"] as? Number)?.toLong()
        val hours = (salary["hours_per_week"] as? Number)?.toInt()
        val fullTime = salary["workload_type"] == "full_time"
        if (minimum == null || maximum == null || minimum <= 0 || maximum < minimum ||
            hours == null || (fullTime && hours != 40) || (!fullTime && hours !in 1..39)) {
            throw ApiException(400, "VALIDATION_ERROR", "Salary expectations must have an ordered positive monthly range and matching workload", "expected_salary")
        }
    }

    private fun invalidEntries(message: String, field: String): Nothing =
        throw ApiException(400, "VALIDATION_ERROR", message, field)

    private fun companyRow(companyId: String) = store.one(
        "SELECT id, version, data, updated_at FROM companies WHERE id = :id",
        mapOf("id" to companyId),
    ) ?: throw ApiException(404, "NOT_FOUND", "Company not found")

    private fun companyDto(row: Map<String, Any?>): Map<String, Any?> {
        val data = jsonMap(row["data"])
        return mapOf(
            "id" to row.getValue("id").toString(),
            "version" to (row["version"] as Number).toInt(),
            "name" to data["name"],
            "description" to data["description"],
            "website" to data["website"],
            "industry" to data["industry"],
            "contact_email" to data["contact_email"],
            "contact_phone" to data["contact_phone"],
            "inn" to data["inn"],
            "legal_name" to data["legal_name"],
            "verification" to companyVerification.getPublicSummary(row.getValue("id").toString()),
            "updated_at" to asInstant(row["updated_at"]).toString(),
        )
    }

    private fun profileRow(userId: String): Map<String, Any?> = store.one(
        """SELECT cp.user_id, cp.version, cp.visibility, cp.data, cp.contacts, cp.updated_at
           FROM candidate_profiles cp JOIN users u ON u.id = cp.user_id
           WHERE cp.user_id = :id AND u.deleted_at IS NULL""".trimIndent(),
        mapOf("id" to userId),
    ) ?: throw ApiException(404, "NOT_FOUND", "Candidate profile not found")

    private fun missingRequired(data: Map<String, Any?>): List<String> = buildList {
        if (data.string("display_name").isBlank()) add("display_name")
        if (SPECIALIZATION.isBlank()) add("specialization_id")
        if (data["declared_grade"] !in setOf("junior", "middle")) add("declared_grade")
        val workModes = data.list("allowed_work_modes")
        if (workModes.isEmpty()) add("allowed_work_modes")
        if ((data["max_weekly_hours"] as? Number)?.toInt() == null) add("max_weekly_hours")
        if (workModes.any { it == "hybrid" || it == "office" } && data.list("cities").isEmpty()) add("cities")
    }

    private fun jsonMap(value: Any?): Map<String, Any?> = when (value) {
        is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
        else -> emptyMap()
    }

    private fun asInstant(value: Any?): java.time.Instant = when (value) {
        is java.time.Instant -> value
        is java.sql.Timestamp -> value.toInstant()
        else -> java.time.Instant.parse(value.toString())
    }

    private fun Map<String, Any?>.string(key: String) = this[key]?.toString().orEmpty()
    private fun Map<String, Any?>.list(key: String) = this[key] as? List<String> ?: emptyList()

    companion object {
        private const val SPECIALIZATION = "backend-python-v1"
    }
}
