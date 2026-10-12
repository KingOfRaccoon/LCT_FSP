package ru.fsp.talent.matching

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.invitations.requireUuid

@RestController
@RequestMapping("/api/v1/candidates")
class PublicCandidateController(
    private val auth: AuthAccess,
    private val matching: MatchingService,
    private val contracts: Contracts,
) {
    @GetMapping("/{candidate_id}")
    fun profile(request: HttpServletRequest, @PathVariable("candidate_id") candidateId: String): Map<String, Any?> {
        val actor = auth.current(request)
        if (actor.role == "employer") auth.verified(actor)
        requireUuid(candidateId, "candidate_id")
        return contracts.envelope(matching.publicCandidateById(candidateId))
    }
}
