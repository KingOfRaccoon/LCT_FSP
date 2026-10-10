package ru.fsp.talent.operations

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.ApiException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@RestController
class OperationsController(
    private val access: OperationsAccess,
    private val snapshots: OperationsSnapshotService,
    private val metrics: OperationalMetrics,
    private val scrapeToken: OperationsScrapeToken,
) {
    @GetMapping("/api/v1/operations/summary")
    fun summary(request: HttpServletRequest): Map<String, Any?> {
        access.require(request, "monitor_read")
        return mapOf("data" to snapshots.snapshot())
    }

    @GetMapping("/api/v1/operations/metrics")
    fun prometheus(request: HttpServletRequest): ResponseEntity<String> {
        requireMetricsAccess(request)
        val body = metrics.renderPrometheus(snapshots.snapshot())
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/plain; version=0.0.4; charset=utf-8"))
            .body(body)
    }

    private fun requireMetricsAccess(request: HttpServletRequest) {
        val authorization = request.getHeader("Authorization")
        if (authorization != null) {
            if (!scrapeToken.matchesAuthorization(authorization)) {
                throw ApiException(401, "UNAUTHENTICATED", "Monitoring access required")
            }
            return
        }
        access.require(request, "monitor_read")
    }
}

/** Optional machine-only bearer credential, read once from a trusted file or direct property. */
@Component
class OperationsScrapeToken(
    @Value("\${fsp.monitoring.scrape-token-file:}") tokenFile: String,
    @Value("\${fsp.monitoring.scrape-token:}") directToken: String,
) {
    private val fileConfigured = tokenFile.isNotBlank()
    private val configuredToken: String = try {
        if (fileConfigured) Files.readString(Path.of(tokenFile)).trim() else directToken.trim()
    } catch (_: Exception) {
        throw IllegalStateException("Monitoring scrape token configuration could not be loaded")
    }
    private val configuredDigest = digest(configuredToken)

    init {
        if (fileConfigured && configuredToken.isEmpty()) {
            throw IllegalStateException("Monitoring scrape token configuration is invalid")
        }
        if (configuredToken.isNotEmpty() && !isStrongEnough(configuredToken)) {
            throw IllegalStateException("Monitoring scrape token configuration is invalid")
        }
    }

    fun matchesAuthorization(authorization: String): Boolean {
        val supplied = authorization.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: ""
        val suppliedDigest = digest(supplied)
        val equal = MessageDigest.isEqual(configuredDigest, suppliedDigest)
        return configuredToken.isNotEmpty() && supplied.isNotEmpty() && equal
    }

    private fun isStrongEnough(token: String): Boolean {
        val normalized = token.lowercase()
        return token.length >= 32 &&
            WEAK_TOKENS.none(normalized::contains) &&
            token.toSet().size > 4
    }

    private fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    companion object {
        private val WEAK_TOKENS = setOf("password", "changeme", "change-me", "development", "demo", "test")
    }
}
