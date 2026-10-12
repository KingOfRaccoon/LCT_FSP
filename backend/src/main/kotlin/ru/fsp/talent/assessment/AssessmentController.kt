package ru.fsp.talent.assessment

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.MutationService

@RestController
@RequestMapping("/api/v1")
class AssessmentController(
    private val assessment: AssessmentService,
    private val auth: AuthAccess,
    private val contracts: Contracts,
    private val mutations: MutationService,
) {
    @GetMapping("/taxonomy")
    fun taxonomy(): Map<String, Any?> = contracts.envelope(assessment.taxonomy())

    @PostMapping("/attempts")
    fun createAttempt(
        request: HttpServletRequest,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        contracts.validate("CreateAttemptRequest", body)
        val actor = auth.verified(auth.candidate(request))
        return mutations.run(request, "assessment:${actor.id}", body, status = 201) {
            assessment.createAttempt(actor.id, body.getValue("requested_grade").toString())
        }
    }

    @GetMapping("/attempts/{attempt_id}")
    fun getAttempt(request: HttpServletRequest, @PathVariable("attempt_id") attemptId: String): Map<String, Any?> {
        val actor = auth.candidate(request)
        return contracts.envelope(assessment.getAttempt(actor.id, attemptId))
    }

    @PutMapping("/attempts/{attempt_id}/answers")
    fun autosave(
        request: HttpServletRequest,
        @PathVariable("attempt_id") attemptId: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        contracts.validate("AnswerRequest", body)
        val actor = auth.verified(auth.candidate(request))
        val expectedVersion = (body["expected_version"] as Number).toInt()
        val answers = (body["answers"] as List<*>).map { (it as Map<*, *>).entries.associate { entry -> entry.key.toString() to entry.value } }
        val response = mutations.run(request, "assessment:${actor.id}", body) {
            assessment.autosave(actor.id, attemptId, expectedVersion, answers)
        }
        if (response.body?.get("data").isDeadlineCommitMarker() == true) {
            throw ApiException(409, "ATTEMPT_DEADLINE_REACHED", "The attempt deadline has passed")
        }
        return response
    }

    @PostMapping("/attempts/{attempt_id}/submit")
    fun submit(
        request: HttpServletRequest,
        @PathVariable("attempt_id") attemptId: String,
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>> {
        val rawVersion = body["expected_version"]
        val version = when (rawVersion) {
            is Byte -> rawVersion.toLong()
            is Short -> rawVersion.toLong()
            is Int -> rawVersion.toLong()
            is Long -> rawVersion
            else -> null
        }
        if (body.keys != setOf("expected_version") || version == null || version !in 1..Int.MAX_VALUE.toLong()) {
            throw ApiException(400, "VALIDATION_ERROR", "expected_version must be a positive integer", "expected_version")
        }
        val actor = auth.verified(auth.candidate(request))
        val expectedVersion = version.toInt()
        return mutations.run(request, "assessment:${actor.id}", body) {
            assessment.submit(actor.id, attemptId, expectedVersion)
        }
    }

    @GetMapping("/me/attempts")
    fun history(
        request: HttpServletRequest,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
    ): Map<String, Any?> {
        val actor = auth.candidate(request)
        return contracts.envelope(assessment.attemptHistory(actor.id, limit, cursor))
    }

    private fun Any?.isDeadlineCommitMarker(): Boolean =
        this is Map<*, *> && this["__assessment_error_after_commit"] == "ATTEMPT_DEADLINE_REACHED"
}
