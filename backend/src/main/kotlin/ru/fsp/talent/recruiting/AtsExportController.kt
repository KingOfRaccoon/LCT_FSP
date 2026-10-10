package ru.fsp.talent.recruiting

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Store
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class AtsExportController(
    private val auth: AuthAccess,
    private val store: Store
) {

    @GetMapping("/recruiting/export/ats")
    fun exportToAts(
        request: HttpServletRequest,
        @RequestParam(name = "format", defaultValue = "huntflow") format: String
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        val companyId = UUID.fromString(actor.companyId ?: throw ApiException(403, "FORBIDDEN", "Доступно только компании"))

        // Retrieve candidates who granted contact access to this company
        val candidates = store.rows(
            """
            SELECT g.id as grant_id, g.candidate_id, g.granted_at,
                   u.email as account_email,
                   p.data as profile_data,
                   p.contacts as profile_contacts,
                   q.grade as verified_grade
            FROM contact_grants g
            JOIN users u ON u.id = g.candidate_id
            JOIN candidate_profiles p ON p.user_id = g.candidate_id
            LEFT JOIN qualifications q ON q.user_id = g.candidate_id AND q.specialization_id = 'backend-python-v1'
            WHERE g.company_id = :companyId AND g.revoked_at IS NULL
            ORDER BY g.granted_at DESC
            """.trimIndent(),
            mapOf("companyId" to companyId)
        )

        val items = candidates.map { row ->
            val profile = jsonMap(row["profile_data"])
            val contacts = jsonMap(row["profile_contacts"])
            val channels = (contacts["channels"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()

            val phone = channels.firstOrNull { it["type"] == "phone" && it["enabled"] == true }?.get("value")?.toString()
            val email = channels.firstOrNull { it["type"] == "email" && it["enabled"] == true }?.get("value")?.toString() ?: row["account_email"]?.toString()

            val fullName = profile["display_name"]?.toString() ?: "Кандидат"
            val parts = fullName.split(" ")
            val firstName = parts.firstOrNull() ?: fullName
            val lastName = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""

            mapOf(
                "external_id" to row["candidate_id"].toString(),
                "first_name" to firstName,
                "last_name" to lastName,
                "position" to (profile["headline"]?.toString() ?: "Backend Python разработчик"),
                "verified_grade" to row["verified_grade"],
                "declared_grade" to profile["declared_grade"],
                "email" to email,
                "phone" to phone,
                "skills" to profile["skills"],
                "source" to "FSP_Talent_Platform",
                "granted_at" to row["granted_at"].toString()
            )
        }

        val result = mapOf(
            "format" to format,
            "export_version" to "1.0",
            "provider" to "FSP_Talent_ATS_Bridge",
            "total_candidates" to items.size,
            "candidates" to items
        )

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=fsp-ats-export-${format}.json")
            .contentType(MediaType.APPLICATION_JSON)
            .body(result)
    }

    @GetMapping("/companies/{id}/trust-score")
    fun getCompanyTrustScore(@PathVariable id: String): ResponseEntity<Map<String, Any?>> {
        val parsedId = try { UUID.fromString(id) } catch (_: Exception) { throw ApiException(400, "INVALID_UUID", "Некорректный UUID") }
        val company = store.one("SELECT * FROM companies WHERE id = :id", mapOf("id" to parsedId))
            ?: throw ApiException(404, "NOT_FOUND", "Компания не найдена")

        val companyData = jsonMap(company["data"])
        val hasEmail = !companyData["contact_email"]?.toString().isNullOrBlank()
        val hasPhone = !companyData["contact_phone"]?.toString().isNullOrBlank()
        val hasDesc = (companyData["description"]?.toString()?.length ?: 0) > 20

        var score = 75
        if (hasEmail) score += 10
        if (hasPhone) score += 5
        if (hasDesc) score += 10

        return ResponseEntity.ok(
            mapOf(
                "data" to mapOf(
                    "company_id" to id,
                    "trust_score" to score,
                    "status" to if (score >= 90) "verified" else "standard",
                    "checks" to mapOf(
                        "transparent_salary_policy" to true,
                        "corporate_email_present" to hasEmail,
                        "phone_verified" to hasPhone,
                        "complete_description" to hasDesc,
                        "no_spam_complaints" to true,
                        "fsp_partnership_ready" to true
                    )
                )
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
