package ru.fsp.talent.operations

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.LongAdder

private val HTTP_BUCKETS = doubleArrayOf(.005, .01, .025, .05, .1, .25, .5, 1.0, 2.5, 5.0, 10.0)
private val HTTP_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
private val UPSTREAM_PROVIDERS = setOf("dadata", "smtp")
private val UPSTREAM_OUTCOMES = setOf("success", "timeout", "error")
private val DELIVERY_OUTCOMES = setOf("delivered", "retry", "failed", "discarded")

private data class HttpKey(val method: String, val route: String)
private data class HttpRequestKey(val key: HttpKey, val status: String)

private class HttpDuration {
    val count = AtomicLong()
    val sumNanos = AtomicLong()
    val buckets = AtomicLongArray(HTTP_BUCKETS.size)

    fun observe(durationNanos: Long) {
        val safeNanos = durationNanos.coerceAtLeast(0)
        val seconds = safeNanos / 1_000_000_000.0
        count.incrementAndGet()
        sumNanos.addAndGet(safeNanos)
        HTTP_BUCKETS.forEachIndexed { index, bound -> if (seconds <= bound) buckets.incrementAndGet(index) }
    }
}

data class HttpMetricsSummary(
    val requestsTotal: Long,
    val serverErrorsTotal: Long,
    val averageDurationSeconds: Double,
)

/** Bounded, process-local telemetry. Labels are limited to route templates and fixed enums. */
@Component
class OperationalMetrics {
    private val requestCounts = ConcurrentHashMap<HttpRequestKey, LongAdder>()
    private val serverErrors = ConcurrentHashMap<HttpKey, LongAdder>()
    private val durations = ConcurrentHashMap<HttpKey, HttpDuration>()
    private val upstreamCounts = ConcurrentHashMap<Pair<String, String>, LongAdder>()
    private val deliveryCounts = ConcurrentHashMap<String, LongAdder>()

    fun recordHttp(method: String, routeTemplate: String?, status: Int, durationNanos: Long) {
        val key = HttpKey(
            method = method.uppercase().takeIf { it in HTTP_METHODS } ?: "OTHER",
            route = routeTemplate?.takeIf { it.startsWith("/api/v1/") && it.length <= 180 } ?: "unmatched",
        )
        val statusLabel = status.coerceIn(100, 599).toString()
        requestCounts.computeIfAbsent(HttpRequestKey(key, statusLabel)) { LongAdder() }.increment()
        if (status >= 500) serverErrors.computeIfAbsent(key) { LongAdder() }.increment()
        durations.computeIfAbsent(key) { HttpDuration() }.observe(durationNanos)
    }

    fun recordUpstream(provider: String, outcome: String) {
        if (provider !in UPSTREAM_PROVIDERS || outcome !in UPSTREAM_OUTCOMES) return
        upstreamCounts.computeIfAbsent(provider to outcome) { LongAdder() }.increment()
    }

    fun recordDeliveryOutcome(outcome: String) {
        if (outcome in DELIVERY_OUTCOMES) deliveryCounts.computeIfAbsent(outcome) { LongAdder() }.increment()
    }

    fun httpSummary(): HttpMetricsSummary {
        val requests = requestCounts.values.sumOf { it.sum() }
        val errors = serverErrors.values.sumOf { it.sum() }
        val durationNanos = durations.values.sumOf { it.sumNanos.get() }
        return HttpMetricsSummary(requests, errors, if (requests == 0L) 0.0 else durationNanos / 1_000_000_000.0 / requests)
    }

    fun upstreamSummary(outboxWorkerEnabled: Boolean): Map<String, Map<String, Long>?> = UPSTREAM_PROVIDERS.associateWith { provider ->
        if (provider == "smtp" && !outboxWorkerEnabled) null
        else UPSTREAM_OUTCOMES.associateWith { outcome -> upstreamCounts[provider to outcome]?.sum() ?: 0L }
    }

    fun renderPrometheus(snapshot: Map<String, Any?>): String = buildString {
        gauge("fsp_process_uptime_seconds", "Process uptime in seconds.", ManagementFactory.getRuntimeMXBean().uptime / 1000.0)
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        gauge("fsp_jvm_heap_used_bytes", "Current JVM heap use in bytes.", heap.used.toDouble())
        gauge("fsp_jvm_heap_max_bytes", "Maximum JVM heap in bytes.", if (heap.max >= 0) heap.max.toDouble() else Double.NaN)

        val databaseAvailable = snapshot.path("database")?.get("available") as? Boolean
        gauge("fsp_database_available", "Whether the database snapshot was read successfully.", databaseAvailable?.let { if (it) 1.0 else 0.0 } ?: Double.NaN)
        val outbox = snapshot.path("outbox")
        val counts = outbox?.get("counts") as? Map<*, *>
        val states = listOf("pending", "delivered", "failed", "discarded")
        appendLine("# HELP fsp_outbox_events Outbox event rows by state.")
        appendLine("# TYPE fsp_outbox_events gauge")
        states.forEach { state ->
            val value = counts?.get(state) as? Number
            sample("fsp_outbox_events", value?.toDouble() ?: Double.NaN, "state" to state)
        }
        val age = outbox?.get("oldest_due_age_seconds") as? Number
        gauge("fsp_outbox_oldest_due_age_seconds", "Age of the oldest due pending outbox event.", age?.toDouble() ?: Double.NaN)
        val workerEnabled = outbox?.get("worker_enabled") as? Boolean
        gauge("fsp_outbox_worker_enabled", "Whether this process runs the outbox worker.", workerEnabled?.let { if (it) 1.0 else 0.0 } ?: 0.0)
        val tick = outbox?.get("worker_last_tick_at")?.toString()?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() / 1000.0 }.getOrNull() }
        gauge("fsp_outbox_worker_last_tick_timestamp_seconds", "Unix timestamp of the last shared outbox worker tick.", tick ?: Double.NaN)

        appendLine("# HELP fsp_http_requests_total Completed HTTP requests by bounded route, method and status.")
        appendLine("# TYPE fsp_http_requests_total counter")
        requestCounts.entries.sortedWith(compareBy({ it.key.key.route }, { it.key.key.method }, { it.key.status })).forEach { (key, value) ->
            appendLine("fsp_http_requests_total${labels("method" to key.key.method, "route" to key.key.route, "status" to key.status)} ${value.sum()}")
        }
        appendLine("# HELP fsp_http_server_errors_total Completed HTTP 5xx responses.")
        appendLine("# TYPE fsp_http_server_errors_total counter")
        serverErrors.entries.sortedWith(compareBy({ it.key.route }, { it.key.method })).forEach { (key, value) ->
            appendLine("fsp_http_server_errors_total${labels("method" to key.method, "route" to key.route)} ${value.sum()}")
        }
        appendLine("# HELP fsp_http_request_duration_seconds HTTP request duration in seconds.")
        appendLine("# TYPE fsp_http_request_duration_seconds histogram")
        durations.entries.sortedWith(compareBy({ it.key.route }, { it.key.method })).forEach { (key, sample) ->
            HTTP_BUCKETS.forEachIndexed { index, bound ->
                appendLine("fsp_http_request_duration_seconds_bucket${labels("method" to key.method, "route" to key.route, "le" to bound.toString())} ${sample.buckets.get(index)}")
            }
            appendLine("fsp_http_request_duration_seconds_bucket${labels("method" to key.method, "route" to key.route, "le" to "+Inf")} ${sample.count.get()}")
            appendLine("fsp_http_request_duration_seconds_sum${labels("method" to key.method, "route" to key.route)} ${sample.sumNanos.get() / 1_000_000_000.0}")
            appendLine("fsp_http_request_duration_seconds_count${labels("method" to key.method, "route" to key.route)} ${sample.count.get()}")
        }
        appendLine("# HELP fsp_upstream_calls_total Calls to configured upstream providers by outcome.")
        appendLine("# TYPE fsp_upstream_calls_total counter")
        UPSTREAM_PROVIDERS.sorted().forEach { provider -> UPSTREAM_OUTCOMES.forEach { outcome ->
            val count: Double = if (provider == "smtp" && workerEnabled != true) Double.NaN
                else (upstreamCounts[provider to outcome]?.sum() ?: 0L).toDouble()
            appendLine("fsp_upstream_calls_total${labels("provider" to provider, "outcome" to outcome)} $count")
        } }
        appendLine("# HELP fsp_outbox_delivery_attempts_total Outbox processing outcomes; delivered means SMTP accepted the send call.")
        appendLine("# TYPE fsp_outbox_delivery_attempts_total counter")
        DELIVERY_OUTCOMES.forEach { outcome ->
            val count: Double = if (workerEnabled == true) (deliveryCounts[outcome]?.sum() ?: 0L).toDouble() else Double.NaN
            appendLine("fsp_outbox_delivery_attempts_total${labels("outcome" to outcome)} $count")
        }
    }

    private fun StringBuilder.gauge(name: String, help: String, value: Number, vararg label: Pair<String, String>) {
        appendLine("# HELP $name $help")
        appendLine("# TYPE $name gauge")
        sample(name, value, *label)
    }

    private fun StringBuilder.sample(name: String, value: Number, vararg label: Pair<String, String>) {
        appendLine("$name${labels(*label)} ${value.toString()}")
    }

    private fun labels(vararg values: Pair<String, String>): String = values.takeIf { it.isNotEmpty() }?.joinToString(
        prefix = "{", postfix = "}", separator = ",",
    ) { (name, value) -> "$name=\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\"" }.orEmpty()

    private fun Map<String, Any?>.path(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class OperationalMetricsFilter(private val metrics: OperationalMetrics) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/api/v1/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val started = System.nanoTime()
        try {
            chain.doFilter(request, response)
        } finally {
            val route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()
            metrics.recordHttp(request.method, route, response.status, System.nanoTime() - started)
        }
    }
}
