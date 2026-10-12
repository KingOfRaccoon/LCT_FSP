package ru.fsp.talent.proctoring

import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import ru.fsp.talent.core.Store

/** Erases attached recordings when the account's processing consent is revoked. */
@Component
class ProctoringConsentRevoker(private val store: Store, private val media: PrivateMediaStore) {
    fun revoke(userId: String) {
        val sessions = store.rows("SELECT id,attempt_id,status FROM proctoring_sessions WHERE user_id=:user AND status NOT IN ('revoked','expired') ORDER BY attempt_id,id",
            mapOf("user" to userId))
        sessions.forEach { initial ->
            val sessionId = initial.getValue("id").toString()
            val attemptId = initial.getValue("attempt_id").toString()
            val attempt = store.one("SELECT id,status FROM attempts WHERE id=:id FOR UPDATE", mapOf("id" to attemptId))
            store.one("SELECT id FROM proctoring_sessions WHERE id=:id FOR UPDATE", mapOf("id" to sessionId)) ?: return@forEach
            if (attempt?.get("status") in setOf("in_progress", "submitted")) {
                store.exec("UPDATE attempts SET status='invalidated',invalidity_reason='proctoring_processing_consent_revoked',evaluated_at=now(),version=version+1 WHERE id=:id AND status IN ('in_progress','submitted')",
                    mapOf("id" to attemptId))
            }
            store.exec("UPDATE proctoring_sessions SET status='revoked',provisional_result=NULL,version=version+1,updated_at=now() WHERE id=:id",
                mapOf("id" to sessionId))
            store.exec("UPDATE proctoring_captures SET status='interrupted',ended_at=now() WHERE session_id=:id AND status='recording'", mapOf("id" to sessionId))
            store.exec("DELETE FROM proctoring_client_events WHERE session_id=:id", mapOf("id" to sessionId))
            store.exec("INSERT INTO proctoring_audit_events(id,session_id,actor_user_id,action,outcome,metadata) VALUES(:id,:session,:actor,'processing_consent_revoked','media_erasure_requested','{}'::jsonb)",
                mapOf("id" to store.id(), "session" to sessionId, "actor" to userId))
            afterCommit { media.deleteSession(sessionId) }
        }
    }

    private fun afterCommit(action: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        }) else action()
    }
}
