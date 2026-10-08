package ru.fsp.talent.assessment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import java.security.MessageDigest

internal data class AssessmentReleaseVerification(
    val integrityVerified: Boolean,
    val realGradeReleaseReady: Boolean,
    val metadata: Map<String, Any?>,
)

internal object AssessmentReleaseVerifier {
    fun verify(
        releaseBytes: ByteArray,
        bankBytes: ByteArray,
        policyBytes: ByteArray,
        mapper: ObjectMapper,
        evidenceLoader: (String) -> ByteArray? = ::loadEvidence,
    ): AssessmentReleaseVerification {
        val release = parse(releaseBytes, mapper)
        val bank = parse(bankBytes, mapper)
        val policy = parse(policyBytes, mapper)
        val bankSha256 = sha256(bankBytes)
        val policySha256 = sha256(policyBytes)
        val integrityVerified = release != null && bank != null && policy != null &&
            release.text("bank_version") == bank.text("bank_version") &&
            release.text("policy_id") == policy.text("policy_id") &&
            release.text("policy_version") == policy.text("version") &&
            release.text("bank_sha256").isSha256() && release.text("bank_sha256") == bankSha256 &&
            release.text("policy_sha256").isSha256() && release.text("policy_sha256") == policySha256
        val realGradeReleaseReady = integrityVerified &&
            bankSha256 != CURRENT_PUBLISHED_DEMO_BANK_SHA256 &&
            policy.text("version") != CURRENT_DEMO_POLICY_VERSION &&
            release!!.path("status").asText() == APPROVED_STATUS &&
            release.path("content_review").asText() == COMPLETED_STATUS &&
            release.path("human_validation").asText() == COMPLETED_STATUS &&
            hasVerifiedEvidence(release, "content_review_evidence", evidenceLoader) &&
            hasVerifiedEvidence(release, "human_validation_evidence", evidenceLoader) &&
            hasVerifiedEvidence(release, "approval_evidence", evidenceLoader)
        val currentPublishedDemo = integrityVerified && bankSha256 == CURRENT_PUBLISHED_DEMO_BANK_SHA256

        return AssessmentReleaseVerification(
            integrityVerified = integrityVerified,
            realGradeReleaseReady = realGradeReleaseReady,
            metadata = mapOf(
                "release_version" to release.text("release_version", RELEASE_VERSION),
                "bank_version" to bank.text("bank_version", "unknown"),
                "bank_sha256" to if (integrityVerified) release.text("bank_sha256") else bankSha256,
                "policy_id" to policy.text("policy_id", "unknown"),
                "policy_version" to release.text("policy_version", policy.text("version", "unknown")),
                "policy_sha256" to if (integrityVerified) release.text("policy_sha256") else policySha256,
                "status" to when {
                    !integrityVerified -> "unavailable"
                    currentPublishedDemo -> "published_demo"
                    else -> release.text("status", "unavailable")
                },
                "content_review" to if (integrityVerified && !currentPublishedDemo) release.text("content_review", "not_performed") else "not_performed",
                "human_validation" to if (integrityVerified && !currentPublishedDemo) release.text("human_validation", "not_performed") else "not_performed",
                "real_grade_release_ready" to realGradeReleaseReady,
            ),
        )
    }

    private fun hasVerifiedEvidence(
        release: JsonNode,
        field: String,
        evidenceLoader: (String) -> ByteArray?,
    ): Boolean {
        val evidence = release.path(field)
        if (!evidence.isObject) return false
        val path = evidence.path("resource").asText()
        val expectedSha256 = evidence.path("sha256").asText()
        if (!path.matches(EVIDENCE_PATH) || !expectedSha256.isSha256()) return false
        return runCatching {
            evidenceLoader(path)?.let(::sha256) == expectedSha256
        }.getOrDefault(false)
    }

    private fun loadEvidence(path: String): ByteArray? =
        runCatching { ClassPathResource(path).inputStream.use { it.readBytes() } }.getOrNull()

    private fun parse(bytes: ByteArray, mapper: ObjectMapper): JsonNode? =
        runCatching { mapper.readTree(bytes) }.getOrNull()?.takeIf(JsonNode::isObject)

    private fun JsonNode?.text(field: String, fallback: String = ""): String =
        this?.path(field)?.takeIf(JsonNode::isTextual)?.asText() ?: fallback

    private fun String.isSha256(): Boolean = matches(SHA256_PATTERN)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private const val RELEASE_VERSION = "assessment-release.v1"
    private const val APPROVED_STATUS = "approved_real_grade"
    private const val COMPLETED_STATUS = "complete"
    private const val CURRENT_PUBLISHED_DEMO_BANK_SHA256 = "2849ead2f937827e4ca545a77b6054f3b37916778cf5c246b3c38825120b9c7d"
    private const val CURRENT_DEMO_POLICY_VERSION = "1.0.0"
    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
    private val EVIDENCE_PATH = Regex("""assessment/release-evidence/[A-Za-z0-9._-]+\.json""")
}
