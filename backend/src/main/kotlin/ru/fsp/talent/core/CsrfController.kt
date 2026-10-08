package ru.fsp.talent.core

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class CsrfController(private val sessions: SessionSupport) {
    @GetMapping("/api/v1/auth/csrf")
    fun csrf(request: HttpServletRequest, response: HttpServletResponse): Map<String, Any?> {
        val session = sessions.csrf(request, response)
        return mapOf("data" to mapOf("csrf_token" to session.csrfToken, "expires_at" to session.expiresAt))
    }
}
