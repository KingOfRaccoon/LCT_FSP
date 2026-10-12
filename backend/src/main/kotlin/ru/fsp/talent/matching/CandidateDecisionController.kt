package ru.fsp.talent.matching

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts

@RestController
@RequestMapping("/api/v1")
class CandidateDecisionController(
    private val auth: AuthAccess,
    private val contracts: Contracts,
    private val decisions: CandidateDecisionService,
) {
    @PostMapping("/candidates/compare")
    fun compare(request: HttpServletRequest, @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        return privateNoStore(contracts.envelope(decisions.compare(actor, body)))
    }

    @GetMapping("/needs/{need_id}/candidate-review")
    fun review(
        request: HttpServletRequest,
        @PathVariable("need_id") needId: String,
        @RequestParam(name = "candidate_id", required = false) candidateId: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        val id = candidateId ?: throw ApiException(400, "VALIDATION_ERROR", "candidate_id is required", "candidate_id")
        return privateNoStore(contracts.envelope(decisions.review(actor, needId, id)))
    }

    private fun privateNoStore(body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body)
}
