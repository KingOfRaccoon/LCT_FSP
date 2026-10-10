package ru.fsp.talent.vacancies

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import ru.fsp.talent.core.AuthAccess

@RestController
@RequestMapping("/api/v1")
class VacancyController(
    private val auth: AuthAccess,
    private val vacancyService: VacancyService
) {

    @GetMapping("/vacancies")
    fun listVacancies(
        @RequestParam(name = "specialization_id", required = false) specializationId: String?,
        @RequestParam(name = "grade", required = false) grade: String?,
        @RequestParam(name = "limit", defaultValue = "20") limit: Int,
        @RequestParam(name = "offset", defaultValue = "0") offset: Int
    ): ResponseEntity<Map<String, Any?>> {
        val items = vacancyService.listVacancies(specializationId, grade, limit, offset)
        return ResponseEntity.ok(mapOf("data" to mapOf("items" to items)))
    }

    @GetMapping("/vacancies/{id}")
    fun getVacancy(@PathVariable id: String): ResponseEntity<Map<String, Any?>> {
        val vacancy = vacancyService.getVacancy(id)
        return ResponseEntity.ok(mapOf("data" to vacancy))
    }

    @PostMapping("/vacancies")
    fun publishVacancy(
        request: HttpServletRequest,
        @RequestBody body: Map<String, Any?>
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        val created = vacancyService.publishVacancy(actor, body)
        return ResponseEntity.ok(mapOf("data" to created))
    }

    @PostMapping("/vacancies/{id}/apply")
    fun applyToVacancy(
        request: HttpServletRequest,
        @PathVariable id: String,
        @RequestBody(required = false) body: Map<String, Any?>?
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.candidate(request))
        val coverLetter = body?.get("cover_letter")?.toString()
        val result = vacancyService.applyToVacancy(actor, id, coverLetter)
        return ResponseEntity.ok(mapOf("data" to result))
    }

    @GetMapping("/me/applications")
    fun listMyApplications(request: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.candidate(request))
        val items = vacancyService.listCandidateApplications(actor)
        return ResponseEntity.ok(mapOf("data" to mapOf("items" to items)))
    }

    @GetMapping("/vacancies/company/applications")
    fun listCompanyApplications(
        request: HttpServletRequest,
        @RequestParam(name = "vacancy_id", required = false) vacancyId: String?
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        val items = vacancyService.listCompanyApplications(actor, vacancyId)
        return ResponseEntity.ok(mapOf("data" to mapOf("items" to items)))
    }

    @PatchMapping("/vacancies/applications/{id}/status")
    fun updateApplicationStatus(
        request: HttpServletRequest,
        @PathVariable id: String,
        @RequestBody body: Map<String, Any?>
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        val status = body["status"]?.toString() ?: "reviewed"
        val updated = vacancyService.updateApplicationStatus(actor, id, status)
        return ResponseEntity.ok(mapOf("data" to updated))
    }
}
