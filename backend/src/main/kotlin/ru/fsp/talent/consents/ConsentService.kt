package ru.fsp.talent.consents

import org.springframework.stereotype.Service
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.Store

@Service
class ConsentService(private val store: Store) {
    /** Returns true only when the latest current-version event for both scopes is acceptance. */
    fun publicationAllowed(userId: String): Boolean {
        val processingVersion = SCOPES.getValue("processing").version
        val publicationVersion = SCOPES.getValue("publication").version
        return store.one(
            """SELECT
                   (SELECT ce.action FROM consent_events ce WHERE ce.actor_user_id = u.id
                    AND ce.scope = 'processing' AND ce.version = :processingVersion
                    ORDER BY ce.sequence DESC LIMIT 1) = 'accept'
                   AND
                   (SELECT ce.action FROM consent_events ce WHERE ce.actor_user_id = u.id
                    AND ce.scope = 'publication' AND ce.version = :publicationVersion
                    ORDER BY ce.sequence DESC LIMIT 1) = 'accept' AS allowed
               FROM users u WHERE u.id = :user""".trimIndent(),
            mapOf("user" to userId, "processingVersion" to processingVersion, "publicationVersion" to publicationVersion),
        )?.get("allowed") == true
    }

    /** SQL predicate for public candidate queries. The user alias must be an approved static identifier. */
    fun publicationGateSql(userAlias: String = "u"): String {
        require(userAlias in APPROVED_USER_ALIASES) { "Unapproved consent user alias" }
        val processingVersion = SCOPES.getValue("processing").version
        val publicationVersion = SCOPES.getValue("publication").version
        return """AND (SELECT ce.action FROM consent_events ce WHERE ce.actor_user_id = $userAlias.id
                      AND ce.scope = 'processing' AND ce.version = '$processingVersion'
                      ORDER BY ce.sequence DESC LIMIT 1) = 'accept'
                  AND (SELECT ce.action FROM consent_events ce WHERE ce.actor_user_id = $userAlias.id
                      AND ce.scope = 'publication' AND ce.version = '$publicationVersion'
                      ORDER BY ce.sequence DESC LIMIT 1) = 'accept'""".trimIndent()
    }

    fun getOwn(userId: String): Map<String, Any?> {
        val states = SCOPES.map { (scope, document) -> currentState(userId, scope, document.version) }
        val events = store.rows(
            """SELECT id, scope, version, action, occurred_at FROM consent_events
               WHERE actor_user_id = :user ORDER BY occurred_at DESC, sequence DESC LIMIT :limit""".trimIndent(),
            mapOf("user" to userId, "limit" to EVENT_PAGE_SIZE),
        ).map(::eventDto)
        return mapOf(
            "documents" to SCOPES.map { (scope, document) ->
                mapOf("scope" to scope, "version" to document.version, "title" to document.title, "text" to document.text)
            },
            "states" to states,
            "events" to events,
        )
    }

    fun update(userId: String, role: String, body: Map<String, Any?>): Map<String, Any?> {
        val scope = body.getValue("scope").toString()
        val version = body.getValue("version").toString()
        val action = body.getValue("action").toString()
        val document = SCOPES[scope] ?: throw ApiException(400, "VALIDATION_ERROR", "Unknown consent scope", "scope")
        if (version != document.version) {
            throw ApiException(409, "CONSENT_VERSION_UNAVAILABLE", "This consent version is no longer available", "version")
        }
        if (scope == "publication" && role != "candidate") {
            throw ApiException(403, "FORBIDDEN", "Publication consent is available to candidates")
        }

        store.one("SELECT id FROM users WHERE id = :user FOR UPDATE", mapOf("user" to userId))
            ?: throw ApiException(404, "NOT_FOUND", "Account not found")
        store.exec(
            """INSERT INTO consent_events(id, actor_user_id, scope, version, action)
               VALUES (:id, :user, :scope, :version, :action)""".trimIndent(),
            mapOf("id" to store.id(), "user" to userId, "scope" to scope, "version" to version, "action" to action),
        )
        if (action == "revoke" && role == "candidate") {
            store.exec(
                """UPDATE candidate_profiles SET visibility = 'hidden', version = version + 1, updated_at = now()
                   WHERE user_id = :user AND visibility = 'public'""".trimIndent(),
                mapOf("user" to userId),
            )
        }
        return getOwn(userId)
    }

    fun requireProcessing(userId: String) = requireConsent(userId, "processing")

    fun requirePublication(userId: String) = requireConsent(userId, "publication")

    /**
     * Reads current processing-consent state for accounts whose rows the caller has already locked.
     * The caller must keep those locks until it has finished any consent-gated reads.
     */
    fun processingConsentStatusForLockedUsers(userIds: Collection<String>): Map<String, Boolean> {
        val ids = userIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val processingVersion = SCOPES.getValue("processing").version
        return store.rows(
            """SELECT u.id::text AS user_id, COALESCE(latest.action = 'accept', FALSE) AS active
               FROM users u
               LEFT JOIN LATERAL (
                   SELECT ce.action FROM consent_events ce
                   WHERE ce.actor_user_id = u.id AND ce.scope = 'processing' AND ce.version = :version
                   ORDER BY ce.sequence DESC LIMIT 1
               ) latest ON TRUE
               WHERE u.id IN (:userIds)""".trimIndent(),
            mapOf("version" to processingVersion, "userIds" to ids),
        ).associate { row -> row.getValue("user_id").toString() to (row["active"] == true) }
    }

    private fun requireConsent(userId: String, scope: String) {
        store.one("SELECT id FROM users WHERE id = :user FOR UPDATE", mapOf("user" to userId))
            ?: throw ApiException(404, "NOT_FOUND", "Account not found")
        val version = SCOPES.getValue(scope).version
        val accepted = store.one(
            """SELECT action FROM consent_events WHERE actor_user_id = :user AND scope = :scope AND version = :version
               ORDER BY sequence DESC LIMIT 1""".trimIndent(),
            mapOf("user" to userId, "scope" to scope, "version" to version),
        )?.get("action") == "accept"
        if (!accepted) throw ApiException(403, "CONSENT_REQUIRED", "Accept the current $scope consent to continue")
    }

    private fun currentState(userId: String, scope: String, version: String): Map<String, Any?> {
        val latest = store.one(
            """SELECT action, occurred_at FROM consent_events WHERE actor_user_id = :user AND scope = :scope AND version = :version
               ORDER BY sequence DESC LIMIT 1""".trimIndent(),
            mapOf("user" to userId, "scope" to scope, "version" to version),
        )
        return mapOf(
            "scope" to scope,
            "version" to version,
            "accepted" to (latest?.get("action") == "accept"),
            "updated_at" to latest?.get("occurred_at").asTimestamp(),
        )
    }

    private fun eventDto(row: Map<String, Any?>) = mapOf(
        "id" to row.getValue("id").toString(),
        "scope" to row.getValue("scope").toString(),
        "version" to row.getValue("version").toString(),
        "action" to row.getValue("action").toString(),
        "occurred_at" to row.getValue("occurred_at").asTimestamp(),
    )

    private fun Any?.asTimestamp(): String? = when (this) {
        null -> null
        is java.time.Instant -> toString()
        is java.sql.Timestamp -> toInstant().toString()
        else -> toString()
    }

    private data class Document(val version: String, val title: String, val text: String)

    companion object {
        private const val EVENT_PAGE_SIZE = 100
        private val APPROVED_USER_ALIASES = setOf("u")
        private val SCOPES = linkedMapOf(
            "processing" to Document(
                version = "1.0",
                title = "Согласие на обработку данных — проект для оператора",
                text = "Я разрешаю платформе использовать сведения, которые я предоставил, для ведения профиля, оценки навыков и подбора. Я могу отозвать это согласие; после отзыва редактирование профиля, оценка и публикация будут недоступны до нового принятия. Этот проект текста требует оценки и утверждения оператором платформы и не является заключением о соответствии законодательству.",
            ),
            "publication" to Document(
                version = "1.0",
                title = "Согласие на публикацию профиля — проект для оператора",
                text = "Я разрешаю сделать мой профиль видимым работодателям, прошедшим проверку электронной почты. Я могу отозвать это согласие; платформа скроет профиль. Этот проект текста требует оценки и утверждения оператором платформы и не является заключением о соответствии законодательству.",
            ),
        )
    }
}
