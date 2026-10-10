package ru.fsp.talent.companyverification

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.fsp.talent.consents.ConsentService
import ru.fsp.talent.core.Actor
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Store
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

@Service
class CompanyVerificationService(
    private val store: Store,
    private val auth: AuthAccess,
    private val consents: ConsentService,
    private val registryProvider: CompanyRegistryProvider,
    @Value("\${fsp.demo-mode:true}") private val demoMode: Boolean,
    @Value("\${fsp.company-verification.review-expiry-days:30}") private val reviewExpiryDays: Long,
) {
    fun getOwn(actor: Actor): Map<String, Any?> {
        val company = ownerCompany(actor)
        return mapOf(
            "public" to publicSummary(company),
            "owner" to ownerWrapper(company),
        )
    }

    /** Checks the owner boundary before mutation replay can return a stored private response. */
    fun requireOwner(actor: Actor) {
        ownerCompany(actor)
    }

    fun requestVerification(actor: Actor, expectedCompanyVersion: Int): Map<String, Any?> {
        auth.verified(actor)
        ownerCompany(actor)
        consents.requireProcessing(actor.id)
        val company = ownerCompany(actor, lock = true)
        val currentVersion = (company.getValue("version") as Number).toInt()
        if (currentVersion != expectedCompanyVersion) {
            throw ApiException(409, "VERSION_CONFLICT", "Company changed; reload before requesting verification", currentVersion = currentVersion)
        }
        val snapshot = criteriaSnapshot(company)
        val inn = snapshot.getValue("inn").toString()
        val legalName = snapshot.getValue("legal_name").toString()
        if (inn.isBlank()) throw ApiException(422, "COMPANY_INN_REQUIRED", "Add the company's INN before requesting verification", "inn")
        if (legalName.isBlank()) throw ApiException(422, "COMPANY_LEGAL_NAME_REQUIRED", "Add the company's legal name before requesting verification", "legal_name")
        if (!CompanyVerificationRules.isValidInn(inn)) throw ApiException(422, "COMPANY_INN_INVALID", "The company's INN is invalid", "inn")

        val hash = criteriaHash(snapshot)
        invalidateChangedRequests(company.getValue("id").toString(), hash)
        val id = store.id()
        val row = store.one(
            """INSERT INTO company_verification_requests(
                   id, company_id, company_version, criteria_hash, inn, legal_name, snapshot, status, demo, created_by
               ) VALUES (
                   :id, :company, :companyVersion, :hash, :inn, :legalName, :snapshot, 'pending', :demo, :actor
               ) ON CONFLICT (company_id, criteria_hash) WHERE status = 'pending'
                 DO UPDATE SET
                   company_version = EXCLUDED.company_version,
                   version = company_verification_requests.version + CASE
                       WHEN company_verification_requests.company_version <> EXCLUDED.company_version THEN 1 ELSE 0 END,
                   updated_at = now()
               RETURNING id, version, company_id, company_version, criteria_hash, inn, legal_name, snapshot,
                         status, demo, provider, registry_inn, registry_legal_name, registry_status, registry_updated_at,
                         source_check_status, source_check_error_code, source_type, source_label, source_reference, checked_at, expires_at,
                         review_notes, created_at""".trimIndent(),
            mapOf(
                "id" to id, "company" to company["id"], "companyVersion" to currentVersion, "hash" to hash,
                "inn" to inn, "legalName" to legalName, "snapshot" to store.json(snapshot),
                "demo" to demoMode, "actor" to actor.id,
            ),
        ) ?: throw IllegalStateException("Company verification request was not returned")
        val companyId = company.getValue("id").toString()
        return mapOf(
            "public" to getPublicSummary(companyId),
            "owner" to ownerWrapper(ownerCompany(actor)),
            "current_request" to requestDto(row),
        )
    }

    fun getPublicSummary(companyId: String): Map<String, Any?> {
        val company = companyById(companyId)
        return publicSummary(company)
    }

    /** Performs the optional registry request before the caller opens its mutation transaction. */
    fun lookupRegistryOutsideTransaction(actor: Actor, requestId: String, expectedVersion: Int): RegistryLookup {
        auth.verified(actor)
        ownerCompany(actor)
        consents.requireProcessing(actor.id)
        val request = store.one(
            """SELECT r.version, r.inn, r.status, r.criteria_hash, r.company_id, c.owner_id,
                      c.version AS current_company_version, c.data, u.email AS owner_email,
                      u.email_verified_at AS owner_email_verified_at,
                      (SELECT ce.action = 'accept' FROM consent_events ce WHERE ce.actor_user_id = u.id
                       AND ce.scope = 'processing' ORDER BY ce.sequence DESC LIMIT 1) AS owner_processing_consent
               FROM company_verification_requests r JOIN companies c ON c.id = r.company_id
               JOIN users u ON u.id = c.owner_id WHERE r.id = :requestId""".trimIndent(),
            mapOf("requestId" to requestId),
        ) ?: throw ApiException(404, "NOT_FOUND", "Company verification request not found")
        if (request.getValue("owner_id").toString() != actor.id || request.getValue("company_id").toString() != actor.companyId) {
            throw ApiException(404, "NOT_FOUND", "Company verification request not found")
        }
        val currentVersion = (request.getValue("version") as Number).toInt()
        if (currentVersion != expectedVersion) {
            throw ApiException(409, "VERSION_CONFLICT", "Company verification request changed; reload before checking", currentVersion = currentVersion)
        }
        if (request["status"]?.toString() !in setOf("pending", "registry_confirmed", "unavailable")) {
            throw ApiException(409, "VERIFICATION_NOT_PENDING", "This company verification request can no longer be checked")
        }
        if (criteriaHash(criteriaSnapshot(request)) != request.getValue("criteria_hash").toString().trim()) {
            throw ApiException(409, "COMPANY_CRITERIA_CHANGED", "Company details changed after this request was submitted", currentVersion = request["current_company_version"] as? Int)
        }
        return registryProvider.lookup(request.getValue("inn").toString())
    }

    /** Persists a bounded provider result after rechecking request version and company criteria under lock. */
    fun recordRegistryLookup(
        actor: Actor,
        requestId: String,
        expectedVersion: Int,
        lookup: RegistryLookup,
    ): Map<String, Any?> {
        ownerCompany(actor)
        consents.requireProcessing(actor.id)
        val request = store.one(
            """SELECT r.*, c.owner_id, c.version AS current_company_version, c.data,
                      u.email AS owner_email, u.email_verified_at AS owner_email_verified_at,
                      (SELECT ce.action = 'accept' FROM consent_events ce WHERE ce.actor_user_id = u.id
                       AND ce.scope = 'processing' ORDER BY ce.sequence DESC LIMIT 1) AS owner_processing_consent
               FROM company_verification_requests r JOIN companies c ON c.id = r.company_id
               JOIN users u ON u.id = c.owner_id WHERE r.id = :requestId FOR UPDATE OF r, c""".trimIndent(),
            mapOf("requestId" to requestId),
        ) ?: throw ApiException(404, "NOT_FOUND", "Company verification request not found")
        if (request.getValue("owner_id").toString() != actor.id || request.getValue("company_id").toString() != actor.companyId) {
            throw ApiException(404, "NOT_FOUND", "Company verification request not found")
        }
        val currentVersion = (request.getValue("version") as Number).toInt()
        if (currentVersion != expectedVersion) {
            throw ApiException(409, "VERSION_CONFLICT", "Company verification request changed; reload before checking", currentVersion = currentVersion)
        }
        if (request["status"]?.toString() !in setOf("pending", "registry_confirmed", "unavailable")) {
            throw ApiException(409, "VERIFICATION_NOT_PENDING", "This company verification request can no longer be checked")
        }
        if (criteriaHash(criteriaSnapshot(request)) != request.getValue("criteria_hash").toString().trim()) {
            throw ApiException(409, "COMPANY_CRITERIA_CHANGED", "Company details changed after this request was submitted", currentVersion = request["current_company_version"] as? Int)
        }
        val decision = CompanyVerificationRules.registryDecision(request["inn"].toString(), request["legal_name"].toString(), lookup)
        val outcome = decision.sourceCheckStatus
        val nextStatus = decision.requestStatus
        val version = currentVersion + 1
        store.exec(
            """INSERT INTO company_verification_source_checks(id, request_id, request_version, provider, outcome, error_code)
               VALUES (:id, :requestId, :version, :provider, :outcome, :errorCode)""".trimIndent(),
            mapOf(
                "id" to store.id(), "requestId" to requestId, "version" to currentVersion,
                "provider" to lookup.provider, "outcome" to outcome, "errorCode" to lookup.errorCode,
            ),
        )
        store.exec(
            """UPDATE company_verification_requests SET
                   version = :version, status = :status, provider = :provider,
                   registry_inn = :registryInn, registry_legal_name = :registryLegalName,
                   registry_status = :registryStatus, registry_updated_at = :registryUpdatedAt,
                   source_check_status = :sourceCheckStatus, source_check_error_code = :sourceCheckErrorCode,
                   source_type = 'registry_api', source_label = :sourceLabel, checked_at = :checkedAt,
                   updated_at = now()
               WHERE id = :requestId""".trimIndent(),
            mapOf(
                "requestId" to requestId, "version" to version, "status" to nextStatus,
                "provider" to lookup.provider, "registryInn" to lookup.inn, "registryLegalName" to lookup.legalName,
                "registryStatus" to lookup.status, "registryUpdatedAt" to lookup.updatedAt,
                "sourceCheckStatus" to outcome, "sourceCheckErrorCode" to lookup.errorCode,
                "sourceLabel" to if (lookup.provider == "dadata") "DaData registry lookup" else "Registry check unavailable",
                "checkedAt" to store.now(),
            ),
        )
        val updated = store.one(
            """SELECT id, version, company_id, company_version, criteria_hash, inn, legal_name, snapshot,
                      status, demo, provider, registry_inn, registry_legal_name, registry_status, registry_updated_at,
                      source_check_status, source_check_error_code, source_type, source_label, source_reference,
                      checked_at, expires_at, review_notes, created_at
               FROM company_verification_requests WHERE id = :requestId""".trimIndent(),
            mapOf("requestId" to requestId),
        ) ?: throw IllegalStateException("Updated registry check disappeared")
        return mapOf(
            "public" to getPublicSummary(updated.getValue("company_id").toString()),
            "owner" to ownerWrapper(ownerCompany(actor)),
            "current_request" to requestDto(updated),
        )
    }

    fun listForReview(limit: Int = 100): Map<String, Any?> = mapOf(
        "items" to store.rows(
            """SELECT r.id, r.version, r.company_id, r.company_version, r.criteria_hash, r.inn, r.legal_name,
                      r.snapshot, r.status, r.demo, r.provider, r.registry_inn, r.registry_legal_name, r.registry_status,
                      r.registry_updated_at, r.source_check_status, r.source_check_error_code, r.source_type, r.source_label, r.source_reference,
                      r.checked_at, r.expires_at, r.review_notes, r.created_at
               FROM company_verification_requests r
               ORDER BY CASE WHEN r.status = 'pending' THEN 0 ELSE 1 END, r.created_at DESC
               LIMIT :limit""".trimIndent(),
            mapOf("limit" to limit.coerceIn(1, 200)),
        ).map(::requestDto),
    )

    internal fun review(requestId: String, reviewer: Actor, input: ReviewInput): Map<String, Any?> {
        val request = store.one(
            """SELECT r.*, c.owner_id, c.version AS current_company_version, c.data,
                      u.email AS owner_email, u.email_verified_at AS owner_email_verified_at,
                      (SELECT ce.action = 'accept' FROM consent_events ce WHERE ce.actor_user_id = u.id
                       AND ce.scope = 'processing' ORDER BY ce.sequence DESC LIMIT 1) AS owner_processing_consent
               FROM company_verification_requests r
               JOIN companies c ON c.id = r.company_id
               JOIN users u ON u.id = c.owner_id
               WHERE r.id = :id FOR UPDATE OF r, c""".trimIndent(),
            mapOf("id" to requestId),
        ) ?: throw ApiException(404, "NOT_FOUND", "Company verification request not found")
        if (request.getValue("owner_id").toString() == reviewer.id) {
            throw ApiException(403, "OWN_COMPANY_REVIEW_FORBIDDEN", "Operators cannot review their own company")
        }
        val requestVersion = (request.getValue("version") as Number).toInt()
        if (requestVersion != input.expectedVersion) {
            throw ApiException(409, "VERSION_CONFLICT", "Company verification request changed; reload before reviewing", currentVersion = requestVersion)
        }
        if (request["status"]?.toString() !in setOf("pending", "registry_confirmed", "unavailable")) {
            throw ApiException(409, "VERIFICATION_NOT_PENDING", "Only open company verification requests can be reviewed")
        }

        val liveSnapshot = criteriaSnapshot(request)
        val companyVersion = (request.getValue("current_company_version") as Number).toInt()
        if (criteriaHash(liveSnapshot) != request.getValue("criteria_hash").toString().trim()) {
            throw ApiException(409, "COMPANY_CRITERIA_CHANGED", "Company details changed after this request was submitted", currentVersion = companyVersion)
        }

        val innMatches = input.registryInn != null && input.registryInn == request["inn"].toString()
        val legalNameMatches = input.registryLegalName?.let {
            CompanyVerificationRules.normalizeLegalName(it) == CompanyVerificationRules.normalizeLegalName(request["legal_name"].toString())
        } == true
        if (input.outcome == "approve" &&
            (input.registryStatus != "ACTIVE" || !innMatches || !legalNameMatches || !input.representativeReviewed)
        ) {
            throw ApiException(422, "VERIFICATION_EVIDENCE_INCOMPLETE", "Approval requires active registry evidence matching the company and a representative review")
        }

        if (input.outcome == "approve" && !demoMode) ensureInnAvailable(input.registryInn!!, requestId)
        val nextStatus = when (input.outcome) {
            "approve" -> "reviewed"
            "request_changes" -> "needs_changes"
            else -> "rejected"
        }
        val now = store.now()
        val expiresAt = if (input.outcome == "approve") now.plus(Duration.ofDays(reviewExpiryDays.coerceIn(1, 365))) else null
        store.exec(
            """INSERT INTO company_verification_reviews(
                   id, request_id, request_version, reviewer_id, outcome, evidence_reference, registry_inn,
                   registry_legal_name, registry_status, representative_reviewed, comment
               ) VALUES (
                   :id, :requestId, :version, :reviewerId, :outcome, :evidence, :inn,
                   :legalName, :registryStatus, :representativeReviewed, :comment
               )""".trimIndent(),
            mapOf(
                "id" to store.id(), "requestId" to requestId, "version" to requestVersion, "reviewerId" to reviewer.id,
                "outcome" to input.outcome, "evidence" to input.evidenceReference, "inn" to input.registryInn,
                "legalName" to input.registryLegalName, "registryStatus" to input.registryStatus,
                "representativeReviewed" to input.representativeReviewed, "comment" to input.comment,
            ),
        )
        store.exec(
            """UPDATE company_verification_requests
               SET status = :status, version = version + 1, provider = COALESCE(provider, 'manual'), source_type = 'manual',
                   source_label = 'Operator supplied registry evidence', source_reference = :evidence,
                   checked_at = :checkedAt, expires_at = :expiresAt, review_notes = :notes, updated_at = now()
               WHERE id = :id""".trimIndent(),
            mapOf(
                "id" to requestId, "status" to nextStatus, "evidence" to input.evidenceReference,
                "checkedAt" to now, "expiresAt" to expiresAt,
                "notes" to input.comment,
            ),
        )
        val reviewed = store.one(
            """SELECT id, version, company_id, company_version, criteria_hash, inn, legal_name, snapshot,
                      status, demo, provider, registry_inn, registry_legal_name, registry_status, registry_updated_at,
                      source_check_status, source_check_error_code, source_type, source_label, source_reference, checked_at, expires_at,
                      review_notes, created_at
               FROM company_verification_requests WHERE id = :id""".trimIndent(),
            mapOf("id" to requestId),
        ) ?: throw IllegalStateException("Reviewed request disappeared")
        return mapOf(
            "request" to requestDto(reviewed),
            "public" to getPublicSummary(reviewed.getValue("company_id").toString()),
        )
    }

    /** Called by the company profile update transaction after a successful relevant update. */
    fun invalidateForCompany(companyId: String) {
        val company = companyById(companyId)
        val currentHash = criteriaHash(criteriaSnapshot(company))
        invalidateChangedRequests(companyId, currentHash)
    }

    private fun ensureInnAvailable(inn: String, requestId: String) {
        store.one("SELECT pg_advisory_xact_lock(hashtext(:inn))", mapOf("inn" to inn))
        val duplicate = store.one(
            """SELECT id FROM company_verification_requests
               WHERE inn = :inn AND id <> :id AND demo = false AND status = 'reviewed'
                 AND expires_at > now() FOR UPDATE""".trimIndent(),
            mapOf("inn" to inn, "id" to requestId),
        )
        if (duplicate != null) throw ApiException(409, "COMPANY_INN_ALREADY_VERIFIED", "A current company already uses this INN")
    }

    private fun invalidateChangedRequests(companyId: String, currentHash: String) {
        store.exec(
            """UPDATE company_verification_requests
               SET status = 'superseded', version = version + 1, updated_at = now()
               WHERE company_id = :company AND criteria_hash <> :hash
                 AND status IN ('pending', 'registry_confirmed', 'reviewed', 'needs_changes', 'unavailable')""".trimIndent(),
            mapOf("company" to companyId, "hash" to currentHash),
        )
    }

    private fun publicSummary(company: Map<String, Any?>): Map<String, Any?> {
        val companyId = company.getValue("id").toString()
        val snapshot = criteriaSnapshot(company)
        val currentHash = criteriaHash(snapshot)
        val latest = store.one(
            """SELECT id, company_version, criteria_hash, inn, legal_name, registry_inn, registry_legal_name,
                      registry_status, source_check_status, status, demo, checked_at, expires_at, snapshot
               FROM company_verification_requests WHERE company_id = :company
               ORDER BY created_at DESC LIMIT 1""".trimIndent(),
            mapOf("company" to companyId),
        )
        val stale = latest != null && latest.getValue("criteria_hash").toString().trim() != currentHash
        val expired = latest?.get("expires_at")?.let { asInstant(it)?.isBefore(store.now()) } == true
        val status = when {
            latest == null -> "unverified"
            stale -> "superseded"
            expired -> "expired"
            else -> latest["status"].toString()
        }
        val checks = latest?.let { publicChecks(company, it, stale || expired) } ?: listOf(
            check("registry", "unknown"), check("legal_name", "unknown"),
            check("corporate_email", corporateEmailStatus(company)), check("representative", "unknown"),
        )
        return mapOf(
            "status" to status,
            "demo" to (latest?.get("demo") == true || demoMode),
            "checked_at" to latest?.get("checked_at"),
            "expires_at" to latest?.get("expires_at"),
            "company_version" to (company["version"] as Number).toInt(),
            "checks" to checks,
            "claims_legal_authority" to false,
        )
    }

    private fun publicChecks(company: Map<String, Any?>, request: Map<String, Any?>, stale: Boolean): List<Map<String, String>> {
        if (stale) return listOf(
            check("registry", "unknown"), check("legal_name", "unknown"),
            check("corporate_email", corporateEmailStatus(company)), check("representative", "unknown"),
        )
        val review = store.one(
            """SELECT registry_inn, registry_legal_name, registry_status, outcome, representative_reviewed
               FROM company_verification_reviews WHERE request_id = :id ORDER BY request_version DESC LIMIT 1""".trimIndent(),
            mapOf("id" to request.getValue("id")),
        )
        val registryInn = review?.get("registry_inn") ?: request["registry_inn"]
        val registryLegalName = review?.get("registry_legal_name") ?: request["registry_legal_name"]
        val registryStatus = review?.get("registry_status") ?: request["registry_status"]
        val innMatches = registryInn?.toString() == request["inn"]?.toString()
        val legalMatches = registryLegalName?.toString()?.let {
            CompanyVerificationRules.normalizeLegalName(it) == CompanyVerificationRules.normalizeLegalName(request["legal_name"].toString())
        } == true
        val active = registryStatus == "ACTIVE"
        val checkAvailable = review == null && request["source_check_status"] == "unavailable"
        val notFound = review == null && request["source_check_status"] == "not_found"
        return listOf(
            check("registry", when {
                checkAvailable -> "unknown"
                notFound -> "failed"
                registryStatus == null -> "unknown"
                active && innMatches -> "passed"
                else -> "failed"
            }),
            check("legal_name", when {
                checkAvailable || registryLegalName == null -> "unknown"
                legalMatches -> "passed"
                else -> "failed"
            }),
            check("corporate_email", corporateEmailStatus(company)),
            check(
                "representative",
                if (review?.get("outcome") == "approve" && review?.get("representative_reviewed") == true) "passed" else "unknown",
            ),
        )
    }

    private fun corporateEmailStatus(company: Map<String, Any?>): String {
        if (company["owner_email_verified_at"] == null) return "unknown"
        val ownerEmail = company["owner_email"]?.toString()?.trim()?.lowercase() ?: return "unknown"
        val ownerDomain = ownerEmail.substringAfterLast('@', "").takeIf(String::isNotBlank) ?: return "unknown"
        if (CompanyVerificationRules.isPublicMailboxDomain(ownerDomain)) return "failed"
        val site = CompanyVerificationRules.siteHost(companyData(company)["website"]?.toString()) ?: return "unknown"
        val declaredContact = companyData(company)["contact_email"]?.toString()?.trim()?.lowercase()
        if (declaredContact != null && declaredContact != ownerEmail) return "failed"
        return if (CompanyVerificationRules.siteHost(ownerDomain) == site) "passed" else "failed"
    }

    private fun criteriaSnapshot(company: Map<String, Any?>): Map<String, Any?> {
        val data = companyData(company)
        return linkedMapOf(
            "inn" to CompanyVerificationRules.normalizeInn(data["inn"]?.toString()?.trim().orEmpty()),
            "legal_name" to data["legal_name"]?.toString()?.trim().orEmpty(),
            "website" to data["website"]?.toString()?.trim().orEmpty(),
            "contact_email" to data["contact_email"]?.toString()?.trim()?.lowercase().orEmpty(),
            "owner_id" to company["owner_id"].toString(),
            "owner_email" to company["owner_email"]?.toString()?.trim()?.lowercase().orEmpty(),
            "owner_email_verified" to (company["owner_email_verified_at"] != null),
            "owner_processing_consent" to (company["owner_processing_consent"] == true),
        )
    }

    private fun criteriaHash(snapshot: Map<String, Any?>): String {
        val canonical = snapshot.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun ownerWrapper(company: Map<String, Any?>): Map<String, Any?> {
        val companyId = company.getValue("id").toString()
        val requests = store.rows(
            """SELECT id, version, company_id, company_version, criteria_hash, inn, legal_name, snapshot,
                      status, demo, provider, registry_inn, registry_legal_name, registry_status, registry_updated_at,
                      source_check_status, source_check_error_code, source_type, source_label, source_reference, checked_at, expires_at,
                      review_notes, created_at
               FROM company_verification_requests WHERE company_id = :company
               ORDER BY created_at DESC LIMIT 20""".trimIndent(),
            mapOf("company" to companyId),
        ).map(::requestDto)
        val latest = requests.firstOrNull()
        val snapshot = criteriaSnapshot(company)
        val currentStatus: String = latest?.get("status")?.toString() ?: "unverified"
        return mapOf(
            "summary" to mapOf(
                "company_version" to (company.getValue("version") as Number).toInt(),
                "current_status" to currentStatus,
                "criteria_current" to (latest?.get("criteria_hash")?.toString()?.trim() == criteriaHash(snapshot)),
            ),
            "current_request" to latest,
            "history" to requests,
        )
    }

    private fun requestDto(row: Map<String, Any?>): Map<String, Any?> = mapOf(
        "id" to row["id"],
        "version" to row["version"],
        "company_id" to row["company_id"],
        "company_version" to row["company_version"],
        "criteria_current" to criteriaCurrent(row),
        "criteria_hash" to row["criteria_hash"]?.toString()?.trim(),
        "status" to row["status"],
        "demo" to row["demo"],
        "registry_inn" to row["registry_inn"],
        "registry_legal_name" to row["registry_legal_name"],
        "registry_status" to row["registry_status"],
        "registry_updated_at" to row["registry_updated_at"],
        "source_check_status" to row["source_check_status"],
        "source_check_error_code" to row["source_check_error_code"],
        "inn" to row["inn"],
        "legal_name" to row["legal_name"],
        "snapshot" to row["snapshot"],
        "provider" to row["provider"],
        "source_type" to row["source_type"],
        "source_label" to row["source_label"],
        "source_reference" to row["source_reference"],
        "checked_at" to row["checked_at"],
        "expires_at" to row["expires_at"],
        "review_notes" to row["review_notes"],
        "created_at" to row["created_at"],
    )

    private fun ownerCompany(actor: Actor, lock: Boolean = false): Map<String, Any?> {
        val companyId = actor.companyId ?: throw ApiException(403, "FORBIDDEN", "Employer access required")
        val row = store.one(
            """SELECT c.id, c.owner_id, c.version, c.data, u.email AS owner_email, u.email_verified_at AS owner_email_verified_at,
                      (SELECT ce.action = 'accept' FROM consent_events ce WHERE ce.actor_user_id = u.id
                       AND ce.scope = 'processing' ORDER BY ce.sequence DESC LIMIT 1) AS owner_processing_consent
               FROM companies c JOIN users u ON u.id = c.owner_id
               WHERE c.id = :company AND c.owner_id = :actor
                 AND EXISTS (SELECT 1 FROM memberships m WHERE m.company_id = c.id AND m.user_id = :actor
                             AND m.role = 'owner' AND m.active = true)
               ${if (lock) "FOR UPDATE OF c" else ""}""".trimIndent(),
            mapOf("company" to companyId, "actor" to actor.id),
        ) ?: throw ApiException(403, "FORBIDDEN", "Company owner access required")
        return row
    }

    private fun companyById(companyId: String): Map<String, Any?> = store.one(
        """SELECT c.id, c.owner_id, c.version, c.data, u.email AS owner_email, u.email_verified_at AS owner_email_verified_at,
                  (SELECT ce.action = 'accept' FROM consent_events ce WHERE ce.actor_user_id = u.id
                   AND ce.scope = 'processing' ORDER BY ce.sequence DESC LIMIT 1) AS owner_processing_consent
           FROM companies c JOIN users u ON u.id = c.owner_id WHERE c.id = :company""".trimIndent(),
        mapOf("company" to companyId),
    ) ?: throw ApiException(404, "NOT_FOUND", "Company not found")

    private fun companyData(company: Map<String, Any?>) = jsonMap(company["data"])

    private fun criteriaCurrent(row: Map<String, Any?>): Boolean {
        val companyId = row["company_id"]?.toString() ?: return false
        val liveCompany = companyById(companyId)
        return row["criteria_hash"]?.toString()?.trim() == criteriaHash(criteriaSnapshot(liveCompany))
    }

    private fun jsonMap(value: Any?): Map<String, Any?> = (value as? Map<*, *>)
        ?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()

    private fun asInstant(value: Any?): Instant? = when (value) {
        is Instant -> value
        is java.sql.Timestamp -> value.toInstant()
        else -> null
    }

    private fun check(code: String, status: String) = mapOf("code" to code, "status" to status)
}
