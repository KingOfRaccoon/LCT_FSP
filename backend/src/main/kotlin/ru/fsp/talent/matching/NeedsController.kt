package ru.fsp.talent.matching

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.MutationService

@RestController
@RequestMapping("/api/v1")
class NeedsController(
    private val auth: AuthAccess,
    private val needs: NeedService,
    private val mutations: MutationService,
    private val contracts: Contracts,
) {
    @PostMapping("/needs")
    fun create(request: HttpServletRequest, @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        return mutations.run(request, "needs:${actor.companyId}", body, 201) { needs.create(actor, body) }
    }

    @GetMapping("/needs")
    fun list(
        request: HttpServletRequest,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): Map<String, Any?> {
        val actor = auth.verified(auth.employer(request))
        if (limit !in 1..100 || (cursor?.length ?: 0) > 512) {
            throw ru.fsp.talent.core.ApiException(400, "VALIDATION_ERROR", "Invalid page parameters")
        }
        return contracts.envelope(needs.list(actor, limit, cursor))
    }

    @GetMapping("/needs/{need_id}")
    fun get(request: HttpServletRequest, @PathVariable("need_id") id: String): Map<String, Any?> {
        val actor = auth.verified(auth.employer(request))
        return contracts.envelope(needs.get(actor, id))
    }

    @PatchMapping("/needs/{need_id}")
    fun patch(
        request: HttpServletRequest,
        @PathVariable("need_id") id: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        return mutations.run(request, "needs:${actor.companyId}", body) { needs.patch(actor, id, body) }
    }

    @PostMapping("/needs/{need_id}/competencies/confirm")
    fun confirm(
        request: HttpServletRequest,
        @PathVariable("need_id") id: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        return mutations.run(request, "needs:${actor.companyId}", body) { needs.confirm(actor, id, body) }
    }
}
