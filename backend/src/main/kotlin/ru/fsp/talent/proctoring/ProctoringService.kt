package ru.fsp.talent.proctoring

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import ru.fsp.talent.consents.ConsentService
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.Store
import ru.fsp.talent.operations.OperationsAccess
import ru.fsp.talent.assessment.AssessmentService
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.ConcurrentHashMap

@Service
class ProctoringService(
    private val store: Store,
    private val consents: ConsentService,
    private val media: PrivateMediaStore,
    private val operations: OperationsAccess,
    private val assessment: AssessmentService,
) {
    // Eight in-flight streams times the hard 4 MiB chunk limit bound concurrent staging to 32 MiB per API process.
    private val uploadSlots = Semaphore(MAX_IN_FLIGHT_UPLOADS, true)
    val maxChunkBytes: Int get() = media.maxChunkBytes
    fun config(): Map<String, Any?> {
        val available = media.available
        return mapOf(
        "available" to available,
        "reason" to media.unavailableReason(available),
        "consent_version" to CONSENT_VERSION,
        "consent_document_sha256" to media.consentHash,
        "consent_text" to media.consentText,
        "retention_days" to media.retentionDays,
        "max_chunk_bytes" to media.maxChunkBytes,
        "max_session_bytes" to media.maxSessionBytes,
        "channels" to CHANNELS,
    )
    }

    fun createSession(userId: String, attemptId: String, version: String, hash: String): Map<String, Any?> =
        store.tx.execute<Map<String, Any?>> {
            if (!media.available) throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Private recording storage is unavailable", retryable = true)
            store.one("SELECT id FROM users WHERE id=:user FOR UPDATE", mapOf("user" to userId))
                ?: notFound()
            consents.requireProcessing(userId)
            val attempt = store.one(
                "SELECT id,status,version FROM attempts WHERE id=:attempt AND user_id=:user FOR UPDATE",
                mapOf("attempt" to uuid(attemptId, "attempt_id"), "user" to userId),
            ) ?: notFound()
            if (attempt["status"] != "in_progress") throw ApiException(409, "INVALID_STATE", "Only an active attempt can start recording")
            if (version != CONSENT_VERSION || !constantTimeEquals(hash, media.consentHash)) {
                throw ApiException(409, "CONSENT_VERSION_UNAVAILABLE", "The current recording consent must be accepted")
            }
            store.one("SELECT id FROM proctoring_sessions WHERE attempt_id=:attempt", mapOf("attempt" to attemptId))?.let {
                throw ApiException(409, "PROCTORING_SESSION_EXISTS", "Recording was already started for this attempt")
            }
            val sessionId = store.id()
            val captureId = store.id()
            store.exec(
                """INSERT INTO proctoring_sessions(id,attempt_id,user_id,status,consent_version,consent_document_sha256,retention_until)
                   VALUES(:id,:attempt,:user,'recording',:version,:hash,:expires)""".trimIndent(),
                mapOf("id" to sessionId, "attempt" to attemptId, "user" to userId, "version" to version,
                    "hash" to hash, "expires" to store.now().plusSeconds(media.retentionDays * 86400L)),
            )
            store.exec("INSERT INTO proctoring_captures(id,session_id,status) VALUES(:id,:session,'recording')",
                mapOf("id" to captureId, "session" to sessionId))
            audit(sessionId, userId, "consent_accepted", "recording_started", mapOf("consent_version" to version, "document_sha256" to hash))
            dto(loadSession(sessionId)!!)
        } ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)

    fun getAttemptSession(userId: String, attemptId: String): Map<String, Any?> = store.tx.execute<Map<String, Any?>> {
        requireAvailable()
        lockUser(userId)
        val row = store.one("SELECT id FROM proctoring_sessions WHERE attempt_id=:attempt AND user_id=:user",
            mapOf("attempt" to uuid(attemptId, "attempt_id"), "user" to userId)) ?: notFound()
        val session = loadSession(row.getValue("id").toString()) ?: notFound()
        requireReadable(session)
        audit(session.getValue("id").toString(), userId, "session_access", "ok")
        dto(session)
    } ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)

    fun getOwnedSession(userId: String, sessionId: String): Map<String, Any?> = store.tx.execute<Map<String, Any?>> {
        requireAvailable()
        lockUser(userId)
        val session = loadOwnedSession(userId, sessionId) ?: notFound()
        requireReadable(session)
        audit(sessionId, userId, "session_access", "ok")
        dto(session)
    } ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)

    /** Keeps owner, active consent, attempt, and session locked through the controller's idempotency replay check. */
    fun authorizeOwnedMutation(userId: String, sessionId: String) {
        requireAvailable()
        lockUser(userId)
        consents.requireProcessing(userId)
        lockAttemptForSession(userId, sessionId)
        val session = loadOwnedSession(userId, sessionId, lock = true) ?: notFound()
        requireReadable(session)
    }

    fun authorizeRevocation(userId: String, sessionId: String) {
        lockUser(userId)
        lockAttemptForSession(userId, sessionId)
        loadOwnedSession(userId, sessionId, lock = true) ?: notFound()
    }

    fun authorizeSessionCreation(userId: String, attemptId: String, version: String, hash: String) {
        requireAvailable()
        lockUser(userId)
        consents.requireProcessing(userId)
        if (version != CONSENT_VERSION || !constantTimeEquals(hash, media.consentHash)) {
            throw ApiException(409, "CONSENT_VERSION_UNAVAILABLE", "The current recording consent must be accepted")
        }
        val attempt = store.one("SELECT id,status FROM attempts WHERE id=:attempt AND user_id=:user FOR UPDATE",
            mapOf("attempt" to uuid(attemptId, "attempt_id"), "user" to userId)) ?: notFound()
        val existing = store.one("SELECT id,status,retention_until FROM proctoring_sessions WHERE attempt_id=:attempt AND user_id=:user FOR UPDATE",
            mapOf("attempt" to attemptId, "user" to userId))
        if (existing != null) {
            if (existing["status"] in setOf("revoked", "expired") || !store.now().isBefore(existing.getValue("retention_until") as Instant)) notFound()
        } else if (attempt["status"] != "in_progress") {
            throw ApiException(409, "INVALID_STATE", "Only an active attempt can start recording")
        }
    }

    fun authorizeReview(reviewerId: String, sessionId: String) {
        requireAvailable()
        requireReviewer(reviewerId)
        val row = store.one("SELECT user_id,attempt_id,retention_until FROM proctoring_sessions WHERE id=:id", mapOf("id" to uuid(sessionId, "session_id"))) ?: notFound()
        if (row.getValue("user_id").toString() == reviewerId) notFound()
        val candidateId = row.getValue("user_id").toString()
        lockUser(candidateId)
        store.one("SELECT id FROM attempts WHERE id=:attempt AND user_id=:user FOR UPDATE",
            mapOf("attempt" to row.getValue("attempt_id"), "user" to candidateId)) ?: notFound()
        store.one("SELECT id FROM proctoring_sessions WHERE id=:id AND user_id=:user FOR UPDATE",
            mapOf("id" to sessionId, "user" to candidateId)) ?: notFound()
        if (!store.now().isBefore(row.getValue("retention_until") as Instant)) notFound()
        requireCurrentProcessing(candidateId)
    }

    fun rotateCapture(userId: String, sessionId: String, expectedVersion: Int): Map<String, Any?> =
        store.tx.execute<Map<String, Any?>> {
            lockUser(userId)
            consents.requireProcessing(userId)
            lockAttemptForSession(userId, sessionId)
            val row = loadOwnedSession(userId, sessionId, lock = true) ?: notFound()
            requireWritable(row, expectedVersion)
            val captureCount = store.one("SELECT count(*) AS count FROM proctoring_captures WHERE session_id=:session", mapOf("session" to sessionId))!!.getValue("count") as Number
            if (captureCount.toInt() >= MAX_CAPTURES) throw ApiException(409, "CAPTURE_LIMIT", "Recording capture limit reached")
            val prior = row["active_capture_id"]?.toString()
            prior?.let { store.exec("UPDATE proctoring_captures SET status='interrupted',ended_at=now() WHERE id=:id AND status='recording'", mapOf("id" to it)) }
            val nextId = store.id()
            store.exec("INSERT INTO proctoring_captures(id,session_id,status) VALUES(:id,:session,'recording')", mapOf("id" to nextId, "session" to sessionId))
            store.exec("UPDATE proctoring_sessions SET status='recording',version=version+1,updated_at=now() WHERE id=:id", mapOf("id" to sessionId))
            audit(sessionId, userId, "capture_rotated", "ok", mapOf("interrupted_capture_id" to prior))
            dto(loadSession(sessionId)!!)
        } ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)

    fun recordEvents(userId: String, sessionId: String, expectedVersion: Int, events: List<Map<String, Any?>>): Map<String, Any?> =
        store.tx.execute<Map<String, Any?>> {
            lockUser(userId)
            consents.requireProcessing(userId)
            lockAttemptForSession(userId, sessionId)
            val session = loadOwnedSession(userId, sessionId, lock = true) ?: notFound()
            requireWritable(session, expectedVersion)
            if (events.isEmpty() || events.size > MAX_EVENTS_PER_REQUEST) invalid("events")
            val currentCount = (session["events_count"] as Number).toInt()
            if (currentCount + events.size > MAX_EVENTS) throw ApiException(413, "PROCTORING_EVENT_LIMIT", "Recording event limit reached")
            val earliestEvent = maxOf(session.getValue("consent_accepted_at") as Instant, session.getValue("attempt_started_at") as Instant)
                .minusSeconds(EVENT_CLOCK_TOLERANCE_SECONDS)
            val latestEvent = store.now().plusSeconds(EVENT_CLOCK_TOLERANCE_SECONDS)
            events.forEach { event ->
                if (event.keys != setOf("event_id", "type", "occurred_at")) invalid("events")
                val eventId = uuid(event["event_id"]?.toString().orEmpty(), "event_id")
                val type = event["type"]?.toString()?.takeIf { it in EVENT_TYPES } ?: invalid("events")
                val occurred = try { Instant.parse(event["occurred_at"].toString()) } catch (_: Exception) { invalid("events") }
                if (occurred.isBefore(earliestEvent) || occurred.isAfter(latestEvent)) invalid("events")
                store.exec("""INSERT INTO proctoring_client_events(id,session_id,event_id,event_type,occurred_at)
                    VALUES(:id,:session,:eventId,:type,:occurred) ON CONFLICT(session_id,event_id) DO NOTHING""".trimIndent(),
                    mapOf("id" to store.id(), "session" to sessionId, "eventId" to eventId, "type" to type, "occurred" to occurred))
            }
            store.exec("UPDATE proctoring_sessions SET events_count=(SELECT count(*) FROM proctoring_client_events WHERE session_id=:id),version=version+1,updated_at=now() WHERE id=:id", mapOf("id" to sessionId))
            audit(sessionId, userId, "events_received", "ok", mapOf("count" to events.size))
            dto(loadSession(sessionId)!!)
        } ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)

    fun seal(userId: String, sessionId: String, expectedVersion: Int, captureId: String, lastSequences: Map<String, Int>): Map<String, Any?> =
        store.tx.execute<Map<String, Any?>> {
            lockUser(userId)
            consents.requireProcessing(userId)
            lockAttemptForSession(userId, sessionId)
            val session = loadOwnedSession(userId, sessionId, lock = true) ?: notFound()
            requireWritable(session, expectedVersion)
            if (lastSequences.keys != CHANNELS.toSet()) invalid("last_sequences")
            val capture = store.one("SELECT id,status FROM proctoring_captures WHERE id=:captureId AND session_id=:session FOR UPDATE",
                mapOf("captureId" to uuid(captureId, "capture_id"), "session" to sessionId)) ?: notFound()
            if (capture["status"] != "recording") throw ApiException(409, "INVALID_STATE", "Capture is not recording")
            CHANNELS.forEach { channel ->
                val expectedLast = lastSequences[channel] ?: invalid("last_sequences")
                if (expectedLast !in 0..MAX_CHUNK_SEQUENCE) invalid("last_sequences")
                val receipt = store.one("""SELECT count(*) AS count, coalesce(max(sequence),-1) AS last
                    FROM proctoring_media_receipts WHERE capture_id=:captureId AND channel=:channel""".trimIndent(),
                    mapOf("captureId" to captureId, "channel" to channel))!!
                if ((receipt["count"] as Number).toInt() != expectedLast + 1 || (receipt["last"] as Number).toInt() != expectedLast) {
                    throw ApiException(409, "INCOMPLETE_CAPTURE", "Media chunks are missing")
                }
            }
            if (CHANNELS.any { channel ->
                    val count = store.one("SELECT count(*) AS count FROM proctoring_media_receipts WHERE capture_id=:captureId AND channel=:channel",
                        mapOf("captureId" to captureId, "channel" to channel))!!.getValue("count") as Number
                    count.toInt() == 0
                }) throw ApiException(409, "INCOMPLETE_CAPTURE", "Both camera and screen recordings are required")
            store.exec("UPDATE proctoring_captures SET status='sealed',ended_at=now() WHERE id=:id", mapOf("id" to captureId))
            store.exec("UPDATE proctoring_sessions SET status='sealed',version=version+1,updated_at=now() WHERE id=:id", mapOf("id" to sessionId))
            audit(sessionId, userId, "capture_sealed", "ok", mapOf("capture_id" to captureId))
            dto(loadSession(sessionId)!!)
        } ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)

    fun authorizeChunk(userId: String, sessionId: String, captureId: String, channel: String, sequence: Int, claimedDigest: String) {
        requireAvailable()
        lockUser(userId)
        consents.requireProcessing(userId)
        val row = loadOwnedSession(userId, sessionId) ?: notFound()
        requireReadable(row)
        if (channel !in CHANNELS || sequence !in 0..MAX_CHUNK_SEQUENCE) invalid("sequence")
        if (store.one("SELECT id FROM proctoring_captures WHERE id=:captureId AND session_id=:session",
                mapOf("captureId" to uuid(captureId, "capture_id"), "session" to sessionId)) == null) notFound()
        val digest = claimedDigest.lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: invalid("X-Content-SHA256")
        if (row["status"] == "recording" && row["active_capture_id"] == captureId) return
        val prior = store.one("""SELECT sha256 FROM proctoring_media_receipts WHERE session_id=:session AND capture_id=:captureId
            AND channel=:channel AND sequence=:sequence""".trimIndent(),
            mapOf("session" to sessionId, "captureId" to captureId, "channel" to channel, "sequence" to sequence))
        if (prior?.get("sha256")?.toString()?.trim() != digest) throw ApiException(409, "INVALID_STATE", "Recording is no longer active")
    }

    fun acceptChunk(userId: String, sessionId: String, captureId: String, channel: String, sequence: Int,
                    key: String, claimedDigest: String, input: InputStream): Map<String, Any?> {
        uuid(sessionId, "session_id"); uuid(captureId, "capture_id")
        if (channel !in CHANNELS || sequence !in 0..MAX_CHUNK_SEQUENCE) invalid("sequence")
        val normalizedDigest = claimedDigest.lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: invalid("X-Content-SHA256")
        if (key.length !in 16..128) invalid("Idempotency-Key")
        authorizeChunk(userId, sessionId, captureId, channel, sequence, normalizedDigest)
        val keyHash = sha256(key.toByteArray())
        val prior = store.one("""SELECT sha256,byte_count FROM proctoring_media_receipts
            WHERE session_id=:session AND capture_id=:captureId AND channel=:channel AND sequence=:sequence""".trimIndent(),
            mapOf("session" to sessionId, "captureId" to captureId, "channel" to channel, "sequence" to sequence))
        if (prior != null) {
            if (prior.getValue("sha256").toString().trim() != normalizedDigest) throw ApiException(409, "CHUNK_CONFLICT", "Chunk sequence already contains different media")
            return receipt(sessionId, captureId, channel, sequence, normalizedDigest, (prior.getValue("byte_count") as Number).toInt())
        }
        val currentBytes = store.one("SELECT coalesce(sum(byte_count),0) AS bytes FROM proctoring_media_receipts WHERE session_id=:session",
            mapOf("session" to sessionId))!!.getValue("bytes") as Number
        if (currentBytes.toLong() >= media.maxSessionBytes) throw ApiException(413, "PROCTORING_SESSION_LIMIT", "Recording size limit reached")
        if (!uploadSlots.tryAcquire()) throw ApiException(429, "PROCTORING_UPLOAD_CAPACITY", "Recording upload capacity is busy; retry shortly", retryable = true)
        try {
        val staged = media.stage(sessionId, captureId, channel, sequence, input)
        if (!constantTimeEquals(staged.sha256, normalizedDigest)) {
            media.delete(staged.path)
            throw ApiException(400, "CHUNK_HASH_MISMATCH", "Chunk digest does not match the received bytes")
        }
        var committedPath: Path? = null
        try {
            val result = store.tx.execute<Map<String, Any?>> {
                lockUser(userId)
                consents.requireProcessing(userId)
                lockAttemptForSession(userId, sessionId)
                val session = loadOwnedSession(userId, sessionId, lock = true) ?: notFound()
                if (!media.available) throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Private recording storage is unavailable", retryable = true)
                if (session["status"] != "recording" || session["active_capture_id"] != captureId) throw ApiException(409, "INVALID_STATE", "Recording is no longer active")
                val existing = store.one("""SELECT sha256,byte_count FROM proctoring_media_receipts
                    WHERE session_id=:session AND capture_id=:captureId AND channel=:channel AND sequence=:sequence FOR UPDATE""".trimIndent(),
                    mapOf("session" to sessionId, "captureId" to captureId, "channel" to channel, "sequence" to sequence))
                if (existing != null) {
                    if (existing.getValue("sha256").toString().trim() != normalizedDigest) throw ApiException(409, "CHUNK_CONFLICT", "Chunk sequence already contains different media")
                    media.delete(staged.path)
                    return@execute receipt(sessionId, captureId, channel, sequence, normalizedDigest, (existing.getValue("byte_count") as Number).toInt())
                }
                val total = store.one("SELECT coalesce(sum(byte_count),0) AS bytes FROM proctoring_media_receipts WHERE session_id=:session",
                    mapOf("session" to sessionId))!!.getValue("bytes") as Number
                if (total.toLong() + staged.byteCount > media.maxSessionBytes) throw ApiException(413, "PROCTORING_SESSION_LIMIT", "Recording size limit reached")
                committedPath = media.commit(staged, sessionId, captureId, channel, sequence)
                store.exec("""INSERT INTO proctoring_media_receipts(id,session_id,capture_id,channel,sequence,sha256,byte_count,idempotency_key_hash)
                    VALUES(:id,:session,:captureId,:channel,:sequence,:sha,:bytes,:keyHash)""".trimIndent(),
                    mapOf("id" to store.id(), "session" to sessionId, "captureId" to captureId, "channel" to channel,
                        "sequence" to sequence, "sha" to normalizedDigest, "bytes" to staged.byteCount, "keyHash" to keyHash))
                store.exec("UPDATE proctoring_sessions SET version=version+1,updated_at=now() WHERE id=:id", mapOf("id" to sessionId))
                audit(sessionId, userId, "media_chunk_received", "ok", mapOf("channel" to channel, "sequence" to sequence, "bytes" to staged.byteCount, "sha256" to normalizedDigest))
                receipt(sessionId, captureId, channel, sequence, normalizedDigest, staged.byteCount)
            } ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)
            return result
        } catch (error: Exception) {
            media.delete(staged.path)
            committedPath?.let(media::delete)
            throw error
        }
        } finally {
            uploadSlots.release()
        }
    }

    fun prepareMedia(actorId: String, sessionId: String, captureId: String, channel: String,
                     rangeHeader: String?): MediaPayload {
        if (!media.available) throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Private recording storage is unavailable", retryable = true)
        if (channel !in CHANNELS) invalid("channel")
        val session = loadSession(uuid(sessionId, "session_id")) ?: notFound()
        val userId = session.getValue("user_id").toString()
        val reviewer = actorId != userId
        if (reviewer) {
            requireReviewer(actorId)
            if (session["status"] != "review_pending") notFound()
        }
        requireReadable(session)
        val capture = store.one("SELECT id,status FROM proctoring_captures WHERE id=:captureId AND session_id=:session",
            mapOf("captureId" to uuid(captureId, "capture_id"), "session" to sessionId)) ?: notFound()
        if (capture["status"] == "recording") throw ApiException(409, "INVALID_STATE", "Recording is not available until capture is sealed")
        val receipts = store.rows("SELECT sequence,byte_count FROM proctoring_media_receipts WHERE session_id=:session AND capture_id=:captureId AND channel=:channel ORDER BY sequence",
            mapOf("session" to sessionId, "captureId" to captureId, "channel" to channel))
        if (receipts.isEmpty()) notFound()
        val paths = receipts.map { media.chunkPath(sessionId, captureId, channel, (it.getValue("sequence") as Number).toInt()) to (it.getValue("byte_count") as Number).toLong() }
        val total = receipts.sumOf { (it.getValue("byte_count") as Number).toLong() }
        val range = parseRange(rangeHeader, total)
        audit(sessionId, actorId, "media_access", if (reviewer) "reviewer" else "owner",
            mapOf("capture_id" to captureId, "channel" to channel, "range" to rangeHeader))
        return MediaPayload(actorId, reviewer, sessionId, captureId, channel, paths, total, range.first, range.second, rangeHeader != null)
    }

    fun streamMedia(payload: MediaPayload, output: java.io.OutputStream) {
        var offset = payload.start
        var remaining = payload.end - payload.start + 1
        payload.paths.forEach { (path, length) ->
            if (remaining <= 0) return
            if (offset >= length) { offset -= length; return@forEach }
            openAuthorizedMedia(payload, path, length).use { input ->
                val startInFile = offset
                var toSkip = startInFile
                while (toSkip > 0) {
                    val skipped = input.skip(toSkip)
                    if (skipped <= 0) { if (input.read() < 0) break else toSkip-- } else toSkip -= skipped
                }
                offset = 0
                var leftInFile = minOf(remaining, length - startInFile)
                val buffer = ByteArray(64 * 1024)
                while (leftInFile > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), leftInFile).toInt())
                    if (read < 0) throw java.io.EOFException("Private media file was truncated")
                    output.write(buffer, 0, read)
                    leftInFile -= read
                    remaining -= read
                }
            }
        }
    }

    private fun openAuthorizedMedia(payload: MediaPayload, path: Path, expectedSize: Long): InputStream =
        store.tx.execute<InputStream> {
            if (!media.available) throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Private recording storage is unavailable", retryable = true)
            val initial = loadSession(payload.sessionId) ?: notFound()
            val ownerId = initial.getValue("user_id").toString()
            lockUser(ownerId)
            store.one("SELECT id FROM attempts WHERE id=:attempt AND user_id=:user FOR SHARE",
                mapOf("attempt" to initial.getValue("attempt_id"), "user" to ownerId)) ?: notFound()
            val session = store.one("""SELECT id,user_id,status,retention_until FROM proctoring_sessions
                WHERE id=:id AND user_id=:user FOR SHARE""".trimIndent(), mapOf("id" to payload.sessionId, "user" to ownerId)) ?: notFound()
            if (payload.actorId != ownerId) {
                requireReviewer(payload.actorId)
                if (session["status"] != "review_pending") notFound()
            }
            requireReadable(session)
            if (media.size(path) != expectedSize) throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Private recording receipt does not match its file", retryable = true)
            media.open(path)
        } ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)

    fun revoke(userId: String, sessionId: String, expectedVersion: Int): Map<String, Any?> = store.tx.execute<Map<String, Any?>> {
        lockUser(userId)
        lockAttemptForSession(userId, sessionId)
        val session = loadOwnedSession(userId, sessionId, lock = true) ?: notFound()
        if ((session["version"] as Number).toInt() != expectedVersion) versionConflict((session["version"] as Number).toInt())
        if (session["status"] == "revoked") return@execute dto(session)
        val attempt = store.one("SELECT id,status FROM attempts WHERE id=:attempt FOR UPDATE", mapOf("attempt" to session.getValue("attempt_id"))) ?: notFound()
        store.exec("UPDATE proctoring_sessions SET status='revoked',version=version+1,updated_at=now(),provisional_result=NULL WHERE id=:id", mapOf("id" to sessionId))
        if (attempt["status"] == "in_progress" || attempt["status"] == "submitted") {
            store.exec("UPDATE attempts SET status='invalidated',invalidity_reason='proctoring_consent_revoked',evaluated_at=now(),version=version+1 WHERE id=:id AND status IN ('in_progress','submitted')", mapOf("id" to attempt.getValue("id")))
        }
        store.exec("DELETE FROM proctoring_client_events WHERE session_id=:id", mapOf("id" to sessionId))
        audit(sessionId, userId, "session_revoked", "media_erasure_requested")
        afterCommit { media.deleteSession(sessionId) }
        dto(loadSession(sessionId)!!)
    } ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)

    fun reviewQueue(reviewerId: String, requestedLimit: Int?, cursor: String?): Map<String, Any?> {
        requireAvailable()
        requireReviewer(reviewerId)
        val limit = (requestedLimit ?: 20).also { if (it !in 1..100) invalid("limit") }
        val after = cursor?.let(::decodeCursor)
        val cursorClause = if (after == null) "" else "AND (s.created_at,s.id) > (CAST(:afterAt AS timestamptz),CAST(:afterId AS uuid))"
        val params = mutableMapOf<String, Any?>("reviewerId" to reviewerId, "processingVersion" to consents.currentVersion("processing"), "take" to limit + 1)
        after?.let { params["afterAt"] = it.first; params["afterId"] = it.second }
        val rows = store.rows("""SELECT s.id,s.created_at,s.version,a.id AS attempt_id,a.requested_grade,a.submitted_at
            FROM proctoring_sessions s JOIN attempts a ON a.id=s.attempt_id
            WHERE s.status='review_pending' AND s.retention_until>now() AND s.user_id<>:reviewerId
              AND (SELECT ce.action FROM consent_events ce WHERE ce.actor_user_id=s.user_id AND ce.scope='processing'
                   AND ce.version=:processingVersion ORDER BY ce.sequence DESC LIMIT 1)='accept'
            $cursorClause
            ORDER BY s.created_at,s.id LIMIT :take""".trimIndent(), params)
        val page = rows.take(limit)
        val next = if (rows.size > limit) encodeCursor(page.last().getValue("created_at").toString(), page.last().getValue("id").toString()) else null
        val items = page.map { row ->
            audit(row.getValue("id").toString(), reviewerId, "review_queue_access", "ok")
            mapOf("session_id" to row.getValue("id").toString(), "attempt_id" to row.getValue("attempt_id").toString(),
                "version" to row.getValue("version"), "status" to "review_pending",
                "requested_grade" to row.getValue("requested_grade"), "submitted_at" to row["submitted_at"]?.toString())
        }
        return mapOf("items" to items, "next_cursor" to next)
    }

    fun reviewDetail(reviewerId: String, sessionId: String): Map<String, Any?> = store.tx.execute<Map<String, Any?>> {
        requireAvailable()
        requireReviewer(reviewerId)
        val owner = store.one("SELECT user_id,attempt_id FROM proctoring_sessions WHERE id=:id", mapOf("id" to uuid(sessionId, "session_id"))) ?: notFound()
        val candidateId = owner.getValue("user_id").toString()
        if (candidateId == reviewerId) notFound()
        lockUser(candidateId)
        store.one("SELECT id FROM attempts WHERE id=:attempt AND user_id=:user FOR SHARE",
            mapOf("attempt" to owner.getValue("attempt_id"), "user" to candidateId)) ?: notFound()
        store.one("SELECT id FROM proctoring_sessions WHERE id=:id AND user_id=:user FOR SHARE",
            mapOf("id" to sessionId, "user" to candidateId)) ?: notFound()
        val row = store.one("""SELECT s.id,s.user_id,s.attempt_id,s.version,s.status,s.provisional_result,s.retention_until,
            a.version AS attempt_version,a.requested_grade,a.status AS attempt_status,a.started_at,a.deadline_at,a.submitted_at,a.tasks
            FROM proctoring_sessions s JOIN attempts a ON a.id=s.attempt_id WHERE s.id=:id""".trimIndent(),
            mapOf("id" to uuid(sessionId, "session_id"))) ?: notFound()
        if (row["status"] != "review_pending" || !store.now().isBefore(row.getValue("retention_until") as Instant)) notFound()
        requireCurrentProcessing(row.getValue("user_id").toString())
        audit(sessionId, reviewerId, "review_access", "ok")
        val tasksMap = row["tasks"].asMap()
        val attempt = mapOf("attempt_id" to row.getValue("attempt_id").toString(), "version" to row.getValue("attempt_version"),
            "requested_grade" to row.getValue("requested_grade"), "status" to row.getValue("attempt_status"),
            "started_at" to row["started_at"]?.toString(), "deadline_at" to row["deadline_at"]?.toString(),
            "submitted_at" to row["submitted_at"]?.toString(), "tasks" to tasksMap["items"])
        mapOf("session_id" to row.getValue("id").toString(), "attempt_id" to row.getValue("attempt_id").toString(),
            "version" to row.getValue("version"), "attempt_version" to row.getValue("attempt_version"),
            "status" to row.getValue("status"), "provisional_result" to row["provisional_result"], "attempt" to attempt,
            "recordings" to dto(loadSession(sessionId)!!)["captures"])
    } ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)

    fun decideReview(reviewerId: String, sessionId: String, expectedVersion: Int, expectedAttemptVersion: Int,
                     outcome: String, reasonCode: String, note: String): Map<String, Any?> =
        store.tx.execute<Map<String, Any?>> {
            requireAvailable()
            requireReviewer(reviewerId)
            val initial = store.one("SELECT user_id,attempt_id FROM proctoring_sessions WHERE id=:id", mapOf("id" to uuid(sessionId, "session_id"))) ?: notFound()
            val candidateId = initial.getValue("user_id").toString()
            val attemptId = initial.getValue("attempt_id").toString()
            lockUser(candidateId)
            val attempt = store.one("SELECT id,status,version FROM attempts WHERE id=:attempt AND user_id=:user FOR UPDATE",
                mapOf("attempt" to attemptId, "user" to candidateId)) ?: notFound()
            val session = store.one("SELECT id,version,status,retention_until FROM proctoring_sessions WHERE id=:id AND user_id=:user FOR UPDATE",
                mapOf("id" to sessionId, "user" to candidateId)) ?: notFound()
            if (candidateId == reviewerId) notFound()
            requireCurrentProcessing(candidateId)
            if (session["status"] != "review_pending" || !store.now().isBefore(session.getValue("retention_until") as Instant)) notFound()
            val currentVersion = (session.getValue("version") as Number).toInt()
            if (currentVersion != expectedVersion) versionConflict(currentVersion)
            val attemptVersion = (attempt.getValue("version") as Number).toInt()
            if (attemptVersion != expectedAttemptVersion) versionConflict(attemptVersion)
            if (attempt["status"] != "submitted") throw ApiException(409, "INVALID_STATE", "Assessment is no longer pending review")
            if (note.isBlank() || note.length > 2000) invalid("note")
            val reasonValid = when (outcome) {
                "uphold" -> reasonCode == "approved"
                "invalidate" -> reasonCode in setOf("unauthorized_assistance", "identity_mismatch")
                "technical" -> reasonCode in setOf("recording_incomplete", "technical_failure")
                else -> false
            }
            if (!reasonValid) invalid("reason_code")
            when (outcome) {
                "uphold" -> assessment.upholdProctoredAttempt(attemptId)
                "invalidate", "technical" -> store.exec("""UPDATE attempts SET status='invalidated',invalidity_reason=:reason,
                    evaluated_at=now(),version=version+1 WHERE id=:id AND status='submitted' AND version=:version""".trimIndent(),
                    mapOf("reason" to "proctoring_${reasonCode}", "id" to attemptId, "version" to attemptVersion)).also {
                    if (it != 1) versionConflict(attemptVersion)
                }
            }
            val sessionStatus = if (outcome == "uphold") "approved" else if (outcome == "technical") "technical_failure" else "invalidated"
            store.exec("""UPDATE proctoring_sessions SET status=:status,version=version+1,decision_outcome=:outcome,
                decision_reason_code=:reason,decision_note=:note,decided_by=:reviewerId,decided_at=now(),provisional_result=NULL,updated_at=now()
                WHERE id=:id AND version=:version""".trimIndent(), mapOf("status" to sessionStatus, "outcome" to outcome,
                "reason" to reasonCode, "note" to note, "reviewerId" to reviewerId, "id" to sessionId, "version" to expectedVersion)).also {
                if (it != 1) versionConflict(currentVersion)
            }
            audit(sessionId, reviewerId, "review_decision", outcome, mapOf("reason_code" to reasonCode, "note" to note))
            dto(loadSession(sessionId)!!)
        } ?: throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Please retry", retryable = true)

    @Scheduled(fixedDelay = 3_600_000)
    fun expireSessionsAndCleanOrphans() {
        val expired = store.rows("SELECT id,user_id,attempt_id FROM proctoring_sessions WHERE retention_until<=now() AND status NOT IN ('expired','revoked') ORDER BY retention_until LIMIT 250")
        expired.forEach { initial ->
            val sessionId = initial.getValue("id").toString()
            val candidateId = initial.getValue("user_id").toString()
            store.tx.execute {
                lockUser(candidateId)
                val attempt = store.one("SELECT id,status FROM attempts WHERE id=:attempt AND user_id=:user FOR UPDATE",
                    mapOf("attempt" to initial.getValue("attempt_id"), "user" to candidateId))
                val session = store.one("SELECT id,status FROM proctoring_sessions WHERE id=:id AND retention_until<=now() FOR UPDATE",
                    mapOf("id" to sessionId)) ?: return@execute
                if (attempt?.get("status") in setOf("in_progress", "submitted")) {
                    store.exec("UPDATE attempts SET status='invalidated',invalidity_reason='proctoring_retention_expired',evaluated_at=now(),version=version+1 WHERE id=:id AND status IN ('in_progress','submitted')",
                        mapOf("id" to attempt!!.getValue("id")))
                }
                store.exec("UPDATE proctoring_sessions SET status='expired',provisional_result=NULL,version=version+1,updated_at=now() WHERE id=:id", mapOf("id" to sessionId))
                store.exec("UPDATE proctoring_captures SET status='interrupted',ended_at=now() WHERE session_id=:id AND status='recording'", mapOf("id" to sessionId))
                store.exec("DELETE FROM proctoring_client_events WHERE session_id=:id", mapOf("id" to sessionId))
                audit(sessionId, null, "retention_expired", "media_erasure_requested")
                afterCommit { media.deleteSession(sessionId) }
            }
        }
        media.findOrphanCandidates(store.now().minusSeconds(3600)).forEach(::removeOrphanIfStillMissing)
    }

    private fun removeOrphanIfStillMissing(candidate: MediaOrphanCandidate) {
        val sessionId = candidate.sessionId
        store.tx.execute {
            val initial = store.one("SELECT user_id,attempt_id FROM proctoring_sessions WHERE id=:id", mapOf("id" to sessionId))
            if (initial == null) {
                if (candidate.captureId == null) media.deleteSession(sessionId)
                else media.delete(media.chunkPath(sessionId, candidate.captureId, candidate.channel!!, candidate.sequence!!))
                return@execute
            }
            val userId = initial.getValue("user_id").toString()
            lockUser(userId)
            val attempt = store.one("SELECT id,status FROM attempts WHERE id=:attempt AND user_id=:user FOR UPDATE",
                mapOf("attempt" to initial.getValue("attempt_id"), "user" to userId)) ?: return@execute
            val session = store.one("SELECT id,status,retention_until FROM proctoring_sessions WHERE id=:id AND user_id=:user FOR UPDATE",
                mapOf("id" to sessionId, "user" to userId)) ?: return@execute
            if (session["status"] in setOf("revoked", "expired") || !store.now().isBefore(session.getValue("retention_until") as Instant)) {
                if (attempt["status"] in setOf("in_progress", "submitted")) {
                    store.exec("UPDATE attempts SET status='invalidated',invalidity_reason='proctoring_retention_expired',evaluated_at=now(),version=version+1 WHERE id=:id AND status IN ('in_progress','submitted')",
                        mapOf("id" to attempt.getValue("id")))
                }
                if (session["status"] !in setOf("revoked", "expired")) {
                    store.exec("UPDATE proctoring_sessions SET status='expired',provisional_result=NULL,version=version+1,updated_at=now() WHERE id=:id", mapOf("id" to sessionId))
                    store.exec("DELETE FROM proctoring_client_events WHERE session_id=:id", mapOf("id" to sessionId))
                    audit(sessionId, null, "retention_expired", "media_erasure_requested")
                }
                media.deleteSession(sessionId)
                return@execute
            }
            if (candidate.stagedName != null) {
                media.deleteStaged(sessionId, candidate.captureId!!, candidate.stagedName)
                return@execute
            }
            val captureId = candidate.captureId ?: return@execute
            val receipt = store.one("""SELECT id FROM proctoring_media_receipts WHERE session_id=:session AND capture_id=:captureId
                AND channel=:channel AND sequence=:sequence""".trimIndent(), mapOf("session" to sessionId, "captureId" to captureId,
                "channel" to candidate.channel, "sequence" to candidate.sequence))
            if (receipt == null) media.delete(media.chunkPath(sessionId, captureId, candidate.channel!!, candidate.sequence!!))
        }
    }

    private fun loadOwnedSession(userId: String, sessionId: String, lock: Boolean = false): Map<String, Any?>? =
        store.one("""SELECT s.id,s.user_id,s.attempt_id,s.version,s.status,s.consent_version,s.consent_document_sha256,s.consent_accepted_at,
            s.retention_until,s.events_count,s.decision_outcome,s.decision_reason_code,s.decision_note,s.decided_at,
            a.version AS attempt_version,a.started_at AS attempt_started_at,c.id AS active_capture_id
            FROM proctoring_sessions s JOIN attempts a ON a.id=s.attempt_id
            LEFT JOIN proctoring_captures c ON c.session_id=s.id AND c.status='recording'
            WHERE s.id=:id AND s.user_id=:user ORDER BY c.started_at DESC LIMIT 1 ${if (lock) "FOR UPDATE OF s" else ""}""".trimIndent(),
            mapOf("id" to uuid(sessionId, "session_id"), "user" to userId))

    private fun loadSession(sessionId: String): Map<String, Any?>? = store.one("""SELECT s.id,s.user_id,s.attempt_id,s.version,s.status,s.consent_version,
        s.consent_document_sha256,s.consent_accepted_at,s.retention_until,s.events_count,s.decision_outcome,s.decision_reason_code,
        s.decision_note,s.decided_at,a.version AS attempt_version,c.id AS active_capture_id
        FROM proctoring_sessions s JOIN attempts a ON a.id=s.attempt_id
        LEFT JOIN proctoring_captures c ON c.session_id=s.id AND c.status='recording'
        WHERE s.id=:id ORDER BY c.started_at DESC LIMIT 1""".trimIndent(), mapOf("id" to sessionId))

    private fun dto(row: Map<String, Any?>): Map<String, Any?> {
        val id = row.getValue("id").toString()
        val captures = store.rows("SELECT id,status,started_at,ended_at FROM proctoring_captures WHERE session_id=:session ORDER BY started_at,id", mapOf("session" to id)).map { capture ->
            val captureId = capture.getValue("id").toString()
            val counters = CHANNELS.associateWith { channel ->
                val values = store.one("""SELECT coalesce(sum(byte_count),0) AS bytes,count(*) AS chunks,coalesce(max(sequence),-1) AS last
                    FROM proctoring_media_receipts WHERE capture_id=:captureId AND channel=:channel""".trimIndent(), mapOf("captureId" to captureId, "channel" to channel))!!
                val chunks = (values.getValue("chunks") as Number).toInt()
                mapOf("bytes" to (values.getValue("bytes") as Number).toLong(), "chunks" to chunks,
                    "next_sequence" to (values.getValue("last") as Number).toInt() + 1)
            }
            mapOf("capture_id" to captureId, "status" to capture.getValue("status"), "started_at" to capture.getValue("started_at").toString(),
                "ended_at" to capture["ended_at"]?.toString(), "media" to counters)
        }
        val decision = if (row["decision_outcome"] == null) null else mapOf("outcome" to row["decision_outcome"], "reason_code" to row["decision_reason_code"],
            "note" to row["decision_note"], "decided_at" to row["decided_at"]?.toString())
        return mapOf("session_id" to id, "attempt_id" to row.getValue("attempt_id").toString(), "version" to row["version"],
            "status" to row["status"], "consent_version" to row["consent_version"],
            "consent_document_sha256" to row["consent_document_sha256"].toString().trim(),
            "consent_accepted_at" to row["consent_accepted_at"].toString(),
            "retention_until" to row["retention_until"].toString(), "attempt_version" to row["attempt_version"],
            "active_capture_id" to row["active_capture_id"]?.toString(), "captures" to captures,
            "events_count" to row["events_count"], "decision" to decision)
    }

    private fun requireReadable(row: Map<String, Any?>) {
        if (row["status"] in setOf("revoked", "expired") || !store.now().isBefore(row.getValue("retention_until") as Instant)) {
            throw ApiException(404, "NOT_FOUND", "Recording session was not found")
        }
        requireCurrentProcessing(row.getValue("user_id").toString())
    }

    private fun requireWritable(row: Map<String, Any?>, expected: Int) {
        if ((row["version"] as Number).toInt() != expected) versionConflict((row["version"] as Number).toInt())
        if (row["status"] !in setOf("recording", "sealed")) throw ApiException(409, "INVALID_STATE", "Recording session is not editable")
        if (!store.now().isBefore(row.getValue("retention_until") as Instant)) throw ApiException(409, "PROCTORING_SESSION_EXPIRED", "Recording session expired")
    }

    private fun requireCurrentProcessing(userId: String) {
        val processingVersion = consents.currentVersion("processing")
        val accepted = store.one("SELECT action FROM consent_events WHERE actor_user_id=:user AND scope='processing' AND version=:version ORDER BY sequence DESC LIMIT 1",
            mapOf("user" to userId, "version" to processingVersion))?.get("action") == "accept"
        if (!accepted) throw ApiException(403, "CONSENT_REQUIRED", "Accept current processing consent to access this recording")
    }

    private fun requireReviewer(userId: String) {
        val active = store.one("""SELECT g.active,u.email_verified_at FROM operations_grants g JOIN users u ON u.id=g.user_id
            WHERE g.user_id=:user AND g.permission='proctoring_review' FOR SHARE OF g,u""".trimIndent(), mapOf("user" to userId))
            ?.let { it["active"] == true && it["email_verified_at"] != null } == true
        if (!active) throw ApiException(403, "FORBIDDEN", "Proctoring review permission required")
    }

    private fun requireAvailable() {
        if (!media.available) throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Private proctoring is unavailable", retryable = true)
    }

    private fun parseRange(header: String?, size: Long): Pair<Long, Long> {
        if (header == null) return 0L to (size - 1)
        val match = Regex("^bytes=(\\d*)-(\\d*)$").matchEntire(header.trim()) ?: throw ApiException(416, "INVALID_RANGE", "Only a single byte range is supported")
        if (match.groupValues[1].isEmpty() && match.groupValues[2].isEmpty()) throw ApiException(416, "INVALID_RANGE", "Invalid media range")
        val suffix = match.groupValues[1].isEmpty()
        val start = if (suffix) (size - (match.groupValues[2].toLongOrNull() ?: 0)).coerceAtLeast(0) else match.groupValues[1].toLongOrNull() ?: throw ApiException(416, "INVALID_RANGE", "Invalid media range")
        val end = if (suffix) size - 1 else match.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: size - 1
        if (size <= 0 || start >= size || end < start) throw ApiException(416, "INVALID_RANGE", "Media range is outside the recording")
        return start to minOf(end, size - 1)
    }

    private fun decodeCursor(cursor: String): Pair<Instant, String> = try {
        val decoded = String(java.util.Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).split('|', limit = 2)
        if (decoded.size != 2) invalid("cursor")
        Instant.parse(decoded[0]) to uuid(decoded[1], "cursor")
    } catch (_: Exception) { invalid("cursor") }

    private fun encodeCursor(time: String, id: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("$time|$id".toByteArray())
    private fun Any?.asMap(): Map<String, Any?> = (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()

    private fun receipt(sessionId: String, captureId: String, channel: String, sequence: Int, digest: String, bytes: Int) = mapOf(
        "session_id" to sessionId, "capture_id" to captureId, "channel" to channel, "sequence" to sequence, "sha256" to digest, "bytes" to bytes)
    private fun lockUser(userId: String) { store.one("SELECT id FROM users WHERE id=:user FOR UPDATE", mapOf("user" to userId)) ?: notFound() }
    private fun lockAttemptForSession(userId: String, sessionId: String) {
        val attemptId = store.one("SELECT attempt_id FROM proctoring_sessions WHERE id=:id AND user_id=:user",
            mapOf("id" to sessionId, "user" to userId))?.get("attempt_id")?.toString() ?: notFound()
        store.one("SELECT id FROM attempts WHERE id=:attempt AND user_id=:user FOR UPDATE", mapOf("attempt" to attemptId, "user" to userId)) ?: notFound()
    }
    private fun audit(sessionId: String, actor: String?, action: String, outcome: String, metadata: Any = emptyMap<String, Any>()) {
        store.exec("INSERT INTO proctoring_audit_events(id,session_id,actor_user_id,action,outcome,metadata) VALUES(:id,:session,:actor,:action,:outcome,:metadata)",
            mapOf("id" to store.id(), "session" to sessionId, "actor" to actor, "action" to action, "outcome" to outcome, "metadata" to store.json(metadata)))
    }
    private fun afterCommit(action: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        }) else action()
    }
    private fun uuid(raw: String, field: String): String = try { UUID.fromString(raw).toString().also { if (it != raw) invalid(field) } } catch (_: Exception) { invalid(field) }
    private fun invalid(field: String): Nothing = throw ApiException(400, "VALIDATION_ERROR", "Invalid $field", field)
    private fun notFound(): Nothing = throw ApiException(404, "NOT_FOUND", "Recording session was not found")
    private fun versionConflict(current: Int): Nothing = throw ApiException(409, "VERSION_CONFLICT", "Reload the current recording state", currentVersion = current)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun constantTimeEquals(left: String, right: String) = MessageDigest.isEqual(left.lowercase().toByteArray(), right.lowercase().toByteArray())

    companion object {
        const val CONSENT_VERSION = "1.0"
        const val ABSOLUTE_MAX_CHUNK_BYTES = 4 * 1024 * 1024
        const val ABSOLUTE_MAX_SESSION_BYTES = 256L * 1024 * 1024
        const val MAX_EVENTS = 2000
        const val MAX_EVENTS_PER_REQUEST = 500
        const val MAX_CAPTURES = 100
        const val MAX_CHUNK_SEQUENCE = 2000
        const val MAX_IN_FLIGHT_UPLOADS = 8
        const val EVENT_CLOCK_TOLERANCE_SECONDS = 60L
        val CHANNELS = listOf("camera", "screen")
        val EVENT_TYPES = setOf("tab_hidden", "window_blur", "fullscreen_exit", "camera_disabled", "screen_share_stopped")
        const val CONSENT_TEXT_TEMPLATE = "Я добровольно разрешаю записывать видео с камеры и выбранного окна или вкладки браузера во время этой попытки без аудио. Записываются только материалы этой попытки. Запись хранится не дольше %d дней; согласие можно отозвать, после чего новые чтения будут закрыты, а файлы удалены. События браузера являются неподтверждёнными сигналами и сами по себе не являются доказательством нарушения. Этот текст — проект для оператора платформы и требует утверждения до использования с реальными кандидатами."
    }
}

data class StagedChunk(val path: Path, val sha256: String, val byteCount: Int)
data class MediaOrphanCandidate(val sessionId: String, val captureId: String? = null, val channel: String? = null,
                                val sequence: Int? = null, val stagedName: String? = null)
data class MediaPayload(
    val actorId: String,
    val reviewer: Boolean,
    val sessionId: String,
    val captureId: String,
    val channel: String,
    val paths: List<Pair<Path, Long>>,
    val totalBytes: Long,
    val start: Long,
    val end: Long,
    val partial: Boolean,
) { val contentLength: Long get() = end - start + 1 }

@Service
class PrivateMediaStore(
    @Value("\${fsp.proctoring.media-root:\${java.io.tmpdir}/fsp-proctoring}") private val configuredRoot: String,
    @Value("\${fsp.public-origin:http://localhost:5173}") private val publicOrigin: String,
    @Value("\${fsp.proctoring.enabled:false}") val enabled: Boolean,
    @Value("\${fsp.proctoring.retention-days:7}") val retentionDays: Int,
    @Value("\${fsp.proctoring.max-chunk-bytes:4194304}") private val configuredMaxChunkBytes: Int,
    @Value("\${fsp.proctoring.max-session-bytes:268435456}") private val configuredMaxSessionBytes: Long,
) {
    private val activeStagedPaths = ConcurrentHashMap.newKeySet<Path>()
    val maxChunkBytes: Int = configuredMaxChunkBytes.coerceAtMost(ProctoringService.ABSOLUTE_MAX_CHUNK_BYTES)
    val maxSessionBytes: Long = configuredMaxSessionBytes.coerceAtMost(ProctoringService.ABSOLUTE_MAX_SESSION_BYTES)
    val consentText: String get() = ProctoringService.CONSENT_TEXT_TEMPLATE.format(retentionDays)
    val consentHash: String get() = MessageDigest.getInstance("SHA-256").digest(consentText.toByteArray()).joinToString("") { "%02x".format(it) }
    private val root: Path by lazy { Path.of(configuredRoot).toAbsolutePath().normalize() }
    fun unavailableReason(available: Boolean): String? = when {
        available -> null
        !enabled -> "disabled"
        retentionDays !in 1..30 || configuredMaxChunkBytes != maxChunkBytes || configuredMaxSessionBytes != maxSessionBytes || maxSessionBytes < maxChunkBytes -> "invalid_configuration"
        !secureOrigin() -> "https_required"
        else -> "private_storage_unavailable"
    }
    val available: Boolean get() {
        return try {
            if (!enabled || retentionDays !in 1..30 || maxChunkBytes !in 1024..ProctoringService.ABSOLUTE_MAX_CHUNK_BYTES ||
                configuredMaxChunkBytes != maxChunkBytes || configuredMaxSessionBytes != maxSessionBytes ||
                maxSessionBytes !in maxChunkBytes.toLong()..ProctoringService.ABSOLUTE_MAX_SESSION_BYTES || !secureOrigin()) return false
            Files.createDirectories(root)
            if (Files.isSymbolicLink(root)) return false
            val canonicalRoot = root.toRealPath()
            val forbiddenRoots = listOf("frontend/dist", "frontend/public", "backend/src/main/resources/static")
                .map { Path.of(it).toAbsolutePath().normalize() }
            val repository = Path.of("").toAbsolutePath().normalize()
            if (forbiddenRoots.any { canonicalRoot.startsWith(it) || it.startsWith(canonicalRoot) } || canonicalRoot.startsWith(repository) || repository.startsWith(canonicalRoot)) return false
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
            val probe = Files.createTempFile(root, ".probe-", ".tmp")
            Files.delete(probe)
            true
        } catch (_: Exception) { false }
    }

    private fun secureOrigin(): Boolean = runCatching {
        val origin = java.net.URI(publicOrigin)
        val host = origin.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return@runCatching false
        val ipv4 = host.split('.').mapNotNull { it.toIntOrNull() }
        val loopback = host == "localhost" || host == "::1" || (ipv4.size == 4 && ipv4[0] == 127 && ipv4.all { it in 0..255 })
        origin.isAbsolute && origin.rawUserInfo == null && (origin.rawPath.isNullOrEmpty() || origin.rawPath == "/") &&
            origin.rawQuery == null && origin.rawFragment == null &&
            (origin.scheme.equals("https", ignoreCase = true) || loopback && origin.scheme.equals("http", ignoreCase = true))
    }.getOrDefault(false)

    fun stage(session: String, capture: String, channel: String, sequence: Int, input: InputStream): StagedChunk {
        val directory = directory(session, capture)
        rejectSymlinkPath(directory)
        privateDirectories(directory)
        val stageDir = directory.resolve("staging")
        privateDirectories(stageDir)
        val file = stageDir.resolve("${UUID.randomUUID()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            activeStagedPaths.add(file)
            Files.newOutputStream(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    if (count > maxChunkBytes) throw ApiException(413, "PROCTORING_CHUNK_TOO_LARGE", "Each recording chunk exceeds the configured limit")
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
            if (count == 0) throw ApiException(400, "VALIDATION_ERROR", "Recording chunk is empty")
            return StagedChunk(file, digest.digest().joinToString("") { "%02x".format(it) }, count)
        } catch (error: Exception) {
            activeStagedPaths.remove(file)
            Files.deleteIfExists(file)
            throw error
        }
    }

    fun commit(staged: StagedChunk, session: String, capture: String, channel: String, sequence: Int): Path {
        val target = chunkPath(session, capture, channel, sequence)
        rejectSymlinkPath(target.parent)
        privateDirectories(target.parent)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw ApiException(409, "CHUNK_CONFLICT", "Chunk already exists")
        try { Files.move(staged.path, target, StandardCopyOption.ATOMIC_MOVE); activeStagedPaths.remove(staged.path); return target }
        catch (_: java.nio.file.FileAlreadyExistsException) { throw ApiException(409, "CHUNK_CONFLICT", "Chunk already exists") }
        catch (error: java.nio.file.AtomicMoveNotSupportedException) { throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Private storage does not support atomic writes", retryable = true) }
    }

    fun delete(path: Path) {
        activeStagedPaths.remove(path)
        try { Files.deleteIfExists(path) } catch (_: Exception) { /* scheduled cleanup retries */ }
    }
    fun deleteStaged(session: String, capture: String, stagedName: String): Boolean {
        if (!stagedName.isCanonicalUuid()) return false
        val path = directory(session, capture).resolve("staging").resolve("$stagedName.tmp").normalize()
        if (!path.startsWith(root) || path in activeStagedPaths) return false
        return try { Files.deleteIfExists(path) } catch (_: Exception) { false }
    }
    fun size(path: Path): Long {
        rejectSymlinkPath(path)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Private recording file is unavailable", retryable = true)
        }
        return Files.size(path)
    }
    fun open(path: Path): InputStream {
        size(path)
        return Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    }
    fun findOrphanCandidates(olderThan: Instant): List<MediaOrphanCandidate> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return emptyList()
        val candidates = mutableListOf<MediaOrphanCandidate>()
        try {
            Files.list(root).use { entries -> entries.forEach { sessionPath ->
                if (Files.isSymbolicLink(sessionPath) || !Files.isDirectory(sessionPath, LinkOption.NOFOLLOW_LINKS)) return@forEach
                val sessionId = sessionPath.fileName.toString()
                if (!sessionId.isCanonicalUuid()) return@forEach
                if (Files.getLastModifiedTime(sessionPath, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(olderThan)) {
                    candidates += MediaOrphanCandidate(sessionId)
                }
                Files.walk(sessionPath).use { paths -> paths.filter {
                    val name = it.fileName.toString()
                    Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) &&
                        (name.endsWith(".webm") || name.endsWith(".tmp") && it !in activeStagedPaths) &&
                        Files.getLastModifiedTime(it, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(olderThan)
                }.forEach { file ->
                    val relative = sessionPath.relativize(file)
                    if (relative.nameCount != 3) return@forEach
                    val captureId = relative.getName(0).toString()
                    val isStagingFile = relative.getName(1).toString() == "staging" && relative.getName(2).fileName.toString().endsWith(".tmp")
                    if (captureId.isCanonicalUuid() && isStagingFile) {
                        val stagedName = relative.getName(2).fileName.toString().removeSuffix(".tmp")
                        if (stagedName.isCanonicalUuid()) candidates += MediaOrphanCandidate(sessionId, captureId, stagedName = stagedName)
                    } else {
                        val channel = relative.getName(1).toString()
                        val sequence = relative.getName(2).fileName.toString().removeSuffix(".webm").toIntOrNull()
                        if (captureId.isCanonicalUuid() && channel in ProctoringService.CHANNELS && sequence != null && sequence in 0..ProctoringService.MAX_CHUNK_SEQUENCE) {
                            candidates += MediaOrphanCandidate(sessionId, captureId, channel, sequence)
                        }
                    }
                } }
            } }
        } catch (_: Exception) { /* next scheduled pass retries */ }
        return candidates.distinct()
    }
    fun deleteSession(session: String) {
        val path = root.resolve(uuid(session)).normalize()
        if (!path.startsWith(root) || Files.isSymbolicLink(path)) return
        activeStagedPaths.removeIf { it.startsWith(path) }
        try { if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.walk(path).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } catch (_: Exception) { }
    }
    private fun directory(session: String, capture: String): Path = root.resolve(uuid(session)).resolve(uuid(capture)).normalize().also {
        if (!it.startsWith(root)) throw ApiException(400, "VALIDATION_ERROR", "Invalid recording path")
    }
    fun chunkPath(session: String, capture: String, channel: String, sequence: Int): Path = directory(session, capture).resolve(channel).resolve("$sequence.webm").normalize().also {
        if (!it.startsWith(root) || channel !in ProctoringService.CHANNELS || sequence < 0) throw ApiException(400, "VALIDATION_ERROR", "Invalid recording path")
    }
    private fun rejectSymlinkPath(path: Path) {
        var at: Path? = path
        while (at != null && at.startsWith(root)) {
            if (Files.isSymbolicLink(at)) throw ApiException(503, "DEPENDENCY_UNAVAILABLE", "Private storage path is unsafe")
            if (at == root) break
            at = at.parent
        }
    }
    private fun privateDirectories(path: Path) {
        rejectSymlinkPath(path)
        Files.createDirectories(path)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        rejectSymlinkPath(path)
    }
    private fun uuid(raw: String) = try { UUID.fromString(raw).toString().also { if (it != raw) throw IllegalArgumentException() } } catch (_: Exception) { throw ApiException(400, "VALIDATION_ERROR", "Invalid recording path") }
    private fun String.isCanonicalUuid(): Boolean = try { UUID.fromString(this).toString() == this } catch (_: Exception) { false }
}

/** Keeps ConsentService's policy constant out of the filesystem component. */
