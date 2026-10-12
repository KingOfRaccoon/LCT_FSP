package ru.fsp.talent.consents

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.MutationService

@RestController
@RequestMapping("/api/v1/me/consents")
class ConsentController(
    private val consents: ConsentService,
    private val auth: AuthAccess,
    private val contracts: Contracts,
    private val mutations: MutationService,
) {
    @GetMapping
    fun getOwn(request: HttpServletRequest): Map<String, Any?> =
        contracts.envelope(consents.getOwn(auth.current(request).id))

    @PostMapping
    fun updateOwn(
        request: HttpServletRequest,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        contracts.validate("ConsentMutationRequest", body)
        val actor = auth.current(request)
        return mutations.run(request, "consent:${actor.id}", body) {
            consents.update(actor.id, actor.role, body)
        }
    }
}
