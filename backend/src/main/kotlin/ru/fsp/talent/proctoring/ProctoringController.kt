package ru.fsp.talent.proctoring

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.MutationService
import ru.fsp.talent.core.Store
import ru.fsp.talent.operations.OperationsAccess

@RestController
@RequestMapping("/api/v1")
class ProctoringController(
    private val service: ProctoringService,
    private val auth: AuthAccess,
    private val operations: OperationsAccess,
    private val mutations: MutationService,
    private val contracts: Contracts,
    private val store: Store,
) {
    @GetMapping("/proctoring/config")
    fun config(request: HttpServletRequest): Map<String, Any?> {
        val actor = auth.current(request)
        if (actor.role == "candidate") auth.verified(actor)
        else operations.require(request, "proctoring_review")
        return contracts.envelope(service.config())
    }

    @PostMapping("/attempts/{attempt_id}/proctoring/session")
    fun createSession(request: HttpServletRequest, @PathVariable("attempt_id") attemptId: String,
                      @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        exactKeys(body, setOf("consent_version", "consent_document_sha256"))
        contracts.validate("ProctoringStartRequest", body)
        val actor = auth.verified(auth.candidate(request))
        return guardedMutation {
            service.authorizeSessionCreation(actor.id, attemptId, body.getValue("consent_version").toString(), body.getValue("consent_document_sha256").toString())
            mutations.run(request, "proctoring:${actor.id}", body, status = 201) {
                service.createSession(actor.id, attemptId, body.getValue("consent_version").toString(), body.getValue("consent_document_sha256").toString())
            }
        }
    }

    @GetMapping("/attempts/{attempt_id}/proctoring/session")
    fun getAttemptSession(request: HttpServletRequest, @PathVariable("attempt_id") attemptId: String): Map<String, Any?> {
        val actor = auth.candidate(request)
        return contracts.envelope(service.getAttemptSession(actor.id, attemptId))
    }

    @PostMapping("/proctoring/sessions/{session_id}/captures")
    fun rotateCapture(request: HttpServletRequest, @PathVariable("session_id") sessionId: String,
                      @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        exactKeys(body, setOf("expected_version"))
        contracts.validate("ProctoringVersionRequest", body)
        val actor = auth.verified(auth.candidate(request))
        return guardedMutation {
            service.authorizeOwnedMutation(actor.id, sessionId)
            mutations.run(request, "proctoring:${actor.id}", body) {
                service.rotateCapture(actor.id, sessionId, positiveInt(body["expected_version"], "expected_version"))
            }
        }
    }

    @PostMapping("/proctoring/sessions/{session_id}/media/{channel}/chunks/{sequence}")
    fun uploadChunk(request: HttpServletRequest, @PathVariable("session_id") sessionId: String,
                    @PathVariable("channel") channel: String, @PathVariable("sequence") sequence: Int,
                    @RequestParam("capture_id") captureId: String): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.candidate(request))
        if (request.contentType?.substringBefore(';')?.trim()?.lowercase() != "video/webm") {
            throw ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "Recording chunks must use video/webm")
        }
        if (request.contentLengthLong > service.maxChunkBytes) throw ApiException(413, "PROCTORING_CHUNK_TOO_LARGE", "Recording chunk exceeds the configured limit")
        val key = request.getHeader("Idempotency-Key") ?: throw ApiException(400, "VALIDATION_ERROR", "Idempotency-Key is required", "Idempotency-Key")
        val digest = request.getHeader("X-Content-SHA256") ?: throw ApiException(400, "VALIDATION_ERROR", "X-Content-SHA256 is required", "X-Content-SHA256")
        // This guard intentionally runs before the request stream is read, including same-key/digest replays.
        service.authorizeChunk(actor.id, sessionId, captureId, channel, sequence, digest)
        return ResponseEntity.ok(contracts.envelope(service.acceptChunk(actor.id, sessionId, captureId, channel, sequence, key, digest, request.inputStream)))
    }

    @PostMapping("/proctoring/sessions/{session_id}/events")
    fun events(request: HttpServletRequest, @PathVariable("session_id") sessionId: String,
               @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        exactKeys(body, setOf("expected_version", "events"))
        contracts.validate("ProctoringEventsRequest", body)
        val actor = auth.verified(auth.candidate(request))

        val events = (body["events"] as? List<*>)?.map { (it as? Map<*, *>)?.entries?.associate { entry -> entry.key.toString() to entry.value } ?: invalid("events") } ?: invalid("events")
        return guardedMutation {
            service.authorizeOwnedMutation(actor.id, sessionId)
            mutations.run(request, "proctoring:${actor.id}", body) {
                service.recordEvents(actor.id, sessionId, positiveInt(body["expected_version"], "expected_version"), events)
            }
        }
    }

    @PostMapping("/proctoring/sessions/{session_id}/seal")
    fun seal(request: HttpServletRequest, @PathVariable("session_id") sessionId: String,
             @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        exactKeys(body, setOf("expected_version", "capture_id", "last_sequences"))
        contracts.validate("ProctoringSealRequest", body)
        val actor = auth.verified(auth.candidate(request))
        val last = (body["last_sequences"] as? Map<*, *>)?.entries?.associate { it.key.toString() to nonnegativeInt(it.value, "last_sequences") } ?: invalid("last_sequences")
        return guardedMutation {
            service.authorizeOwnedMutation(actor.id, sessionId)
            mutations.run(request, "proctoring:${actor.id}", body) {
                service.seal(actor.id, sessionId, positiveInt(body["expected_version"], "expected_version"), body["capture_id"].toString(), last)
            }
        }
    }

    @PostMapping("/proctoring/sessions/{session_id}/revoke")
    fun revoke(request: HttpServletRequest, @PathVariable("session_id") sessionId: String,
               @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        exactKeys(body, setOf("expected_version"))
        contracts.validate("ProctoringVersionRequest", body)
        val actor = auth.candidate(request)
        return guardedMutation {
            service.authorizeRevocation(actor.id, sessionId)
            mutations.run(request, "proctoring:${actor.id}", body) {
                service.revoke(actor.id, sessionId, positiveInt(body["expected_version"], "expected_version"))
            }
        }
    }

    @GetMapping("/proctoring/sessions/{session_id}/media/{channel}")
    fun media(request: HttpServletRequest, @PathVariable("session_id") sessionId: String,
              @PathVariable("channel") channel: String, @RequestParam("capture_id") captureId: String): ResponseEntity<StreamingResponseBody> {
        val actor = auth.current(request)
        val payload = service.prepareMedia(actor.id, sessionId, captureId, channel, request.getHeader(HttpHeaders.RANGE))
        val responseHeaders = HttpHeaders().apply {
            contentType = MediaType.parseMediaType("video/webm")
            set(HttpHeaders.CACHE_CONTROL, "private, no-store, no-cache, max-age=0")
            set("X-Content-Type-Options", "nosniff")
            set("X-Accel-Buffering", "no")
            set(HttpHeaders.ACCEPT_RANGES, "bytes")
            set(HttpHeaders.CONTENT_LENGTH, payload.contentLength.toString())
            if (payload.partial) {
                set(HttpHeaders.CONTENT_RANGE, "bytes ${payload.start}-${payload.end}/${payload.totalBytes}")
            }
        }
        val body = StreamingResponseBody { output -> service.streamMedia(payload, output) }
        val status = if (payload.partial) HttpStatus.PARTIAL_CONTENT else HttpStatus.OK
        return ResponseEntity.status(status).headers(responseHeaders).body(body)
    }

    @GetMapping("/operations/proctoring/reviews")
    fun reviewQueue(request: HttpServletRequest, @RequestParam(required = false) limit: Int?,
                    @RequestParam(required = false) cursor: String?): Map<String, Any?> {
        val reviewer = operations.require(request, "proctoring_review")
        return contracts.envelope(service.reviewQueue(reviewer.id, limit, cursor))
    }

    @GetMapping("/operations/proctoring/reviews/{session_id}")
    fun reviewDetail(request: HttpServletRequest, @PathVariable("session_id") sessionId: String): Map<String, Any?> {
        val reviewer = operations.require(request, "proctoring_review")
        return contracts.envelope(service.reviewDetail(reviewer.id, sessionId))
    }

    @PostMapping("/operations/proctoring/reviews/{session_id}/decision")
    fun decision(request: HttpServletRequest, @PathVariable("session_id") sessionId: String,
                 @RequestBody body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        exactKeys(body, setOf("expected_version", "expected_attempt_version", "outcome", "reason_code", "note"))
        contracts.validate("ProctoringReviewRequest", body)
        val reviewer = operations.require(request, "proctoring_review")
        val note = body["note"] as? String ?: invalid("note")
        if (note.isBlank() || note.length > 2000) invalid("note")
        return guardedMutation {
            service.authorizeReview(reviewer.id, sessionId)
            mutations.run(request, "proctoring-review:${reviewer.id}", body) {
                service.decideReview(reviewer.id, sessionId, positiveInt(body["expected_version"], "expected_version"),
                    positiveInt(body["expected_attempt_version"], "expected_attempt_version"), body["outcome"].toString(),
                    body["reason_code"].toString(), note)
            }
        }
    }

    private fun exactKeys(body: Map<String, Any?>, expected: Set<String>) {
        if (body.keys != expected) invalid("body")
    }
    private fun positiveInt(raw: Any?, field: String): Int = when (raw) {
        is Byte -> raw.toInt()
        is Short -> raw.toInt()
        is Int -> raw
        is Long -> raw.takeIf { it <= Int.MAX_VALUE }?.toInt()
        else -> null
    }?.takeIf { it > 0 } ?: invalid(field)
    private fun nonnegativeInt(raw: Any?, field: String): Int = when (raw) {
        is Byte -> raw.toInt()
        is Short -> raw.toInt()
        is Int -> raw
        is Long -> raw.takeIf { it <= Int.MAX_VALUE }?.toInt()
        else -> null
    }?.takeIf { it in 0..2000 } ?: invalid(field)
    private fun guardedMutation(action: () -> ResponseEntity<Map<String, Any?>>): ResponseEntity<Map<String, Any?>> =
        store.tx.execute { action() } ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)
    private fun invalid(field: String): Nothing = throw ApiException(400, "VALIDATION_ERROR", "Invalid $field", field)
}
