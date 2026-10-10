package ru.fsp.talent.vacancies

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.MutationService

@RestController
@RequestMapping("/api/v1")
class VacancyController(
    private val auth: AuthAccess,
    private val vacancyService: VacancyService,
    private val mutations: MutationService,
) {

    @GetMapping("/vacancies")
    fun listVacancies(
        @RequestParam(name = "specialization_id", required = false) specializationId: String?,
        @RequestParam(name = "grade", required = false) grade: String?,
        @RequestParam(name = "limit", defaultValue = "20") limit: Int,
        @RequestParam(name = "offset", defaultValue = "0") offset: Int,
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
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        return mutations.run(request, "company:${actor.companyId}", body) {
            vacancyService.publishVacancy(actor, body)
        }
    }

    @PostMapping("/vacancies/{id}/apply")
    fun applyToVacancy(
        request: HttpServletRequest,
        @PathVariable id: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.candidate(request))
        return mutations.run(request, "user:${actor.id}", body) {
            vacancyService.applyToVacancy(actor, id, body)
        }
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
        @RequestParam(name = "vacancy_id", required = false) vacancyId: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        val items = vacancyService.listCompanyApplications(actor, vacancyId)
        return ResponseEntity.ok(mapOf("data" to mapOf("items" to items)))
    }

    @GetMapping("/vacancies/applications/{id}/contacts")
    fun applicationContacts(
        request: HttpServletRequest,
        @PathVariable id: String,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        return ResponseEntity.ok(mapOf("data" to vacancyService.applicationContacts(actor, id)))
    }

    @PostMapping("/me/applications/{id}/contact-access/revoke")
    fun revokeApplicationContactAccess(
        request: HttpServletRequest,
        @PathVariable id: String,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.candidate(request))
        val body = emptyMap<String, Any?>()
        return mutations.run(request, "user:${actor.id}", body) {
            vacancyService.revokeApplicationContactAccess(actor, id)
        }
    }

    @PatchMapping("/vacancies/applications/{id}/status")
    fun updateApplicationStatus(
        request: HttpServletRequest,
        @PathVariable id: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.employer(request))
        return mutations.run(request, "company:${actor.companyId}", body) {
            val status = body["status"]?.toString()
                ?: throw ApiException(400, "VALIDATION_ERROR", "status обязателен", "status")
            vacancyService.updateApplicationStatus(actor, id, status)
        }
    }
}
