package ru.fsp.talent.identity

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.CsrfOriginFilter
import ru.fsp.talent.core.MutationService
import ru.fsp.talent.core.RateLimits
import ru.fsp.talent.core.SessionSupport
import ru.fsp.talent.core.Store
import ru.fsp.talent.operations.OperationsAccess
import ru.fsp.talent.companyverification.CompanyVerificationService
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class IdentityService(
    private val store: Store,
    private val sessions: SessionSupport,
    private val auth: AuthAccess,
    private val profiles: ProfileReader,
    private val contracts: Contracts,
    private val passwords: PasswordEncoder,
    private val rateLimits: RateLimits,
    private val replay: AuthReplay,
    private val mutations: MutationService,
    private val operations: OperationsAccess,
    private val companyVerification: CompanyVerificationService,
    @Value("\${fsp.session-secret}") private val sessionSecret: String,
    @Value("\${fsp.email-verification-required:false}") private val emailVerificationRequired: Boolean,
) {
    private val dummyPasswordHash = passwords.encode("unusable-login-dummy-${UUID.randomUUID()}")

    fun register(
        request: HttpServletRequest,
        response: HttpServletResponse,
        body: Map<String, Any?>,
    ): AuthReplay.Outcome {
        contracts.validate("RegisterRequest", body)
        val email = (body["email"] as String).trim().lowercase(Locale.ROOT)
        val password = body["password"] as String
        if (!EMAIL.matches(email)) throw ApiException(400, "VALIDATION_ERROR", "Enter a valid email address", "email")
        val accountType = body["account_type"] as String
        val displayName = (body["display_name"] as String).trim()
        if (displayName.isEmpty()) throw ApiException(400, "VALIDATION_ERROR", "Display name is required", "display_name")
        val companyName = (body["company_name"] as? String)?.trim()
        if (accountType == "employer" && companyName.isNullOrEmpty()) {
            throw ApiException(400, "VALIDATION_ERROR", "Company name is required for employer accounts", "company_name")
        }

        val scope = replay.scope("register", email)
        replay.find(request, scope, body, response)?.let { return it }
        rateLimits.consume(RateLimits.Rule("registration-ip", rateLimits.clientIp(request), 5, 3600))
        return replay.execute(request, response, scope, body, status = 201) {
            if (store.one("SELECT id FROM users WHERE email = :email", mapOf("email" to email)) != null) {
                throw ApiException(409, "EMAIL_ALREADY_EXISTS", "An account with this email already exists", "email")
            }
            val userId = store.id()
            store.exec(
                "INSERT INTO users(id, email, password_hash, role, email_verified_at) " +
                    "VALUES (:id, :email, :password, :role, CASE WHEN :required THEN NULL ELSE now() END)",
                mapOf("id" to userId, "email" to email, "password" to passwords.encode(password), "role" to accountType,
                    "required" to emailVerificationRequired),
            )
            if (accountType == "candidate") {
                store.exec(
                    "INSERT INTO candidate_profiles(user_id, data, contacts) VALUES (:userId, :data, '{}'::jsonb)",
                    mapOf("userId" to userId, "data" to store.json(mapOf("display_name" to displayName))),
                )
            } else {
                val companyId = store.id()
                store.exec(
                    "INSERT INTO companies(id, owner_id, data) VALUES (:id, :owner, :data)",
                    mapOf("id" to companyId, "owner" to userId, "data" to store.json(mapOf("name" to companyName, "description" to null, "website" to null))),
                )
                store.exec(
                    "INSERT INTO memberships(id, user_id, company_id, role) VALUES (:id, :userId, :companyId, 'owner')",
                    mapOf("id" to store.id(), "userId" to userId, "companyId" to companyId),
                )
            }
            if (emailVerificationRequired) {
                val verificationId = store.id()
                val token = verificationToken(verificationId)
                store.exec(
                    "INSERT INTO email_verification(id, user_id, email, token_hash, expires_at) VALUES (:id, :user, :email, :hash, :expires)",
                    mapOf("id" to verificationId, "user" to userId, "email" to email,
                        "hash" to SessionSupport.digest(token), "expires" to store.now().plus(VERIFICATION_TTL)),
                )
                store.exec(
                    "INSERT INTO outbox_events(id, type, payload) VALUES (:id, 'email_verification', :payload)",
                    mapOf("id" to store.id(), "payload" to store.json(mapOf("verification_id" to verificationId))),
                )
            }
            val issued = sessions.rotate(request, userId, response)
            AuthReplay.Created(mapOf("user_id" to userId, "account_type" to accountType,
                "email_verification_required" to emailVerificationRequired), issued.id)
        }
    }

    fun verificationToken(id: String): String = "$id.${hmac(id)}"

    fun confirmEmail(request: HttpServletRequest, body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        contracts.validate("ConfirmEmailRequest", body)
        val token = body.getValue("token").toString()
        val parts = token.split('.', limit = 2)
        if (parts.size != 2 || !constantTime(parts[1], hmac(parts[0]))) {
            throw ApiException(400, "VALIDATION_ERROR", "Email verification token is invalid", "token")
        }
        val scope = replay.scope("confirm-email", token)
        return mutations.run(request, scope, body) {
            val verification = store.one(
                "SELECT id, user_id, token_hash, expires_at, used_at FROM email_verification WHERE id = :id FOR UPDATE",
                mapOf("id" to parts[0]),
            ) ?: throw ApiException(400, "VALIDATION_ERROR", "Email verification token is invalid", "token")
            if (verification["used_at"] != null || asInstant(verification["expires_at"]).let { !it.isAfter(store.now()) } ||
                !constantTime(verification["token_hash"].toString().trim(), SessionSupport.digest(token))) {
                throw ApiException(400, "VALIDATION_ERROR", "Email verification token is invalid or expired", "token")
            }
            store.exec("UPDATE email_verification SET used_at = now() WHERE id = :id", mapOf("id" to parts[0]))
            store.exec("UPDATE users SET email_verified_at = now() WHERE id = :id", mapOf("id" to verification["user_id"]))
            mapOf("email_verified" to true)
        }
    }

    fun resendConfirmation(request: HttpServletRequest, body: Map<String, Any?>): ResponseEntity<Map<String, Any?>> {
        contracts.validate("ResendConfirmationRequest", body)
        val email = body.getValue("email").toString().trim().lowercase(Locale.ROOT)
        if (!EMAIL.matches(email)) throw ApiException(400, "VALIDATION_ERROR", "Enter a valid email address", "email")
        val scope = replay.scope("resend-confirmation", email)
        replay.find(request, scope, body, protectBodyHash = false)?.let {
            return ResponseEntity.status(it.status).body(it.body)
        }
        rateLimits.consume(
            RateLimits.Rule("confirmation-resend-account", email, 5, 86_400, 60),
            RateLimits.Rule("confirmation-resend-ip", rateLimits.clientIp(request), 20, 86_400),
        )
        return mutations.run(request, scope, body, status = 202) {
            val user = store.one(
                "SELECT id, email_verified_at FROM users WHERE email = :email AND deleted_at IS NULL FOR UPDATE",
                mapOf("email" to email),
            )
            if (user != null && user["email_verified_at"] == null) {
                store.exec("UPDATE email_verification SET used_at = now() WHERE user_id = :id AND used_at IS NULL", mapOf("id" to user["id"]))
                val verificationId = store.id()
                store.exec(
                    "INSERT INTO email_verification(id, user_id, email, token_hash, expires_at) VALUES (:id, :user, :email, :hash, :expires)",
                    mapOf("id" to verificationId, "user" to user["id"], "email" to email,
                        "hash" to SessionSupport.digest(verificationToken(verificationId)), "expires" to store.now().plus(VERIFICATION_TTL)),
                )
                store.exec(
                    "INSERT INTO outbox_events(id, type, payload) VALUES (:id, 'email_verification', :payload)",
                    mapOf("id" to store.id(), "payload" to store.json(mapOf("verification_id" to verificationId))),
                )
            }
            mapOf("accepted" to true)
        }
    }

    fun login(body: Map<String, Any?>, request: HttpServletRequest, response: HttpServletResponse): AuthReplay.Outcome {
        contracts.validate("LoginRequest", body)
        val email = body.getValue("email").toString().trim().lowercase(Locale.ROOT)
        val password = body.getValue("password").toString()
        val scope = replay.scope("login", email)
        replay.find(request, scope, body, response)?.let { return it }
        val ip = rateLimits.clientIp(request)
        val user = rateLimits.failedLogin("$email\u0000$ip") {
            val candidate = store.one(
                "SELECT id, password_hash FROM users WHERE email = :email AND deleted_at IS NULL",
                mapOf("email" to email),
            )
            val matches = passwords.matches(password, candidate?.get("password_hash")?.toString() ?: dummyPasswordHash)
            candidate?.takeIf { matches }
        }
        val userId = user.getValue("id").toString()
        return replay.execute(request, response, scope, body, status = 200) {
            if (!emailVerificationRequired) {
                // Accounts created while verification was required would otherwise stay stuck on the verify page.
                store.exec("UPDATE users SET email_verified_at = now() WHERE id = :id AND email_verified_at IS NULL", mapOf("id" to userId))
            }
            val issued = sessions.rotate(request, userId, response)
            AuthReplay.Created(currentUser(userId), issued.id)
        }
    }

    fun logout(request: HttpServletRequest): Map<String, Any?> {
        val sessionId = request.getAttribute(CsrfOriginFilter.SESSION_ID_ATTRIBUTE)?.toString()
            ?: throw ApiException(401, "UNAUTHENTICATED", "Sign in to continue")
        val scope = "identity-logout:$sessionId"
        val body = emptyMap<String, Any?>()
        return mutations.run(request, scope, body) {
            auth.current(request)
            sessions.revoke(request)
            mapOf("logged_out" to true)
        }.body?.get("data") as? Map<String, Any?> ?: mapOf("logged_out" to true)
    }

    fun currentUser(request: HttpServletRequest): Map<String, Any?> = currentUser(auth.current(request).id)

    private fun currentUser(userId: String): Map<String, Any?> {
        val user = store.one("SELECT id, email, role, email_verified_at FROM users WHERE id = :id AND deleted_at IS NULL", mapOf("id" to userId))
            ?: throw ApiException(401, "UNAUTHENTICATED", "Sign in to continue")
        val candidate = user["role"] == "candidate"
        val company = if (candidate) null else store.one(
            "SELECT id, version, data, updated_at FROM companies WHERE owner_id = :id", mapOf("id" to userId),
        )?.let(::companyDto)
        val candidateProfile = if (candidate) profiles.privateProfile(userId) else null
        return mapOf(
            "user_id" to user.getValue("id").toString(),
            "email" to user.getValue("email").toString(),
            "email_verified" to (user["email_verified_at"] != null),
            "account_type" to user.getValue("role").toString(),
            "candidate_profile" to candidateProfile,
            "company" to company,
            "membership_role" to if (candidate) null else "owner",
            "operations_permissions" to if (user["email_verified_at"] != null) operations.getPermissions(userId) else emptyList<String>(),
            "methodist_access" to ((user["email_verified_at"] != null) &&
                (store.one("SELECT active FROM assessment_methodists WHERE user_id = :id", mapOf("id" to userId))?.get("active") == true)),
        )
    }

    private fun companyDto(row: Map<String, Any?>): Map<String, Any?> {
        val data = jsonMap(row["data"])
        return mapOf(
            "id" to row.getValue("id").toString(),
            "version" to (row["version"] as Number).toInt(),
            "name" to data["name"],
            "description" to data["description"],
            "website" to data["website"],
            "industry" to data["industry"],
            "contact_email" to data["contact_email"],
            "contact_phone" to data["contact_phone"],
            "inn" to data["inn"],
            "legal_name" to data["legal_name"],
            "verification" to companyVerification.getPublicSummary(row.getValue("id").toString()),
            "updated_at" to asInstant(row["updated_at"]).toString(),
        )
    }

    private fun jsonMap(value: Any?): Map<String, Any?> = when (value) {
        is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
        else -> emptyMap()
    }

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    private fun constantTime(left: String, right: String) = MessageDigest.isEqual(left.toByteArray(), right.toByteArray())

    private fun asInstant(value: Any?): java.time.Instant = when (value) {
        is java.time.Instant -> value
        is java.sql.Timestamp -> value.toInstant()
        else -> java.time.Instant.parse(value.toString())
    }

    companion object {
        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        private val VERIFICATION_TTL = Duration.ofMinutes(30)
    }
}

interface ProfileReader {
    fun privateProfile(userId: String): Map<String, Any?>
}
