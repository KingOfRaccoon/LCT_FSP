package ru.fsp.talent.companyverification

import ru.fsp.talent.core.ApiException
import java.net.URI
import java.util.UUID

internal object CompanyVerificationRules {
    fun expectedCompanyVersion(body: Map<String, Any?>): Int {
        if (body.keys != setOf("expected_company_version")) invalid("Request must contain expected_company_version")
        return positiveInt(body["expected_company_version"], "expected_company_version")
    }

    fun expectedRequestVersion(body: Map<String, Any?>): Int {
        if (body.keys != setOf("expected_version")) invalid("Request must contain expected_version")
        return positiveInt(body["expected_version"], "expected_version")
    }

    fun requestId(value: String): String = try {
        UUID.fromString(value).toString()
    } catch (_: IllegalArgumentException) {
        invalid("request_id must be a UUID", "request_id")
    }

    fun review(body: Map<String, Any?>): ReviewInput {
        val fields = setOf(
            "expected_version", "outcome", "evidence_reference", "registry_inn", "registry_legal_name",
            "registry_status", "representative_reviewed", "comment",
        )
        if (body.keys != fields) invalid("Review fields are incomplete or contain unknown values")
        val outcome = text(body["outcome"], "outcome", 1, 20)
        if (outcome !in setOf("approve", "request_changes", "reject")) invalid("Unknown review outcome", "outcome")
        val inn = optionalText(body["registry_inn"], "registry_inn", 12)?.let(::normalizeInn)
        if (inn != null && !isValidInn(inn)) invalid("registry_inn has an invalid checksum", "registry_inn")
        val registryStatus = text(body["registry_status"], "registry_status", 1, 30)
        if (registryStatus !in setOf("ACTIVE", "LIQUIDATING", "TERMINATED", "UNKNOWN")) {
            invalid("Unknown registry_status", "registry_status")
        }
        return ReviewInput(
            expectedVersion = positiveInt(body["expected_version"], "expected_version"),
            outcome = outcome,
            evidenceReference = text(body["evidence_reference"], "evidence_reference", 1, 1000),
            registryInn = inn,
            registryLegalName = optionalText(body["registry_legal_name"], "registry_legal_name", 300),
            registryStatus = registryStatus,
            representativeReviewed = body["representative_reviewed"] as? Boolean
                ?: invalid("representative_reviewed must be boolean", "representative_reviewed"),
            comment = text(body["comment"], "comment", 1, 2000),
        )
    }

    fun registryDecision(companyInn: String, companyLegalName: String, lookup: RegistryLookup): RegistryDecision {
        if (!lookup.available) return RegistryDecision("unavailable", "unavailable")
        if (lookup.errorCode == "not_found") return RegistryDecision("needs_changes", "not_found")
        val matches = lookup.inn == companyInn && lookup.legalName?.let {
            normalizeLegalName(it) == normalizeLegalName(companyLegalName)
        } == true
        return if (matches && lookup.status == "ACTIVE") {
            RegistryDecision("registry_confirmed", "available")
        } else {
            RegistryDecision("needs_changes", "available")
        }
    }

    fun normalizeInn(value: String): String = value.filterNot(Char::isWhitespace)

    fun isValidInn(inn: String): Boolean {
        if (inn.any { !it.isDigit() }) return false
        val digits = inn.map { it.digitToInt() }
        fun checksum(weights: List<Int>, length: Int) =
            (weights.zip(digits.take(length)).sumOf { (weight, digit) -> weight * digit } % 11) % 10
        return when (digits.size) {
            10 -> checksum(listOf(2, 4, 10, 3, 5, 9, 4, 6, 8), 9) == digits[9]
            12 -> checksum(listOf(7, 2, 4, 10, 3, 5, 9, 4, 6, 8, 0), 11) == digits[10] &&
                checksum(listOf(3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8), 11) == digits[11]
            else -> false
        }
    }

    fun normalizeLegalName(value: String) = value.trim().lowercase().replace(Regex("\\s+"), " ")

    fun siteHost(website: String?): String? {
        return try {
            val raw = website?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val uri = URI(if (raw.contains("://")) raw else "https://$raw")
            if (uri.scheme !in setOf("http", "https") || uri.userInfo != null || uri.host.isNullOrBlank()) return null
            uri.host.lowercase().removePrefix("www.").trimEnd('.')
        } catch (_: Exception) {
            null
        }
    }

    fun isPublicMailboxDomain(domain: String): Boolean {
        val normalized = domain.lowercase().trimEnd('.')
        return FREE_MAIL_DOMAINS.any { normalized == it || normalized.endsWith(".$it") }
    }

    private fun positiveInt(value: Any?, field: String): Int {
        val number = (value as? Number)?.toInt() ?: invalid("$field must be an integer", field)
        if (number < 1) invalid("$field must be positive", field)
        return number
    }

    private fun text(value: Any?, field: String, min: Int, max: Int): String =
        (value as? String)?.trim()?.takeIf { it.length in min..max }
            ?: invalid("$field must contain $min to $max characters", field)

    private fun optionalText(value: Any?, field: String, max: Int): String? = when (value) {
        null -> null
        is String -> value.trim().takeIf(String::isNotEmpty)?.also {
            if (it.length > max) invalid("$field must contain at most $max characters", field)
        }
        else -> invalid("$field must be a string", field)
    }

    private fun invalid(message: String, field: String? = null): Nothing =
        throw ApiException(400, "VALIDATION_ERROR", message, field)

    private val FREE_MAIL_DOMAINS = setOf(
        "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.uk", "outlook.com", "hotmail.com",
        "live.com", "msn.com", "icloud.com", "me.com", "mac.com", "mail.com", "gmx.com",
        "fastmail.com", "proton.me", "protonmail.com", "tuta.com", "tuta.io", "tutanota.com",
        "yandex.ru", "yandex.com", "ya.ru", "mail.ru", "bk.ru", "inbox.ru", "list.ru", "rambler.ru",
    )
}

internal data class ReviewInput(
    val expectedVersion: Int,
    val outcome: String,
    val evidenceReference: String,
    val registryInn: String?,
    val registryLegalName: String?,
    val registryStatus: String,
    val representativeReviewed: Boolean,
    val comment: String,
)

internal data class RegistryDecision(val requestStatus: String, val sourceCheckStatus: String)
