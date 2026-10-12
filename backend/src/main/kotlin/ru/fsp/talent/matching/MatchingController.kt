package ru.fsp.talent.matching

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.RateLimits

@RestController
@RequestMapping("/api/v1/matches")
class MatchingController(
    private val auth: AuthAccess,
    private val contracts: Contracts,
    private val needs: NeedService,
    private val matching: MatchingService,
    private val rates: RateLimits,
) {
    @PostMapping("/search")
    fun search(request: HttpServletRequest, @RequestBody body: Map<String, Any?>): Map<String, Any?> {
        val actor = auth.verified(auth.employer(request))
        contracts.validate("SearchRequest", body)
        val key = request.getHeader("Idempotency-Key")?.trim()
        if (key.isNullOrEmpty() || key.length !in 16..128) {
            throw ApiException(400, "VALIDATION_ERROR", "A valid Idempotency-Key is required", "Idempotency-Key")
        }
        val needId = body["need_id"]?.toString()
            ?: throw ApiException(400, "VALIDATION_ERROR", "need_id is required", "need_id")
        val need = needs.getNeedForSearch(actor, needId)
        rates.consume(RateLimits.Rule("match-search", actor.companyId!!, 60, 60))
        val limit = (body["limit"] as? Number)?.toInt() ?: 20
        val strict = body["strict_confirmed_only"] == true
        return contracts.envelope(matching.search(need, limit, body["cursor"]?.toString(), strict))
    }
}
