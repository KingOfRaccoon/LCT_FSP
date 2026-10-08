package ru.fsp.talent.profiles

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.*
import ru.fsp.talent.core.Contracts

@RestController
@RequestMapping("/api/v1")
class ProfileController(
    private val profiles: ProfileService,
    private val contracts: Contracts,
) {
    @GetMapping("/me/profile")
    fun getProfile(request: HttpServletRequest): Map<String, Any?> =
        contracts.envelope(profiles.getOwnProfile(request))

    @PatchMapping("/me/profile")
    fun patchProfile(
        request: HttpServletRequest,
        @RequestBody body: Map<String, Any?>,
    ): Map<String, Any?> = contracts.envelope(profiles.patchOwnProfile(request, body))

    @PatchMapping("/me/visibility")
    fun patchVisibility(
        request: HttpServletRequest,
        @RequestBody body: Map<String, Any?>,
    ): Map<String, Any?> = contracts.envelope(profiles.patchVisibility(request, body))

    @GetMapping("/me/company")
    fun getCompany(request: HttpServletRequest): Map<String, Any?> =
        contracts.envelope(profiles.getOwnCompany(request))

    @PatchMapping("/me/company")
    fun patchCompany(
        request: HttpServletRequest,
        @RequestBody body: Map<String, Any?>,
    ): Map<String, Any?> = contracts.envelope(profiles.patchOwnCompany(request, body))
}
