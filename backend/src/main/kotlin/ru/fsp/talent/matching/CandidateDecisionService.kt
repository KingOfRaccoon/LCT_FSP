package ru.fsp.talent.matching

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.Store
import ru.fsp.talent.privacy.PublicTextRedactor
import java.util.UUID

@Component
class CandidateDecisionService(
    private val store: Store,
    private val contracts: Contracts,
    private val needs: NeedService,
    private val matching: MatchingService,
    private val mapper: ObjectMapper,
) {
    private val interviewTemplate: Map<String, Any?> by lazy {
        ClassPathResource("contracts/interview-kit.v1.json").inputStream.use {
            mapper.readValue(it, object : TypeReference<Map<String, Any?>>() {})
        }
    }

    fun compare(actor: Actor, request: Map<String, Any?>): Map<String, Any?> {
        needs.requireOwner(actor)
        contracts.validate("CandidateComparisonRequest", request)
        val needId = request["need_id"]?.toString() ?: invalid("need_id is required", "need_id")
        val candidateIds = (request["candidate_ids"] as? Iterable<*>)?.mapNotNull { it?.toString() }.orEmpty()
        if (candidateIds.size !in 2..3 || candidateIds.distinct().size != candidateIds.size) {
            invalid("Choose two or three different candidates", "candidate_ids")
        }
        candidateIds.forEach { parseUuid(it, "candidate_ids") }
        val need = NeedService.needDto(needs.getNeedForSearch(actor, needId))
        val items = candidateIds.map { id -> reviewItem(need, id) }
        return mapOf(
            "need" to need,
            "policy_version" to MatchingPolicy.POLICY_VERSION,
            "generated_at" to store.now().toString(),
            "items" to items,
        )
    }

    fun review(actor: Actor, needId: String, candidateId: String): Map<String, Any?> {
        needs.requireOwner(actor)
        parseUuid(needId, "need_id")
        parseUuid(candidateId, "candidate_id")
        val need = NeedService.needDto(needs.getNeedForSearch(actor, needId))
        val item = reviewItem(need, candidateId)
        return mapOf(
            "need" to need,
            "candidate" to item.getValue("candidate"),
            "fit" to item.getValue("fit"),
            "interview_kit" to item.getValue("interview_kit"),
        )
    }

    private fun reviewItem(need: Map<String, Any?>, candidateId: String): Map<String, Any?> {
        // Read policy evidence and the public projection afresh for every request. Either missing
        // result is deliberately the same 404, so no partial comparison reveals hidden profiles.
        val policyProfile = matching.policyProfileById(candidateId)
            ?: throw ApiException(404, "NOT_FOUND", "Candidate not found")
        val publicCandidate = matching.publicCandidateById(candidateId)
        // Keep raw policy snapshots for classification, but sanitize every rendered text value.
        val fit = publicFit(MatchingPolicy.explainFit(policyProfile, need))
        return mapOf(
            "candidate" to publicCandidate,
            "fit" to fit,
            "interview_kit" to InterviewKitFactory.build(interviewTemplate, need, candidateId, fit),
        )
    }

    private fun publicFit(fit: Map<String, Any?>): Map<String, Any?> {
        val checks = (fit["checks"] as? Iterable<*>)?.mapNotNull { raw ->
            val check = (raw as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: return@mapNotNull null
            check.toMutableMap().apply {
                put("expected", PublicTextRedactor.redact(check["expected"] as? String, 160))
                put("observed", PublicTextRedactor.redact(check["observed"] as? String, 160))
                put("reason", PublicTextRedactor.redact(check["reason"] as? String, 300))
            }
        }.orEmpty()
        return fit + mapOf(
            "checks" to checks,
            "summary" to PublicTextRedactor.redact(fit["summary"] as? String, 1000),
        )
    }

    private fun parseUuid(value: String, field: String): UUID = runCatching { UUID.fromString(value) }
        .getOrElse { invalid("A valid UUID is required", field) }

    private fun invalid(message: String, field: String): Nothing =
        throw ApiException(400, "VALIDATION_ERROR", message, field)
}
