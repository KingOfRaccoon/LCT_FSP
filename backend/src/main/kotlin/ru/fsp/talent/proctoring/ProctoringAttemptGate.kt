package ru.fsp.talent.proctoring

import org.springframework.stereotype.Component
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.Store

/** Database-only boundary used by assessment finalization; it does not depend on the review service. */
@Component
class ProctoringAttemptGate(private val store: Store, private val media: PrivateMediaStore) {
    fun withholdResult(userId: String, attemptId: String, result: Map<String, Any?>, submittedAt: java.time.Instant): Boolean {
        val session = store.one("""SELECT id,status FROM proctoring_sessions
            WHERE attempt_id=:attempt AND user_id=:user FOR UPDATE""".trimIndent(),
            mapOf("attempt" to attemptId, "user" to userId)) ?: return false
        val sessionId = session.getValue("id").toString()
        if (!media.available) throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Private proctoring is unavailable", retryable = true)
        when (session["status"]) {
            "review_pending" -> return true
            "sealed" -> {
                store.exec("""UPDATE proctoring_sessions SET status='review_pending',provisional_result=:result,
                    version=version+1,updated_at=now() WHERE id=:id AND status='sealed'""".trimIndent(),
                    mapOf("id" to sessionId, "result" to store.json(result)))
                store.exec("INSERT INTO proctoring_audit_events(id,session_id,actor_user_id,action,outcome,metadata) VALUES(:id,:session,:actor,'assessment_submitted','human_review_pending','{}'::jsonb)",
                    mapOf("id" to store.id(), "session" to sessionId, "actor" to userId))
                return true
            }
            "recording" -> {
                store.exec("UPDATE proctoring_sessions SET status='technical_failure',version=version+1,updated_at=now() WHERE id=:id", mapOf("id" to sessionId))
                store.exec("UPDATE proctoring_captures SET status='interrupted',ended_at=now() WHERE session_id=:id AND status='recording'", mapOf("id" to sessionId))
                store.exec("UPDATE attempts SET status='invalidated',invalidity_reason='proctoring_recording_incomplete',submitted_at=COALESCE(submitted_at,:at),evaluated_at=:at,version=version+1 WHERE id=:id AND status IN ('in_progress','submitted')",
                    mapOf("id" to attemptId, "at" to submittedAt))
                store.exec("INSERT INTO proctoring_audit_events(id,session_id,actor_user_id,action,outcome,metadata) VALUES(:id,:session,:actor,'assessment_finalized','technical_failure','{}'::jsonb)",
                    mapOf("id" to store.id(), "session" to sessionId, "actor" to userId))
                return true
            }
            else -> throw ApiException(409, "PROCTORING_SESSION_UNAVAILABLE", "Recording session cannot be reviewed")
        }
    }
}
