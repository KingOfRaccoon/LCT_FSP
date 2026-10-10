package ru.fsp.talent.companyverification

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import ru.fsp.talent.operations.OperationalMetrics
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

data class RegistryLookup(
    val available: Boolean,
    val provider: String? = null,
    val inn: String? = null,
    val legalName: String? = null,
    val status: String = "UNKNOWN",
    val updatedAt: String? = null,
    val errorCode: String? = null,
)

interface CompanyRegistryProvider {
    fun lookup(inn: String): RegistryLookup
}

interface RegistryHttpTransport {
    fun lookup(token: String, inn: String): ByteArray
}

@Component
class JavaHttpClientRegistryTransport : RegistryHttpTransport {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun lookup(token: String, inn: String): ByteArray {
        val body = """{"query":"$inn","count":1,"type":"${if (inn.length == 10) "LEGAL" else "INDIVIDUAL"}","branch_type":"MAIN"}"""
        val request = HttpRequest.newBuilder(ENDPOINT)
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Token $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandler { LimitedByteArraySubscriber(MAX_RESPONSE_BYTES) })
        if (response.statusCode() !in 200..299) throw IOException("Registry provider returned HTTP ${response.statusCode()}")
        return response.body()
    }

    private class LimitedByteArraySubscriber(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val output = ByteArrayOutputStream()
        private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<ByteArray> = result

        override fun onSubscribe(value: Flow.Subscription) {
            subscription = value
            value.request(1)
        }

        override fun onNext(items: List<ByteBuffer>) {
            for (item in items) {
                if (output.size() + item.remaining() > limit) {
                    subscription?.cancel()
                    result.completeExceptionally(IOException("Registry response exceeded the size limit"))
                    return
                }
                val bytes = ByteArray(item.remaining())
                item.get(bytes)
                output.write(bytes)
            }
            subscription?.request(1)
        }

        override fun onError(throwable: Throwable) {
            result.completeExceptionally(throwable)
        }

        override fun onComplete() {
            result.complete(output.toByteArray())
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private val ENDPOINT = URI.create("https://suggestions.dadata.ru/suggestions/api/4_1/rs/findById/party")
    }
}

@Component
class DaDataCompanyRegistryProvider(
    @Value("\${COMPANY_VERIFICATION_PROVIDER:manual}") private val mode: String,
    @Value("\${DADATA_TOKEN:}") private val token: String,
    private val transport: RegistryHttpTransport,
    private val mapper: ObjectMapper,
    private val metrics: OperationalMetrics,
) : CompanyRegistryProvider {
    init {
        require(mode == "manual" || mode == "dadata") { "COMPANY_VERIFICATION_PROVIDER must be manual or dadata" }
    }

    override fun lookup(inn: String): RegistryLookup {
        if (mode != "dadata" || token.isBlank()) return RegistryLookup(available = false, errorCode = "provider_unavailable")
        return try {
            val root = mapper.readTree(transport.lookup(token, inn))
            val data = root.path("suggestions").path(0).path("data")
            if (data.isMissingNode || data.isNull) {
                metrics.recordUpstream("dadata", "success")
                return RegistryLookup(available = true, provider = "dadata", errorCode = "not_found")
            }
            val resultInn = data.path("inn").asText().takeIf(String::isNotBlank)
            val legalName = data.path("name").path("full_with_opf").asText().takeIf(String::isNotBlank)
                ?: data.path("name").path("short_with_opf").asText().takeIf(String::isNotBlank)
            val rawStatus = data.path("state").path("status").asText("UNKNOWN").uppercase()
            val status = when (rawStatus) {
                "ACTIVE" -> "ACTIVE"
                "LIQUIDATING", "REORGANIZING" -> "LIQUIDATING"
                "LIQUIDATED", "BANKRUPT" -> "TERMINATED"
                else -> "UNKNOWN"
            }
            metrics.recordUpstream("dadata", "success")
            RegistryLookup(
                available = true,
                provider = "dadata",
                inn = resultInn,
                legalName = legalName,
                status = status,
                updatedAt = data.path("state").path("actuality_date").asText().takeIf(String::isNotBlank),
            )
        } catch (failure: Exception) {
            metrics.recordUpstream("dadata", if (failure is HttpTimeoutException) "timeout" else "error")
            RegistryLookup(available = false, provider = "dadata", errorCode = "provider_unavailable")
        }
    }
}
