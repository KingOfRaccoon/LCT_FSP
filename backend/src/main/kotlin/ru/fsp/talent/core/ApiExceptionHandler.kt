package ru.fsp.talent.core

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.TransientDataAccessException
import org.springframework.jdbc.CannotGetJdbcConnectionException
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.multipart.support.MissingServletRequestPartException

@RestControllerAdvice
class ApiExceptionHandler {
    private val log=LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(NoResourceFoundException::class)
    fun notFound() = ResponseEntity.status(404).body(mapOf("error" to mapOf(
        "code" to "NOT_FOUND", "message" to "Resource not found", "retryable" to false, "eligible_at" to null, "active_attempt_id" to null)))
    @ExceptionHandler(ApiException::class)
    fun apiException(error: ApiException): ResponseEntity<Map<String, Any?>> {
        val details = linkedMapOf<String, Any?>(
            "code" to error.code,
            "message" to error.message,
            "retryable" to error.retryable,
            "eligible_at" to error.eligibleAt,
            "active_attempt_id" to error.activeAttemptId,
        )
        error.field?.let { details["field"] = it }
        error.currentVersion?.let { details["current_version"] = it }
        error.eligibleAt?.let { details["eligible_at"] = it }
        error.activeAttemptId?.let { details["active_attempt_id"] = it }
        val response = ResponseEntity.status(error.status)
        if (error.status == 429) {
            val wait = error.eligibleAt?.let { Duration.between(Instant.now(), it).seconds.coerceAtLeast(1) } ?: 60
            response.header("Retry-After", wait.toString())
        }
        return response.body(mapOf("error" to details))
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun uploadTooLarge() = apiException(ApiException(413, "RESUME_IMPORT_TOO_LARGE", "PDF должен быть не больше 5 MiB", "file"))

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestPartException::class,
        MissingServletRequestParameterException::class,
    )
    fun badRequest(): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.badRequest().body(
            mapOf("error" to mapOf("code" to "VALIDATION_ERROR", "message" to "Invalid request", "retryable" to false, "eligible_at" to null, "active_attempt_id" to null)),
        )

    @ExceptionHandler(HttpMediaTypeNotAcceptableException::class)
    fun notAcceptable(): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(
            mapOf("error" to mapOf(
                "code" to "VALIDATION_ERROR",
                "message" to "Choose a response media type supported by this resource.",
                "retryable" to false,
                "eligible_at" to null,
                "active_attempt_id" to null,
            )),
        )

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflict() = ResponseEntity.status(409).body(mapOf("error" to mapOf(
        "code" to "VERSION_CONFLICT", "message" to "Данные изменились или операция уже выполнена.", "retryable" to false, "eligible_at" to null, "active_attempt_id" to null)))

    @ExceptionHandler(TransientDataAccessException::class, CannotGetJdbcConnectionException::class)
    fun unavailable() = ResponseEntity.status(503).body(mapOf("error" to mapOf(
        "code" to "DEPENDENCY_UNAVAILABLE", "message" to "Хранилище временно недоступно.", "retryable" to true, "eligible_at" to null, "active_attempt_id" to null)))

    @ExceptionHandler(Exception::class)
    fun unexpected(error:Exception): ResponseEntity<Map<String, Any?>> {
        // Class and code frames are enough to diagnose a failure without logging bodies or secrets.
        val cause=generateSequence<Throwable>(error){it.cause}.last()
        log.error("Unhandled API failure {} at {}",cause.javaClass.name,
            cause.stackTrace.take(5).joinToString(" | "))
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            mapOf("error" to mapOf("code" to "INTERNAL_ERROR", "message" to "Request failed", "retryable" to false, "eligible_at" to null, "active_attempt_id" to null)),
        )
    }
}
