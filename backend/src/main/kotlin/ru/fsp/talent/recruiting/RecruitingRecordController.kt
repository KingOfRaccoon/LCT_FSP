package ru.fsp.talent.recruiting

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.MutationService

@RestController
@RequestMapping("/api/v1/recruiting/records")
class RecruitingRecordController(
    private val auth: AuthAccess,
    private val records: RecruitingRecordService,
    private val mutations: MutationService,
    private val contracts: Contracts,
) {
    @GetMapping
    fun list(
        request: HttpServletRequest,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false, name = "need_id") needId: String?,
        @RequestParam(required = false, name = "candidate_id") candidateId: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(contracts.envelope(records.list(actor, limit, cursor, needId, candidateId)))
    }

    @PutMapping("/{candidate_id}")
    fun put(
        request: HttpServletRequest,
        @PathVariable("candidate_id") candidateId: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        val response = mutations.run(request, "recruiting-records:${actor.companyId}", body) {
            records.upsert(actor, candidateId, body)
        }
        val headers = HttpHeaders().apply {
            putAll(response.headers)
            set(HttpHeaders.CACHE_CONTROL, "private, no-store")
        }
        return ResponseEntity(response.body, headers, response.statusCode)
    }
}
