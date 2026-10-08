package ru.fsp.talent.assessment

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.security.MessageDigest

class AssessmentReleaseVerifierTest {
    private val mapper = ObjectMapper()

    @Test
    fun `current demo release verifies artifact hashes but is not eligible for real grades`() {
        val verification = verifier().verify(releaseBytes(), bankBytes(), policyBytes(), mapper)

        assertTrue(verification.integrityVerified)
        assertFalse(verification.realGradeReleaseReady)
    }

    @Test
    fun `wrong bank digest fails closed`() {
        val record = mapper.readTree(releaseBytes()) as com.fasterxml.jackson.databind.node.ObjectNode
        record.put("bank_sha256", "0".repeat(64))

        val verification = verifier().verify(
            mapper.writeValueAsBytes(record),
            bankBytes(),
            policyBytes(),
            mapper,
        )

        assertFalse(verification.integrityVerified)
        assertFalse(verification.realGradeReleaseReady)
    }

    @Test
    fun `current published demo bank stays blocked even with matching approval evidence`() {
        val policy = mapper.readTree(policyBytes()) as com.fasterxml.jackson.databind.node.ObjectNode
        policy.put("version", "1.1.0")
        val updatedPolicyBytes = mapper.writeValueAsBytes(policy)
        val record = approvedRecord(bankBytes(), updatedPolicyBytes)

        val verification = verifier().verify(
            mapper.writeValueAsBytes(record),
            bankBytes(),
            updatedPolicyBytes,
            mapper,
            evidenceFiles::get,
        )

        assertTrue(verification.integrityVerified)
        assertFalse(verification.realGradeReleaseReady)
        assertEquals("published_demo", verification.metadata["status"])
        assertEquals("not_performed", verification.metadata["content_review"])
        assertEquals("not_performed", verification.metadata["human_validation"])
    }

    @Test
    fun `approved versioned release accepts only path and digest matched evidence artifacts`() {
        val bank = mapper.readTree(bankBytes()) as com.fasterxml.jackson.databind.node.ObjectNode
        bank.put("bank_version", "backend-python-v2.0")
        val updatedBankBytes = mapper.writeValueAsBytes(bank)
        val policy = mapper.readTree(policyBytes()) as com.fasterxml.jackson.databind.node.ObjectNode
        policy.put("version", "1.1.0")
        val updatedPolicyBytes = mapper.writeValueAsBytes(policy)
        val record = approvedRecord(updatedBankBytes, updatedPolicyBytes)

        val verification = verifier().verify(
            mapper.writeValueAsBytes(record),
            updatedBankBytes,
            updatedPolicyBytes,
            mapper,
            evidenceFiles::get,
        )

        assertTrue(verification.integrityVerified)
        assertTrue(verification.realGradeReleaseReady)
    }

    @Test
    fun `malformed release record fails closed`() {
        val verification = verifier().verify(
            "{ malformed".toByteArray(),
            bankBytes(),
            policyBytes(),
            mapper,
        )

        assertFalse(verification.integrityVerified)
        assertFalse(verification.realGradeReleaseReady)
    }

    private fun verifier() = AssessmentReleaseVerifier

    private val evidenceFiles = mapOf(
        "assessment/release-evidence/content-review.json" to "synthetic content review evidence".toByteArray(),
        "assessment/release-evidence/human-validation.json" to "synthetic human validation evidence".toByteArray(),
        "assessment/release-evidence/approval.json" to "synthetic approval evidence".toByteArray(),
    )

    private fun approvedRecord(bank: ByteArray, policy: ByteArray): com.fasterxml.jackson.databind.node.ObjectNode =
        (mapper.readTree(releaseBytes()) as com.fasterxml.jackson.databind.node.ObjectNode).apply {
            put("bank_version", mapper.readTree(bank).path("bank_version").asText())
            put("bank_sha256", sha256(bank))
            put("policy_version", mapper.readTree(policy).path("version").asText())
            put("policy_sha256", sha256(policy))
            put("status", "approved_real_grade")
            put("content_review", "complete")
            put("human_validation", "complete")
            put("real_grade_release_ready", true)
            evidenceFiles.forEach { (path, bytes) ->
                val name = path.substringAfterLast('/')
                val field = when (name) {
                    "content-review.json" -> "content_review_evidence"
                    "human-validation.json" -> "human_validation_evidence"
                    else -> "approval_evidence"
                }
                set<com.fasterxml.jackson.databind.JsonNode>(
                    field,
                    mapper.createObjectNode().put("resource", path).put("sha256", sha256(bytes)),
                )
            }
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun releaseBytes() = ClassPathResource("assessment/assessment-release.v1.json").inputStream.use { it.readBytes() }
    private fun bankBytes() = ClassPathResource("assessment/question-bank.v1.json").inputStream.use { it.readBytes() }
    private fun policyBytes() = ClassPathResource("assessment/assessment-policy.v1.json").inputStream.use { it.readBytes() }
}
