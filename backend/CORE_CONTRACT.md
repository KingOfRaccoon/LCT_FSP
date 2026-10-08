# Shared Kotlin backend contract

This file is the boundary for parallel domain modules. The implementation
uses Spring Boot 3.5 / Kotlin 2.2 and PostgreSQL; Gradle loads the current
OpenAPI v1 contract from `../docs/contracts/openapi.v1.yaml` into the backend
resources on each build.

## Core package

All core types live in `ru.fsp.talent.core` (subpackages are acceptable for
implementation, but keep these public names/imports stable):

```kotlin
data class Actor(
    val id: String,
    val email: String,
    val role: String,
    val companyId: String? = null,
    val sessionId: String,
    val emailVerified: Boolean,
)

class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val field: String? = null,
    val retryable: Boolean = false,
    val currentVersion: Int? = null,
    val eligibleAt: Instant? = null,
    val activeAttemptId: String? = null,
) : RuntimeException(message)

@Component class Contracts {
    fun validate(requestSchema: String, payload: Map<String, Any?>)
    fun envelope(data: Any?): Map<String, Any?>
    fun iso(value: Instant?): String?
    fun assertVersion(current: Int, expected: Int)
}

@Component class Store(
    val jdbc: NamedParameterJdbcTemplate,
    val tx: TransactionTemplate,
    val mapper: ObjectMapper,
) {
    fun one(sql: String, params: Map<String, *> = emptyMap()): Map<String, Any?>?
    fun rows(sql: String, params: Map<String, *> = emptyMap()): List<Map<String, Any?>>
    fun exec(sql: String, params: Map<String, *> = emptyMap()): Int
    fun now(): Instant
    fun id(): String
    fun json(value: Any?): PGobject
}

@Component class AuthAccess {
    fun current(request: HttpServletRequest): Actor
    fun candidate(request: HttpServletRequest): Actor
    fun employer(request: HttpServletRequest): Actor
    fun verified(actor: Actor): Actor
}

@Component class MutationService(val store: Store) {
    fun run(
        request: HttpServletRequest,
        scope: String,
        body: Map<String, Any?>,
        status: Int = 200,
        action: () -> Map<String, Any?>,
    ): ResponseEntity<Map<String, Any?>>
}
```

Controllers return `Contracts.envelope(data)` or use `MutationService.run()`;
request/response JSON stays `snake_case`. Domain controllers use
`@RestController` and mappings below `/api/v1`, match endpoint shapes from
`OpenAPI v1`, and keep their routers out of the core package.

## Database and behavior boundaries

`Store` binds `:named` parameters through `NamedParameterJdbcTemplate`; UUID ID
columns are returned as canonical strings and ID-named parameters (including
the common `user`, `company`, `candidate`, `attempt`, `need`, and `invitation`
aliases) are bound as UUID values, while text keys such as `scope` remain strings. JSONB values are
decoded as maps/lists by Jackson, and timestamps as `Instant`. `store.tx.execute { ... }` encloses every
multi-row business transition. There is no request-scoped ORM session.

`AuthAccess` reads `fsp_session` as an opaque random token, hashes it before
lookup, and resolves actor + active membership from the database. The CSRF
token is returned only by the CSRF bootstrap endpoint; the session cookie,
session token, and stored hashes are never returned in JSON. `current` requires an authenticated
session; role helpers reject other roles; `verified` additionally requires
verified email.

`MutationService.run()` requires `Idempotency-Key`, hashes a canonical JSON
body, reserves a `(scope, method+path, key)` row, rejects key/body reuse with
409 `IDEMPOTENCY_CONFLICT`, and commits the response envelope and business
changes in the same transaction. Auth registration/login/session rotation
may implement their special replay behavior in the identity module.

`ApiException` maps to `{ "error": { "code", "message", ... } }` without
stack traces or private data. Unknown resources may use 404 to avoid exposing
cross-actor existence. Controllers must not expose attempt keys/answers, FSP
external IDs, or contacts without an active grant.

## Migration inventory

V1 creates `users`, `sessions`, `email_verification`, `companies`,
`memberships`, `candidate_profiles`, `qualifications`, `fsp_links`,
`achievements`, `attempts`, `task_exposures`, `employer_needs`, `shortlists`,
`invitations`, `invitation_versions`, `contact_grants`, `idempotency_records`,
`outbox_events`, and `audit_events`. UUID foreign keys and partial unique
indexes enforce one active attempt per user/specialization, one pending
invitation per company/candidate, and one unrevoked contact grant per pair.
Structured domain snapshots use JSONB where documented in OpenAPI.

## Test boundary

`DbTestBase` uses PostgreSQL 17 database `fsp_test` at the local development
port. It must never truncate or migrate `fsp_dev`. HTTP integration tests get
real servlet cookies and can mint candidate/employer fixtures through SQL.
