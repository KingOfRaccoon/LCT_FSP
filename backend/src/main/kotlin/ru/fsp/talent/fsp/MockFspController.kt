package ru.fsp.talent.fsp

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import ru.fsp.talent.core.Contracts

@RestController
@RequestMapping("/api/v1/me")
class MockFspController(
    private val fsp: MockFspService,
    private val contracts: Contracts,
) {
    @PostMapping("/fsp-link")
    fun link(request: HttpServletRequest, @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(201).body(contracts.envelope(fsp.link(request, body)))

    @DeleteMapping("/fsp-link")
    fun unlink(request: HttpServletRequest): Map<String, Any?> =
        contracts.envelope(fsp.unlink(request))

    @GetMapping("/fsp-evidence")
    fun getEvidence(request: HttpServletRequest): Map<String, Any?> =
        contracts.envelope(fsp.getOwnEvidence(request))
}
