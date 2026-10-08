package ru.fsp.talent.core

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import java.net.URI
import java.util.Locale

/** Rejects configuration that would expose the synthetic/demo setup as a production service. */
@Configuration
@Profile("production")
class ProductionGuardConfiguration {
    @Bean
    fun productionConfigurationGuard(environment: Environment): ProductionConfigurationGuard {
        val guard = ProductionConfigurationGuard(environment)
        guard.validate()
        return guard
    }
}

class ProductionConfigurationGuard(private val environment: Environment) {
    fun validate() {
        rejectIf(environment.getProperty("fsp.demo-mode", Boolean::class.java, false), "fsp.demo-mode")
        rejectIf(environment.getProperty("fsp.seed-demo", Boolean::class.java, false), "fsp.seed-demo")
        rejectIf(environment.getProperty("spring.flyway.enabled", Boolean::class.java, false), "spring.flyway.enabled")

        val secret = environment.getProperty("fsp.session-secret")
            ?: invalid("fsp.session-secret")
        val normalizedSecret = secret.lowercase(Locale.ROOT)
        rejectIf(
            secret.length < MIN_SECRET_LENGTH ||
                secret.toSet().size < MIN_SECRET_UNIQUE_CHARACTERS ||
                isRepeatedPattern(secret) ||
                normalizedSecret in KNOWN_WEAK_SECRETS ||
                listOf("demo", "change-me", "changeme", "replace", "secret", "password").any(normalizedSecret::contains),
            "fsp.session-secret",
        )

        rejectIf(!environment.getProperty("fsp.session-cookie-secure", Boolean::class.java, false), "fsp.session-cookie-secure")
        val origin = environment.getProperty("fsp.public-origin") ?: invalid("fsp.public-origin")
        rejectIf(!isValidPublicOrigin(origin), "fsp.public-origin")

        val datasourcePassword = environment.getProperty("spring.datasource.password") ?: invalid("spring.datasource.password")
        val normalizedPassword = datasourcePassword.lowercase(Locale.ROOT)
        rejectIf(
            datasourcePassword.isBlank() || normalizedPassword in KNOWN_DEMO_PASSWORDS,
            "spring.datasource.password",
        )
    }

    private fun isValidPublicOrigin(value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase(Locale.ROOT)
        uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true) &&
            !host.isNullOrBlank() && uri.rawUserInfo == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
            uri.rawQuery == null && uri.rawFragment == null &&
            host != "localhost" && !host.endsWith(".localhost") &&
            host != "::1" && host != "[::1]" && host != "0.0.0.0" &&
            !host.startsWith("127.")
    }.getOrDefault(false)

    private fun isRepeatedPattern(value: String): Boolean {
        for (patternLength in 1..value.length / 2) {
            if (value.length % patternLength == 0 &&
                value == value.take(patternLength).repeat(value.length / patternLength)
            ) return true
        }
        return false
    }

    private fun rejectIf(condition: Boolean, field: String) {
        if (condition) invalid(field)
    }

    private fun invalid(field: String): Nothing =
        throw IllegalStateException("Invalid production configuration: $field")

    private companion object {
        const val MIN_SECRET_LENGTH = 32
        const val MIN_SECRET_UNIQUE_CHARACTERS = 10
        val KNOWN_WEAK_SECRETS = setOf(
            "local-demo-session-secret-change-before-deploy-2026",
            "changeme",
            "change-me",
            "secret",
            "password",
        )
        val KNOWN_DEMO_PASSWORDS = setOf("fsp_demo", "demopassword123!")
    }
}
