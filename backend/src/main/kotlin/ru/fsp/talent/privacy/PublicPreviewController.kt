package ru.fsp.talent.privacy

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.matching.MatchingService

@RestController
@RequestMapping("/api/v1")
class PublicPreviewController(
    private val auth: AuthAccess,
    private val matching: MatchingService,
    private val contracts: Contracts,
) {
    @GetMapping("/me/public-preview")
    fun preview(request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val actor = auth.candidate(request)
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(contracts.envelope(matching.previewOwnCandidate(actor.id)))
    }
}
