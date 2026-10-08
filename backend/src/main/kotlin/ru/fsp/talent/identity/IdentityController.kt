package ru.fsp.talent.identity

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import ru.fsp.talent.core.Contracts
import java.time.Duration

@RestController
@RequestMapping("/api/v1/auth")
class IdentityController(
    private val identity: IdentityService,
    private val contracts: Contracts,
    @Value("\${fsp.session-cookie-secure:false}") private val secureCookie: Boolean,
) {
    @PostMapping("/register")
    fun register(
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Map<String, Any?>> {
        requireIdempotencyKey(request)
        val result = identity.register(request, response, body)
        return ResponseEntity.status(result.status).body(result.body)
    }

    @PostMapping("/confirm-email")
    fun confirmEmail(request: HttpServletRequest, @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        requireIdempotencyKey(request)
        return identity.confirmEmail(request, body)
    }

    @PostMapping("/resend-confirmation")
    fun resendConfirmation(request: HttpServletRequest, @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        requireIdempotencyKey(request)
        return identity.resendConfirmation(request, body)
    }

    @PostMapping("/login")
    fun login(
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Map<String, Any?>> {
        requireIdempotencyKey(request)
        val result = identity.login(body, request, response)
        return ResponseEntity.status(result.status).body(result.body)
    }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest, response: HttpServletResponse): Map<String, Any?> {
        requireIdempotencyKey(request)
        val result = identity.logout(request)
        response.addHeader(
            HttpHeaders.SET_COOKIE,
            ResponseCookie.from("fsp_session", "").httpOnly(true).secure(secureCookie)
                .sameSite("Lax").path("/").maxAge(Duration.ZERO).build().toString(),
        )
        return contracts.envelope(result)
    }

    @GetMapping("/me")
    fun me(request: HttpServletRequest): Map<String, Any?> = contracts.envelope(identity.currentUser(request))

    private fun requireIdempotencyKey(request: HttpServletRequest) {
        val key = request.getHeader("Idempotency-Key")?.trim().orEmpty()
        if (key.length !in 16..128) {
            throw ru.fsp.talent.core.ApiException(400, "VALIDATION_ERROR", "A valid Idempotency-Key is required", "Idempotency-Key")
        }
    }
}
