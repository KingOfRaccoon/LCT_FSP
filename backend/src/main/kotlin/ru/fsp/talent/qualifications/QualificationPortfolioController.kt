package ru.fsp.talent.qualifications

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts

@RestController
@RequestMapping("/api/v1")
class QualificationPortfolioController(
    private val portfolio: QualificationPortfolioService,
    private val auth: AuthAccess,
    private val contracts: Contracts,
) {
    @GetMapping("/me/qualifications")
    fun getOwn(request: HttpServletRequest): Map<String, Any?> =
        contracts.envelope(portfolio.getOwn(auth.candidate(request).id))
}
