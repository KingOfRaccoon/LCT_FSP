package ru.fsp.talent.shorttasks

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
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.MutationService

@RestController
@RequestMapping("/api/v1/short-tasks")
class ShortTaskController(
    private val auth: AuthAccess,
    private val shortTasks: ShortTaskService,
    private val mutations: MutationService,
    private val contracts: Contracts,
) {
    @GetMapping
    fun list(
        request: HttpServletRequest,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = readActor(request)
        return noStore(contracts.envelope(shortTasks.list(actor, limit, cursor)))
    }

    @PostMapping
    fun create(request: HttpServletRequest, @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        val response = mutations.run(request, "short-tasks:${actor.id}", body, 201) { shortTasks.create(actor, body) }
        return noStore(response)
    }

    @GetMapping("/{id}")
    fun get(request: HttpServletRequest, @PathVariable("id") id: String): ResponseEntity<Map<String, Any?>> {
        val actor = readActor(request)
        return noStore(contracts.envelope(shortTasks.get(actor, id)))
    }

    @PostMapping("/{id}/accept")
    fun accept(request: HttpServletRequest, @PathVariable("id") id: String, @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        val actor = auth.candidate(request)
        return noStore(mutate(request, actor.id, body) { shortTasks.accept(actor, id, body) })
    }

    @PostMapping("/{id}/decline")
    fun decline(request: HttpServletRequest, @PathVariable("id") id: String, @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        val actor = auth.candidate(request)
        return noStore(mutate(request, actor.id, body) { shortTasks.decline(actor, id, body) })
    }

    @PostMapping("/{id}/submit")
    fun submit(request: HttpServletRequest, @PathVariable("id") id: String, @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        val actor = auth.candidate(request)
        return noStore(mutate(request, actor.id, body) { shortTasks.submit(actor, id, body) })
    }

    @PostMapping("/{id}/review")
    fun review(request: HttpServletRequest, @PathVariable("id") id: String, @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        val response = shortTasks.withCurrentCompanySharing(actor, id) {
            mutate(request, actor.id, body) { shortTasks.review(actor, id, body) }
        }
        return noStore(response)
    }

    @PostMapping("/{id}/withdraw")
    fun withdraw(request: HttpServletRequest, @PathVariable("id") id: String, @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        val actor = readActor(request)
        return noStore(mutate(request, actor.id, body) { shortTasks.withdraw(actor, id, body) })
    }

    @PostMapping("/{id}/revoke-sharing")
    fun revokeSharing(request: HttpServletRequest, @PathVariable("id") id: String, @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        val actor = auth.candidate(request)
        return noStore(mutate(request, actor.id, body) { shortTasks.revokeSharing(actor, id, body) })
    }

    private fun mutate(
        request: HttpServletRequest,
        actorId: String,
        body: Map<String, Any?>,
        effect: () -> Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> = mutations.run(request, "short-tasks:$actorId", body) { effect() }

    private fun readActor(request: HttpServletRequest): Actor {
        val actor = auth.current(request)
        return when (actor.role) {
            "candidate" -> actor
            "employer" -> auth.verified(actor)
            else -> throw ApiException(403, "FORBIDDEN", "Candidate or employer role required")
        }
    }

    private fun noStore(response: ResponseEntity<Map<String, Any?>>): ResponseEntity<Map<String, Any?>> {
        val headers = HttpHeaders().apply {
            putAll(response.headers)
            set(HttpHeaders.CACHE_CONTROL, "private, no-store")
        }
        return ResponseEntity(response.body, headers, response.statusCode)
    }

    private fun noStore(body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body)
}
