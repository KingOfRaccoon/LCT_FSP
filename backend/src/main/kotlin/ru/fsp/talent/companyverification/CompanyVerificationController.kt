package ru.fsp.talent.companyverification

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.MutationService
import ru.fsp.talent.core.RateLimits
import ru.fsp.talent.operations.OperationsAccess

@RestController
class CompanyVerificationController(
    private val auth: AuthAccess,
    private val operationsAccess: OperationsAccess,
    private val service: CompanyVerificationService,
    private val contracts: Contracts,
    private val mutations: MutationService,
    private val rateLimits: RateLimits,
) {
    @GetMapping("/api/v1/me/company/verification")
    fun getOwn(request: HttpServletRequest): Map<String, Any?> {
        val actor = auth.verified(auth.employer(request))
        return contracts.envelope(service.getOwn(actor))
    }

    @PostMapping("/api/v1/me/company/verification/requests")
    fun requestVerification(
        request: HttpServletRequest,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        service.requireOwner(actor)
        val expectedCompanyVersion = CompanyVerificationRules.expectedCompanyVersion(body)
        return mutations.run(request, "company-verification:${actor.companyId}", body, status = 201) {
            service.requestVerification(actor, expectedCompanyVersion)
        }
    }

    @PostMapping("/api/v1/me/company/verification/requests/{request_id}/registry-check")
    fun checkRegistry(
        request: HttpServletRequest,
        @PathVariable("request_id") requestId: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        service.requireOwner(actor)
        val validRequestId = CompanyVerificationRules.requestId(requestId)
        val expectedVersion = CompanyVerificationRules.expectedRequestVersion(body)
        val scope = "company-verification:${actor.companyId}"
        mutations.replayIfPresent(request, scope, body)?.let { return it }
        rateLimits.consume(RateLimits.Rule("company-registry-check-user", actor.id, 10, 60))
        val lookup = service.lookupRegistryOutsideTransaction(actor, validRequestId, expectedVersion)
        return mutations.run(request, scope, body) {
            service.recordRegistryLookup(actor, validRequestId, expectedVersion, lookup)
        }
    }

    @GetMapping("/api/v1/operations/company-verifications")
    fun listForReview(request: HttpServletRequest): Map<String, Any?> {
        operationsAccess.require(request, "company_review")
        return contracts.envelope(service.listForReview())
    }

    @PostMapping("/api/v1/operations/company-verifications/{request_id}/review")
    fun review(
        request: HttpServletRequest,
        @PathVariable("request_id") requestId: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val reviewer = operationsAccess.require(request, "company_review")
        val validRequestId = CompanyVerificationRules.requestId(requestId)
        val review = CompanyVerificationRules.review(body)
        return mutations.run(request, "company-verification-review:$validRequestId", body) {
            service.review(validRequestId, reviewer, review)
        }
    }
}
