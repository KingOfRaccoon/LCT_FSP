package ru.fsp.talent.matching

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts

@RestController
@RequestMapping("/api/v1/candidates")
class CandidateBankController(
    private val auth: AuthAccess,
    private val contracts: Contracts,
    private val matching: MatchingService,
) {
    @PostMapping("/search")
    fun search(
        request: HttpServletRequest,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val employer = auth.verified(auth.employer(request))
        contracts.validate("CandidateBankSearchRequest", body)
        val filters = CandidateBankFilters(
            specializationId = body["specialization_id"] as? String,
            grade = body["grade"] as? String,
            categoryStatus = body["category_status"] as? String ?: "any",
            skillIds = (body["skill_ids"] as? Iterable<*>)?.mapNotNull { it as? String }?.sorted().orEmpty(),
            fspStatus = body["fsp_status"] as? String ?: "any",
        )
        val limit = (body["limit"] as? Number)?.toInt() ?: DEFAULT_LIMIT
        val cursor = body["cursor"] as? String
        val page = matching.searchCandidateBank(employer.companyId!!, filters, limit, cursor)
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(contracts.envelope(page))
    }

    private companion object {
        const val DEFAULT_LIMIT = 20
    }
}
