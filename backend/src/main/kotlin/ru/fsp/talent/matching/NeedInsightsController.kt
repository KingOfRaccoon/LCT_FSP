package ru.fsp.talent.matching

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts

@RestController
@RequestMapping("/api/v1/needs")
class NeedInsightsController(
    private val auth: AuthAccess,
    private val contracts: Contracts,
    private val insights: NeedInsightsService,
) {
    @GetMapping("/{need_id}/insights")
    fun get(
        request: HttpServletRequest,
        @PathVariable("need_id") needId: String,
        @RequestParam(name = "expected_version", required = false) expectedVersion: Long?,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        val version = expectedVersion?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
            ?: throw ApiException(400, "VALIDATION_ERROR", "expected_version must be a positive integer", "expected_version")
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(contracts.envelope(insights.get(actor, needId, version)))
    }
}
