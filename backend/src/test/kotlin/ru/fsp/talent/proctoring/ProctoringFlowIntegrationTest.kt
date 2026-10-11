package ru.fsp.talent.proctoring

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.beans.factory.annotation.Autowired
import com.fasterxml.jackson.databind.JsonNode
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.SessionSupport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.InputStream
import java.io.ByteArrayInputStream

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["fsp.proctoring.enabled=true"])
class ProctoringFlowIntegrationTest : DbTestBase() {
    @Autowired
    private lateinit var contracts: Contracts

    @Autowired
    private lateinit var proctoring: ProctoringService

    @Autowired
    private lateinit var privateMedia: PrivateMediaStore

    @Test
    fun `sealed recording withholds assessment until upheld and decision replay does not score twice`() {
        val candidate = seedActor(defaultConsents = false)
        jdbc.update("INSERT INTO consent_events(id,actor_user_id,scope,version,action) VALUES(?::uuid,?::uuid,'processing','1.0','accept')",
            UUID.randomUUID().toString(), candidate.id)
        val attemptResponse = localRequest(candidate, HttpMethod.POST, "/api/v1/attempts", mapOf(
            "specialization_id" to "backend-python-v1", "requested_grade" to "junior",
            "policy_id" to "backend-python-policy-v1", "policy_version" to "1.0.0"), UUID.randomUUID().toString())
        assertEquals(201, attemptResponse.statusCode.value(), attemptResponse.body.toString())
        val attempt = attemptResponse.body!!.path("data")
        val attemptId = attempt.path("id").asText()

        val config = localRequest(candidate, HttpMethod.GET, "/api/v1/proctoring/config").body!!.path("data")
        contracts.validate("ProctoringConfiguration", objectMapper.convertValue(config, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}))
        assertTrue(config.path("available").asBoolean())
        val createBody = mapOf("consent_version" to config.path("consent_version").asText(),
            "consent_document_sha256" to config.path("consent_document_sha256").asText())
        assertEquals(409, localRequest(candidate, HttpMethod.POST, "/api/v1/attempts/$attemptId/proctoring/session",
            createBody + ("consent_document_sha256" to "0".repeat(64)), UUID.randomUUID().toString()).statusCode.value())
        val createKey = UUID.randomUUID().toString()
        val sessionResponse = localRequest(candidate, HttpMethod.POST, "/api/v1/attempts/$attemptId/proctoring/session", createBody, createKey)
        assertEquals(201, sessionResponse.statusCode.value())
        val session = sessionResponse.body!!.path("data")
        validate("ProctoringSession", session)
        val sessionId = session.path("session_id").asText()
        val captureId = session.path("active_capture_id").asText()

        // Synthetic 4.4s Chromium MediaRecorder VP8 capture (timeslice chunks concatenated).
        val webm = javaClass.getResourceAsStream("/proctoring/mediarecorder-native.webm")!!.use { it.readBytes() }
        val recordingChunks = listOf(webm.copyOfRange(0, 19076), webm.copyOfRange(19076, 43695), webm.copyOfRange(43695, webm.size))
        val firstChunkDigest = sha256(recordingChunks.first())
        val foreignCandidate = seedActor(defaultConsents = false)
        jdbc.update("INSERT INTO consent_events(id,actor_user_id,scope,version,action) VALUES(?::uuid,?::uuid,'processing','1.0','accept')",
            UUID.randomUUID().toString(), foreignCandidate.id)
        val foreignAttempt = localRequest(foreignCandidate, HttpMethod.POST, "/api/v1/attempts", mapOf(
            "specialization_id" to "backend-python-v1", "requested_grade" to "junior",
            "policy_id" to "backend-python-policy-v1", "policy_version" to "1.0.0"), UUID.randomUUID().toString()).body!!.path("data")
        val foreignSession = localRequest(foreignCandidate, HttpMethod.POST, "/api/v1/attempts/${foreignAttempt.path("id").asText()}/proctoring/session",
            createBody, UUID.randomUUID().toString()).body!!.path("data")
        val unreadForeignCapture = CountingInputStream(webm)
        val foreignCaptureError = assertThrows(ApiException::class.java) {
            proctoring.acceptChunk(candidate.id, sessionId, foreignSession.path("active_capture_id").asText(), "camera", 0,
                UUID.randomUUID().toString(), firstChunkDigest, unreadForeignCapture)
        }
        assertEquals(404, foreignCaptureError.status)
        assertEquals(0, unreadForeignCapture.readCount)
        val oversized = ByteArray(config.path("max_chunk_bytes").asInt() + 1)
        val tooLarge = assertThrows(ApiException::class.java) {
            proctoring.acceptChunk(candidate.id, sessionId, captureId, "camera", 99, UUID.randomUUID().toString(), sha256(oversized), ByteArrayInputStream(oversized))
        }
        assertEquals(413, tooLarge.status)
        assertEquals(400, upload(candidate, sessionId, captureId, 0, "0".repeat(64), recordingChunks.first()).statusCode.value())
        val upload = upload(candidate, sessionId, captureId, 0, firstChunkDigest, recordingChunks.first())
        assertEquals(200, upload.statusCode.value())
        validate("ProctoringChunkReceipt", upload.body!!.path("data"))
        assertEquals(firstChunkDigest, upload.body!!.path("data").path("sha256").asText())
        recordingChunks.drop(1).forEachIndexed { index, chunk ->
            assertEquals(200, upload(candidate, sessionId, captureId, index + 1, sha256(chunk), chunk).statusCode.value())
        }

        val afterCamera = localRequest(candidate, HttpMethod.GET, "/api/v1/attempts/$attemptId/proctoring/session").body!!.path("data")
        val incompleteSeal = localRequest(candidate, HttpMethod.POST, "/api/v1/proctoring/sessions/$sessionId/seal", mapOf(
            "expected_version" to afterCamera.path("version").asInt(), "capture_id" to captureId,
            "last_sequences" to mapOf("camera" to 2, "screen" to 0)), UUID.randomUUID().toString())
        assertEquals(409, incompleteSeal.statusCode.value(), incompleteSeal.body.toString())
        val screen = "synthetic-screen-segment".toByteArray()
        assertEquals(200, upload(candidate, sessionId, captureId, 0, sha256(screen), screen, channel = "screen").statusCode.value())
        jdbc.update("UPDATE attempts SET started_at=now()-interval '32 minutes' WHERE id=?::uuid", attemptId)
        jdbc.update("UPDATE proctoring_sessions SET consent_accepted_at=now()-interval '31 minutes' WHERE id=?::uuid", sessionId)
        val validEvent = mapOf("event_id" to UUID.randomUUID().toString(), "type" to "tab_hidden", "occurred_at" to Instant.now().minusSeconds(30 * 60).toString())
        assertEquals(200, localRequest(candidate, HttpMethod.POST, "/api/v1/proctoring/sessions/$sessionId/events",
            mapOf("expected_version" to localRequest(candidate, HttpMethod.GET, "/api/v1/attempts/$attemptId/proctoring/session").body!!.path("data").path("version").asInt(),
                "events" to listOf(validEvent)), UUID.randomUUID().toString()).statusCode.value())
        val invalidEvent = validEvent + ("event_id" to UUID.randomUUID().toString()) + ("type" to "paste_detected")
        assertEquals(400, localRequest(candidate, HttpMethod.POST, "/api/v1/proctoring/sessions/$sessionId/events",
            mapOf("expected_version" to localRequest(candidate, HttpMethod.GET, "/api/v1/attempts/$attemptId/proctoring/session").body!!.path("data").path("version").asInt(),
                "events" to listOf(invalidEvent)), UUID.randomUUID().toString()).statusCode.value())

        val sessionNow = localRequest(candidate, HttpMethod.GET, "/api/v1/attempts/$attemptId/proctoring/session").body!!.path("data")
        val seal = localRequest(candidate, HttpMethod.POST, "/api/v1/proctoring/sessions/$sessionId/seal", mapOf(
            "expected_version" to sessionNow.path("version").asInt(), "capture_id" to captureId,
            "last_sequences" to mapOf("camera" to 2, "screen" to 0)), UUID.randomUUID().toString())
        assertEquals(200, seal.statusCode.value())
        assertEquals(200, upload(candidate, sessionId, captureId, 0, firstChunkDigest, recordingChunks.first()).statusCode.value())

        val mediaPath = "/api/v1/proctoring/sessions/$sessionId/media/camera?capture_id=$captureId"
        val indexedMedia = downloadAll(candidate, mediaPath)
        assertEquals(200, indexedMedia.statusCode.value())
        assertTrue(indexedMedia.body!!.size > webm.size, "seekable representation should prepend finite-duration metadata and cues")
        val outputFirstCluster = assertSeekableRepresentation(indexedMedia.body!!)
        val media = download(candidate, mediaPath, "bytes=2-7")
        assertEquals(206, media.statusCode.value())
        assertArrayEquals(indexedMedia.body!!.copyOfRange(2, 8), media.body)
        assertEquals("bytes 2-7/${indexedMedia.body!!.size}", media.headers.getFirst(HttpHeaders.CONTENT_RANGE))
        assertEquals("private, no-store", media.headers.getFirst(HttpHeaders.CACHE_CONTROL))
        assertEquals("no", media.headers.getFirst("X-Accel-Buffering"))
        val originalFirstCluster = webm.indexOfId(byteArrayOf(0x1f, 0x43, 0xb6.toByte(), 0x75))
        val sourceBoundary = outputFirstCluster + (recordingChunks.first().size - originalFirstCluster)
        val crossReceiptRange = download(candidate, mediaPath, "bytes=${sourceBoundary - 8}-${sourceBoundary + 8}")
        assertArrayEquals(indexedMedia.body!!.copyOfRange(sourceBoundary - 8, sourceBoundary + 9), crossReceiptRange.body)
        val suffixRange = download(candidate, mediaPath, "bytes=${indexedMedia.body!!.size - 8}-${indexedMedia.body!!.size - 1}")
        assertArrayEquals(indexedMedia.body!!.copyOfRange(indexedMedia.body!!.size - 8, indexedMedia.body!!.size), suffixRange.body)

        val storedTasks = objectMapper.readTree(jdbc.queryForObject("SELECT tasks::text FROM attempts WHERE id=?::uuid", String::class.java, attemptId))
        val correctAnswers = storedTasks.path("answer_keys").map { key -> mapOf(
            "task_id" to key.path("task_id").asText(), "subquestion_id" to key.path("subquestion_id").asText(),
            "selected_option_id" to key.path("correct_option_id").asText()) }
        val answerWrite = localRequest(candidate, HttpMethod.PUT, "/api/v1/attempts/$attemptId/answers",
            mapOf("expected_version" to attempt.path("version").asInt(), "answers" to correctAnswers), UUID.randomUUID().toString())
        assertEquals(200, answerWrite.statusCode.value())
        val submission = localRequest(candidate, HttpMethod.POST, "/api/v1/attempts/$attemptId/submit",
            mapOf("expected_version" to answerWrite.body!!.path("data").path("version").asInt()), UUID.randomUUID().toString())
        assertEquals(200, submission.statusCode.value())
        assertEquals("submitted", submission.body!!.path("data").path("status").asText())
        assertTrue(submission.body!!.path("data").path("result").isNull)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM qualifications WHERE user_id=?::uuid", Int::class.java, candidate.id))
        assertEquals("none", localRequest(candidate, HttpMethod.GET, "/api/v1/attempts/$attemptId").body!!.path("data").path("next_action").asText())
        val lateEdit = localRequest(candidate, HttpMethod.PUT, "/api/v1/attempts/$attemptId/answers",
            mapOf("expected_version" to submission.body!!.path("data").path("version").asInt(), "answers" to correctAnswers), UUID.randomUUID().toString())
        assertEquals(409, lateEdit.statusCode.value())

        val reviewer = seedActor(defaultConsents = false)
        jdbc.update("INSERT INTO operations_grants(user_id,permission,granted_by) VALUES(?::uuid,'proctoring_review',?::uuid)", reviewer.id, reviewer.id)
        var queueCursor: String? = null
        var foundInQueue = false
        var queuePages = 0
        while (!foundInQueue && queuePages < 100) {
            val cursorQuery = queueCursor?.let { "&cursor=$it" }.orEmpty()
            val queue = localRequest(reviewer, HttpMethod.GET, "/api/v1/operations/proctoring/reviews?limit=100$cursorQuery")
            assertEquals(200, queue.statusCode.value())
            val queueData = queue.body!!.path("data")
            validate("ProctoringReviewQueue", queueData)
            foundInQueue = queueData.path("items").any { it.path("session_id").asText() == sessionId }
            queueCursor = queueData.path("next_cursor").takeIf { !it.isNull }?.asText()
            queuePages++
            if (queueCursor == null) break
        }
        assertTrue(foundInQueue, "review queue must contain this fixture across its cursor pages")
        val unauthorizedEmployer = seedActor(role = "employer", defaultConsents = false)
        assertEquals(403, localRequest(unauthorizedEmployer, HttpMethod.GET, "/api/v1/operations/proctoring/reviews").statusCode.value())
        assertEquals(403, download(unauthorizedEmployer, "/api/v1/proctoring/sessions/$sessionId/media/camera?capture_id=$captureId", "bytes=0-1").statusCode.value())
        jdbc.update("INSERT INTO operations_grants(user_id,permission,granted_by) VALUES(?::uuid,'proctoring_review',?::uuid)", candidate.id, candidate.id)
        assertEquals(404, localRequest(candidate, HttpMethod.GET, "/api/v1/operations/proctoring/reviews/$sessionId").statusCode.value())
        val detail = localRequest(reviewer, HttpMethod.GET, "/api/v1/operations/proctoring/reviews/$sessionId")
        assertEquals(200, detail.statusCode.value())
        validate("ProctoringReview", detail.body!!.path("data"))
        assertFalse(detail.body!!.path("data").toString().contains("answer_keys"))
        assertFalse(detail.body!!.path("data").toString().contains("correct_option_id"))
        val reviewerMedia = download(reviewer, "/api/v1/proctoring/sessions/$sessionId/media/camera?capture_id=$captureId", "bytes=0-3")
        assertEquals(206, reviewerMedia.statusCode.value(), String(reviewerMedia.body ?: byteArrayOf()))
        assertEquals("no", reviewerMedia.headers.getFirst("X-Accel-Buffering"))

        val decisionBody = mapOf("expected_version" to detail.body!!.path("data").path("version").asInt(),
            "expected_attempt_version" to detail.body!!.path("data").path("attempt_version").asInt(),
            "outcome" to "uphold", "reason_code" to "approved", "note" to "Synthetic integration review")
        val decisionKey = UUID.randomUUID().toString()
        val decisionPath = "/api/v1/operations/proctoring/reviews/$sessionId/decision"
        val decision = localRequest(reviewer, HttpMethod.POST, decisionPath, decisionBody, decisionKey)
        assertEquals(200, decision.statusCode.value())
        validate("ProctoringSession", decision.body!!.path("data"))
        assertEquals("approved", decision.body!!.path("data").path("status").asText())
        assertEquals("evaluated", localRequest(candidate, HttpMethod.GET, "/api/v1/attempts/$attemptId").body!!.path("data").path("status").asText())
        assertEquals("junior", jdbc.queryForObject("SELECT grade FROM qualifications WHERE user_id=?::uuid", String::class.java, candidate.id))
        val versionAfterDecision = jdbc.queryForObject("SELECT version FROM attempts WHERE id=?::uuid", Int::class.java, attemptId)

        val replay = localRequest(reviewer, HttpMethod.POST, decisionPath, decisionBody, decisionKey)
        assertEquals(200, replay.statusCode.value())
        assertEquals("approved", replay.body!!.path("data").path("status").asText())
        assertEquals(versionAfterDecision, jdbc.queryForObject("SELECT version FROM attempts WHERE id=?::uuid", Int::class.java, attemptId))

        val revoke = localRequest(candidate, HttpMethod.POST, "/api/v1/me/consents", mapOf("scope" to "processing", "version" to "1.0", "action" to "revoke"), UUID.randomUUID().toString())
        assertEquals(200, revoke.statusCode.value())
        assertFalse(Files.exists(mediaRoot.resolve(sessionId)))
        assertEquals(404, download(candidate, mediaPath, "bytes=0-3").statusCode.value(), "consent revocation must deny a cached prefix range")
        assertEquals(403, localRequest(reviewer, HttpMethod.POST, decisionPath, decisionBody, decisionKey).statusCode.value())
        assertEquals(403, localRequest(candidate, HttpMethod.POST, "/api/v1/attempts/$attemptId/proctoring/session", createBody, createKey).statusCode.value())
        jdbc.update("UPDATE operations_grants SET active=false WHERE user_id=?::uuid AND permission='proctoring_review'", reviewer.id)
        assertEquals(403, download(reviewer, "/api/v1/proctoring/sessions/$sessionId/media/camera?capture_id=$captureId", "bytes=0-3").statusCode.value())
        assertEquals("evaluated", localRequest(candidate, HttpMethod.GET, "/api/v1/attempts/$attemptId").body!!.path("data").path("status").asText())
        assertEquals("junior", jdbc.queryForObject("SELECT grade FROM qualifications WHERE user_id=?::uuid", String::class.java, candidate.id))
    }

    @Test
    fun `processing consent revocation wins against an upload still staging bytes`() {
        val candidate = seedActor(defaultConsents = false)
        jdbc.update("INSERT INTO consent_events(id,actor_user_id,scope,version,action) VALUES(?::uuid,?::uuid,'processing','1.0','accept')",
            UUID.randomUUID().toString(), candidate.id)
        jdbc.update("""INSERT INTO qualifications(id,user_id,specialization_id,grade,last_changed_at,data)
            VALUES(?::uuid,?::uuid,'backend-python-v1','junior',now()-interval '40 days','{}'::jsonb)""",
            UUID.randomUUID().toString(), candidate.id)
        val attempt = localRequest(candidate, HttpMethod.POST, "/api/v1/attempts", mapOf(
            "specialization_id" to "backend-python-v1", "requested_grade" to "middle",
            "policy_id" to "backend-python-policy-v1", "policy_version" to "1.0.0"), UUID.randomUUID().toString()).body!!.path("data")
        val attemptId = attempt.path("id").asText()
        val config = localRequest(candidate, HttpMethod.GET, "/api/v1/proctoring/config").body!!.path("data")
        val created = localRequest(candidate, HttpMethod.POST, "/api/v1/attempts/$attemptId/proctoring/session", mapOf(
            "consent_version" to config.path("consent_version").asText(),
            "consent_document_sha256" to config.path("consent_document_sha256").asText()), UUID.randomUUID().toString())
        assertEquals(201, created.statusCode.value())
        val sessionId = created.body!!.path("data").path("session_id").asText()
        val captureId = created.body!!.path("data").path("active_capture_id").asText()

        val orphanChunk = privateMedia.chunkPath(sessionId, captureId, "camera", 0)
        Files.createDirectories(orphanChunk.parent)
        Files.write(orphanChunk, "unreceipted crash artifact".toByteArray())
        val oldTime = FileTime.from(Instant.now().minusSeconds(2 * 3600))
        Files.setLastModifiedTime(mediaRoot.resolve(sessionId), oldTime)
        Files.setLastModifiedTime(orphanChunk, oldTime)
        proctoring.expireSessionsAndCleanOrphans()
        assertTrue(Files.isDirectory(mediaRoot.resolve(sessionId)), "the DB-backed active session must survive a stale filesystem scan")
        assertFalse(Files.exists(orphanChunk), "a stale file with no committed receipt must be removed")

        val readStarted = CountDownLatch(1)
        val allowReadToFinish = CountDownLatch(1)
        val payload = ByteArray(128 * 1024) { (it % 251).toByte() }
        val uploader = Executors.newSingleThreadExecutor()
        try {
            val upload = uploader.submit<Boolean> {
                try {
                    proctoring.acceptChunk(candidate.id, sessionId, captureId, "camera", 0, UUID.randomUUID().toString(), sha256(payload),
                        BlockingInputStream(payload, readStarted, allowReadToFinish))
                    false
                } catch (error: ApiException) {
                    error.code == "CONSENT_REQUIRED"
                }
            }
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))
            val revoke = localRequest(candidate, HttpMethod.POST, "/api/v1/me/consents",
                mapOf("scope" to "processing", "version" to "1.0", "action" to "revoke"), UUID.randomUUID().toString())
            assertEquals(200, revoke.statusCode.value())
            allowReadToFinish.countDown()
            assertTrue(upload.get(10, TimeUnit.SECONDS))
        } finally {
            allowReadToFinish.countDown()
            uploader.shutdownNow()
        }
        assertFalse(Files.exists(mediaRoot.resolve(sessionId)))
        assertEquals(404, localRequest(candidate, HttpMethod.GET, "/api/v1/attempts/$attemptId/proctoring/session").statusCode.value())
        assertEquals("invalidated", localRequest(candidate, HttpMethod.GET, "/api/v1/attempts/$attemptId").body!!.path("data").path("status").asText())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM proctoring_media_receipts WHERE session_id=?::uuid", Int::class.java, sessionId))
        assertEquals("junior", jdbc.queryForObject("SELECT grade FROM qualifications WHERE user_id=?::uuid", String::class.java, candidate.id))
    }

    @Test
    fun `in flight media staging is bounded and rejected upload body is not read`() {
        val candidate = seedActor(defaultConsents = false)
        jdbc.update("INSERT INTO consent_events(id,actor_user_id,scope,version,action) VALUES(?::uuid,?::uuid,'processing','1.0','accept')",
            UUID.randomUUID().toString(), candidate.id)
        val attemptResponse = localRequest(candidate, HttpMethod.POST, "/api/v1/attempts", mapOf(
            "specialization_id" to "backend-python-v1", "requested_grade" to "junior",
            "policy_id" to "backend-python-policy-v1", "policy_version" to "1.0.0"), UUID.randomUUID().toString())
        assertEquals(201, attemptResponse.statusCode.value(), attemptResponse.body.toString())
        val attempt = attemptResponse.body!!.path("data")
        val attemptId = attempt.path("id").asText()
        val config = localRequest(candidate, HttpMethod.GET, "/api/v1/proctoring/config").body!!.path("data")
        val created = localRequest(candidate, HttpMethod.POST, "/api/v1/attempts/$attemptId/proctoring/session", mapOf(
            "consent_version" to config.path("consent_version").asText(),
            "consent_document_sha256" to config.path("consent_document_sha256").asText()), UUID.randomUUID().toString())
        assertEquals(201, created.statusCode.value())
        val sessionId = created.body!!.path("data").path("session_id").asText()
        val captureId = created.body!!.path("data").path("active_capture_id").asText()

        val started = CountDownLatch(ProctoringService.MAX_IN_FLIGHT_UPLOADS)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(ProctoringService.MAX_IN_FLIGHT_UPLOADS)
        try {
            val uploads = (0 until ProctoringService.MAX_IN_FLIGHT_UPLOADS).map { sequence ->
                val bytes = byteArrayOf(sequence.toByte())
                executor.submit<Boolean> {
                    try {
                        proctoring.acceptChunk(candidate.id, sessionId, captureId, "camera", sequence,
                            UUID.randomUUID().toString(), sha256(bytes), BlockingInputStream(bytes, started, release))
                        true
                    } catch (_: Exception) { false }
                }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val stagingDirectory = mediaRoot.resolve(sessionId)
            val stagedFiles = Files.walk(stagingDirectory).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".tmp") }.toList()
            }
            assertEquals(ProctoringService.MAX_IN_FLIGHT_UPLOADS, stagedFiles.size)
            val stale = FileTime.from(Instant.now().minusSeconds(2 * 3600))
            Files.setLastModifiedTime(stagingDirectory, stale)
            stagedFiles.forEach { Files.setLastModifiedTime(it, stale) }
            proctoring.expireSessionsAndCleanOrphans()
            assertTrue(Files.isDirectory(stagingDirectory), "cleanup must keep an active database session")
            assertTrue(stagedFiles.all { Files.exists(it) }, "cleanup must not delete in-process staged uploads")
            val unread = CountingInputStream(byteArrayOf(99))
            val saturated = assertThrows(ApiException::class.java) {
                proctoring.acceptChunk(candidate.id, sessionId, captureId, "camera", ProctoringService.MAX_IN_FLIGHT_UPLOADS,
                    UUID.randomUUID().toString(), sha256(byteArrayOf(99)), unread)
            }
            assertEquals(429, saturated.status)
            assertEquals("PROCTORING_UPLOAD_CAPACITY", saturated.code)
            assertTrue(saturated.retryable)
            assertEquals(0, unread.readCount)
            release.countDown()
            assertTrue(uploads.all { it.get(10, TimeUnit.SECONDS) })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
        assertEquals(ProctoringService.MAX_IN_FLIGHT_UPLOADS,
            jdbc.queryForObject("SELECT count(*) FROM proctoring_media_receipts WHERE session_id=?::uuid", Int::class.java, sessionId))
        assertEquals(200, localRequest(candidate, HttpMethod.POST, "/api/v1/me/consents",
            mapOf("scope" to "processing", "version" to "1.0", "action" to "revoke"), UUID.randomUUID().toString()).statusCode.value())
        assertFalse(Files.exists(mediaRoot.resolve(sessionId)))
    }

    @Test
    fun `manual invalidate preserves prior qualification and does not add cooldown`() {
        verifyNegativeDecision("invalidate", "unauthorized_assistance", "invalidated")
    }

    @Test
    fun `manual technical decision preserves prior qualification and does not add cooldown`() {
        verifyNegativeDecision("technical", "technical_failure", "technical_failure")
    }

    @Test
    fun `expired pending review is technically invalidated and prior qualification remains eligible`() {
        val fixture = createPendingMiddleFixture()
        jdbc.update("UPDATE proctoring_sessions SET retention_until=now()-interval '1 second' WHERE id=?::uuid", fixture.sessionId)
        proctoring.expireSessionsAndCleanOrphans()

        assertEquals("expired", jdbc.queryForObject("SELECT status FROM proctoring_sessions WHERE id=?::uuid", String::class.java, fixture.sessionId))
        assertEquals("invalidated", jdbc.queryForObject("SELECT status FROM attempts WHERE id=?::uuid", String::class.java, fixture.attemptId))
        assertEquals("junior", jdbc.queryForObject("SELECT grade FROM qualifications WHERE user_id=?::uuid", String::class.java, fixture.candidate.id))
        assertTrue(Files.notExists(mediaRoot.resolve(fixture.sessionId)))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM proctoring_audit_events WHERE session_id=?::uuid AND action='retention_expired'", Int::class.java, fixture.sessionId))
        assertEquals(201, createMiddleAttempt(fixture.candidate).statusCode.value())
    }

    private fun verifyNegativeDecision(outcome: String, reason: String, expectedSessionStatus: String) {
        val fixture = createPendingMiddleFixture()
        val previousChange = jdbc.queryForObject("SELECT last_changed_at FROM qualifications WHERE user_id=?::uuid",
            java.sql.Timestamp::class.java, fixture.candidate.id)!!.toInstant()
        val reviewer = seedActor(defaultConsents = false)
        jdbc.update("INSERT INTO operations_grants(user_id,permission,granted_by) VALUES(?::uuid,'proctoring_review',?::uuid)", reviewer.id, reviewer.id)
        val detail = localRequest(reviewer, HttpMethod.GET, "/api/v1/operations/proctoring/reviews/${fixture.sessionId}")
        assertEquals(200, detail.statusCode.value())
        validate("ProctoringReview", detail.body!!.path("data"))
        assertEquals("submitted", detail.body!!.path("data").path("attempt").path("status").asText())

        val decisionBody = mapOf("expected_version" to detail.body!!.path("data").path("version").asInt(),
            "expected_attempt_version" to detail.body!!.path("data").path("attempt_version").asInt(),
            "outcome" to outcome, "reason_code" to reason, "note" to "Synthetic negative decision regression")
        val decisionKey = UUID.randomUUID().toString()
        val path = "/api/v1/operations/proctoring/reviews/${fixture.sessionId}/decision"
        val decision = localRequest(reviewer, HttpMethod.POST, path, decisionBody, decisionKey)
        assertEquals(200, decision.statusCode.value())
        assertEquals(expectedSessionStatus, decision.body!!.path("data").path("status").asText())
        assertEquals(outcome, decision.body!!.path("data").path("decision").path("outcome").asText())
        assertEquals("invalidated", localRequest(fixture.candidate, HttpMethod.GET, "/api/v1/attempts/${fixture.attemptId}").body!!.path("data").path("status").asText())
        assertEquals("junior", jdbc.queryForObject("SELECT grade FROM qualifications WHERE user_id=?::uuid", String::class.java, fixture.candidate.id))
        assertEquals(previousChange, jdbc.queryForObject("SELECT last_changed_at FROM qualifications WHERE user_id=?::uuid",
            java.sql.Timestamp::class.java, fixture.candidate.id)!!.toInstant())

        val attemptVersion = jdbc.queryForObject("SELECT version FROM attempts WHERE id=?::uuid", Int::class.java, fixture.attemptId)
        val replay = localRequest(reviewer, HttpMethod.POST, path, decisionBody, decisionKey)
        assertEquals(200, replay.statusCode.value())
        assertEquals(attemptVersion, jdbc.queryForObject("SELECT version FROM attempts WHERE id=?::uuid", Int::class.java, fixture.attemptId))
        assertEquals(201, createMiddleAttempt(fixture.candidate).statusCode.value())
    }

    private fun createPendingMiddleFixture(): PendingMiddleFixture {
        val candidate = seedActor(defaultConsents = false)
        jdbc.update("INSERT INTO consent_events(id,actor_user_id,scope,version,action) VALUES(?::uuid,?::uuid,'processing','1.0','accept')",
            UUID.randomUUID().toString(), candidate.id)
        val lastChanged = Instant.now().minusSeconds(40L * 86400)
        jdbc.update("""INSERT INTO qualifications(id,user_id,specialization_id,grade,last_changed_at,data)
            VALUES(?::uuid,?::uuid,'backend-python-v1','junior',?,'{}'::jsonb)""",
            UUID.randomUUID().toString(), candidate.id, java.sql.Timestamp.from(lastChanged))
        val attempt = createMiddleAttempt(candidate)
        assertEquals(201, attempt.statusCode.value(), attempt.body.toString())
        val attemptId = attempt.body!!.path("data").path("id").asText()
        val config = localRequest(candidate, HttpMethod.GET, "/api/v1/proctoring/config").body!!.path("data")
        val created = localRequest(candidate, HttpMethod.POST, "/api/v1/attempts/$attemptId/proctoring/session", mapOf(
            "consent_version" to config.path("consent_version").asText(),
            "consent_document_sha256" to config.path("consent_document_sha256").asText()), UUID.randomUUID().toString())
        assertEquals(201, created.statusCode.value())
        val session = created.body!!.path("data")
        val sessionId = session.path("session_id").asText()
        val captureId = session.path("active_capture_id").asText()
        val cameraBytes = "negative-review-camera".toByteArray()
        val screenBytes = "negative-review-screen".toByteArray()
        assertEquals(200, upload(candidate, sessionId, captureId, 0, sha256(cameraBytes), cameraBytes).statusCode.value())
        assertEquals(200, upload(candidate, sessionId, captureId, 0, sha256(screenBytes), screenBytes, channel = "screen").statusCode.value())
        val beforeSeal = localRequest(candidate, HttpMethod.GET, "/api/v1/attempts/$attemptId/proctoring/session").body!!.path("data")
        val sealed = localRequest(candidate, HttpMethod.POST, "/api/v1/proctoring/sessions/$sessionId/seal", mapOf(
            "expected_version" to beforeSeal.path("version").asInt(), "capture_id" to captureId,
            "last_sequences" to mapOf("camera" to 0, "screen" to 0)), UUID.randomUUID().toString())
        assertEquals(200, sealed.statusCode.value())
        val submitted = localRequest(candidate, HttpMethod.POST, "/api/v1/attempts/$attemptId/submit",
            mapOf("expected_version" to attempt.body!!.path("data").path("version").asInt()), UUID.randomUUID().toString())
        assertEquals(200, submitted.statusCode.value())
        assertEquals("submitted", submitted.body!!.path("data").path("status").asText())
        assertTrue(submitted.body!!.path("data").path("result").isNull)
        assertEquals("junior", jdbc.queryForObject("SELECT grade FROM qualifications WHERE user_id=?::uuid", String::class.java, candidate.id))
        return PendingMiddleFixture(candidate, attemptId, sessionId)
    }

    private fun createMiddleAttempt(candidate: ru.fsp.talent.core.TestActor) = localRequest(candidate, HttpMethod.POST, "/api/v1/attempts", mapOf(
        "specialization_id" to "backend-python-v1", "requested_grade" to "middle",
        "policy_id" to "backend-python-policy-v1", "policy_version" to "1.0.0"), UUID.randomUUID().toString())

    private data class PendingMiddleFixture(val candidate: ru.fsp.talent.core.TestActor, val attemptId: String, val sessionId: String)

    private fun localRequest(actor: ru.fsp.talent.core.TestActor, method: HttpMethod, path: String, body: Any? = null,
                             idempotencyKey: String? = null): ResponseEntity<JsonNode> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            accept = listOf(MediaType.APPLICATION_JSON)
            set("Origin", "http://127.0.0.1:8080")
            set("Cookie", "${SessionSupport.COOKIE_NAME}=${actor.sessionCookie}")
            set("X-CSRF-Token", actor.csrfToken)
            idempotencyKey?.let { set("Idempotency-Key", it) }
        }
        val payload = body?.let(objectMapper::writeValueAsString)
        return http.exchange("http://localhost:$port$path", method, HttpEntity(payload, headers), JsonNode::class.java)
    }

    private fun validate(schema: String, node: JsonNode) {
        contracts.validate(schema, objectMapper.convertValue(node, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {}))
    }

    private fun upload(actor: ru.fsp.talent.core.TestActor, sessionId: String, captureId: String, sequence: Int, digest: String, bytes: ByteArray,
                       channel: String = "camera"): ResponseEntity<JsonNode> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.parseMediaType("video/webm")
            accept = listOf(MediaType.APPLICATION_JSON)
            set("Origin", "http://127.0.0.1:8080")
            set("Cookie", "${SessionSupport.COOKIE_NAME}=${actor.sessionCookie}")
            set("X-CSRF-Token", actor.csrfToken)
            set("Idempotency-Key", UUID.randomUUID().toString())
            set("X-Content-SHA256", digest)
        }
        return http.exchange("http://localhost:$port/api/v1/proctoring/sessions/$sessionId/media/$channel/chunks/$sequence?capture_id=$captureId",
            HttpMethod.POST, HttpEntity(bytes, headers), com.fasterxml.jackson.databind.JsonNode::class.java)
    }

    private fun download(actor: ru.fsp.talent.core.TestActor, path: String, range: String): ResponseEntity<ByteArray> {
        val headers = HttpHeaders().apply {
            set("Origin", "http://127.0.0.1:8080")
            set("Cookie", "${SessionSupport.COOKIE_NAME}=${actor.sessionCookie}")
            set("Range", range)
        }
        return http.exchange("http://localhost:$port$path", HttpMethod.GET, HttpEntity<Any>(headers), ByteArray::class.java)
    }

    private fun downloadAll(actor: ru.fsp.talent.core.TestActor, path: String): ResponseEntity<ByteArray> {
        val headers = HttpHeaders().apply {
            set("Origin", "http://127.0.0.1:8080")
            set("Cookie", "${SessionSupport.COOKIE_NAME}=${actor.sessionCookie}")
        }
        val response = http.exchange("http://localhost:$port$path", HttpMethod.GET, HttpEntity<Any>(headers), ByteArray::class.java)
        System.getenv("FSP_SEEKABLE_FIXTURE_OUTPUT")?.let { target ->
            val output = Path.of(target)
            Files.createDirectories(output.parent)
            Files.write(output, response.body!!)
        }
        return response
    }

    private fun assertSeekableRepresentation(bytes: ByteArray): Int {
        val roots = ebmlElements(bytes, 0, bytes.size)
        val segment = roots.single { it.id == 0x18538067L }
        assertEquals((bytes.size - segment.dataStart).toLong(), segment.size, "Segment must declare its finite byte length")
        val children = ebmlElements(bytes, segment.dataStart, segment.end)
        val seekHead = children.single { it.id == 0x114D9B74L }
        val seek = ebmlElements(bytes, seekHead.dataStart, seekHead.end).single { it.id == 0x4DBBL }
        val seekFields = ebmlElements(bytes, seek.dataStart, seek.end)
        assertEquals(listOf(0x1c, 0x53, 0xbb, 0x6b), bytes.copyOfRange(seekFields.single { it.id == 0x53ABL }.dataStart,
            seekFields.single { it.id == 0x53ABL }.end).map { it.toInt() and 0xff })
        val cuesAt = segment.dataStart + seekFields.single { it.id == 0x53ACL }.unsignedValue(bytes).toInt()
        assertEquals(listOf(0x1c, 0x53, 0xbb, 0x6b), bytes.copyOfRange(cuesAt, cuesAt + 4).map { it.toInt() and 0xff },
            "SeekHead must resolve to Cues in the virtual Segment")
        val info = children.single { it.id == 0x1549A966L }
        val duration = ebmlElements(bytes, info.dataStart, info.end).single { it.id == 0x4489L }
        assertEquals(8L, duration.size)
        val durationValue = java.nio.ByteBuffer.wrap(bytes, duration.dataStart, 8).order(java.nio.ByteOrder.BIG_ENDIAN).double
        assertTrue(durationValue.isFinite() && durationValue > 4_000.0, "Info Duration must be finite and represent the recorded video")
        val tracks = children.single { it.id == 0x1654AE6BL }
        val videoTrackNumbers = ebmlElements(bytes, tracks.dataStart, tracks.end).filter { it.id == 0xAEL }.mapNotNull { entry ->
            val fields = ebmlElements(bytes, entry.dataStart, entry.end)
            val number = fields.singleOrNull { it.id == 0xD7L }?.unsignedValue(bytes)
            val type = fields.singleOrNull { it.id == 0x83L }?.unsignedValue(bytes)
            number?.takeIf { type == 1L }
        }.toSet()
        val cues = children.single { it.id == 0x1C53BB6BL }
        val cuePoint = ebmlElements(bytes, cues.dataStart, cues.end).first { it.id == 0xBBL }
        val positions = ebmlElements(bytes, cuePoint.dataStart, cuePoint.end).single { it.id == 0xB7L }
        val cueFields = ebmlElements(bytes, positions.dataStart, positions.end)
        val clusterOffset = cueFields.single { it.id == 0xF1L }
        var relative = 0L
        for (offset in clusterOffset.dataStart until clusterOffset.end) relative = (relative shl 8) or (bytes[offset].toLong() and 0xff)
        val clusterIdStart = segment.dataStart + relative
        assertTrue(clusterIdStart + 4 <= bytes.size)
        assertEquals(listOf(0x1f, 0x43, 0xb6, 0x75), bytes.copyOfRange(clusterIdStart.toInt(), clusterIdStart.toInt() + 4).map { it.toInt() and 0xff },
            "CueClusterPosition must resolve to the actual Cluster ID")
        val cueTrack = cueFields.single { it.id == 0xF7L }.unsignedValue(bytes)
        assertTrue(cueTrack in videoTrackNumbers, "cue should target the native video track")
        val cueBlock = cueFields.single { it.id == 0x5378L }.unsignedValue(bytes)
        assertTrue(cueBlock >= 1)
        assertCueTargetsVideoKeyframe(bytes, clusterIdStart.toInt(), cueTrack, cueBlock)
        return clusterIdStart.toInt()
    }

    private fun assertCueTargetsVideoKeyframe(bytes: ByteArray, clusterStart: Int, cueTrack: Long, cueBlock: Long) {
        val idWidth = ebmlVintWidth(bytes[clusterStart].toInt() and 0xff)
        val sizeWidth = ebmlVintWidth(bytes[clusterStart + idWidth].toInt() and 0xff)
        var offset = clusterStart + idWidth + sizeWidth
        var ordinal = 0L
        while (offset < bytes.size) {
            val childIdWidth = ebmlVintWidth(bytes[offset].toInt() and 0xff)
            var childId = 0L
            repeat(childIdWidth) { childId = (childId shl 8) or (bytes[offset + it].toLong() and 0xff) }
            if (childId in setOf(0x114D9B74L, 0x1549A966L, 0x1654AE6BL, 0x1C53BB6BL, 0x1941A469L, 0x1043A770L, 0x1254C367L, 0x1F43B675L)) break
            val child = readTestElement(bytes, offset)
            if (child.id == 0xA3L) {
                ordinal++
                val (track, flags) = blockTrackAndFlags(bytes, child.dataStart, child.end)
                if (ordinal == cueBlock) {
                    assertEquals(cueTrack, track)
                    assertTrue(flags and 0x80 != 0, "CueBlockNumber must identify a keyframe block")
                    return
                }
            } else if (child.id == 0xA0L) {
                val nested = ebmlElements(bytes, child.dataStart, child.end)
                val block = nested.singleOrNull { it.id == 0xA1L }
                if (block != null) {
                    ordinal++
                    val (track, flags) = blockTrackAndFlags(bytes, block.dataStart, block.end)
                    if (ordinal == cueBlock) {
                        assertEquals(cueTrack, track)
                        assertTrue(nested.none { it.id == 0xFBL }, "CueBlockNumber must identify a keyframe block")
                        return
                    }
                }
            }
            offset = child.end
        }
        error("CueBlockNumber did not resolve to a keyframe block")
    }

    private fun blockTrackAndFlags(bytes: ByteArray, start: Int, end: Int): Pair<Long, Int> {
        val first = bytes[start].toInt() and 0xff
        val width = ebmlVintWidth(first)
        require(width <= 8 && start + width + 3 <= end)
        val marker = 1 shl (8 - width)
        var track = (first and marker.inv()).toLong()
        for (index in 1 until width) track = (track shl 8) or (bytes[start + index].toLong() and 0xff)
        val flags = bytes[start + width + 2].toInt() and 0xff
        return track to flags
    }

    private fun readTestElement(bytes: ByteArray, start: Int): TestEbmlElement {
        val idWidth = ebmlVintWidth(bytes[start].toInt() and 0xff)
        var id = 0L
        repeat(idWidth) { id = (id shl 8) or (bytes[start + it].toLong() and 0xff) }
        var offset = start + idWidth
        val firstSize = bytes[offset].toInt() and 0xff
        val sizeWidth = ebmlVintWidth(firstSize)
        val marker = 1 shl (8 - sizeWidth)
        var size = (firstSize and marker.inv()).toLong()
        offset++
        repeat(sizeWidth - 1) { size = (size shl 8) or (bytes[offset++].toLong() and 0xff) }
        require(size != ((1L shl (7 * sizeWidth)) - 1))
        val end = offset.toLong() + size
        require(end <= bytes.size && end <= Int.MAX_VALUE)
        return TestEbmlElement(id, offset, end.toInt())
    }

    private data class TestEbmlElement(val id: Long, val dataStart: Int, val end: Int) {
        val size: Long get() = (end - dataStart).toLong()
        fun unsignedValue(bytes: ByteArray): Long {
            var value = 0L
            for (offset in dataStart until end) value = (value shl 8) or (bytes[offset].toLong() and 0xff)
            return value
        }
    }

    private fun ebmlElements(bytes: ByteArray, start: Int, end: Int): List<TestEbmlElement> {
        val result = mutableListOf<TestEbmlElement>()
        var offset = start
        while (offset < end) {
            val idStart = offset
            val idWidth = ebmlVintWidth(bytes[offset].toInt() and 0xff)
            require(idWidth in 1..4)
            if (offset + idWidth > end) error("Truncated test EBML ID")
            var id = 0L
            repeat(idWidth) { id = (id shl 8) or (bytes[offset++].toLong() and 0xff) }
            val sizeWidth = ebmlVintWidth(bytes[offset].toInt() and 0xff)
            if (offset + sizeWidth > end) error("Truncated test EBML size")
            val marker = 1 shl (8 - sizeWidth)
            var size = (bytes[offset++].toInt() and 0xff and marker.inv()).toLong()
            repeat(sizeWidth - 1) { size = (size shl 8) or (bytes[offset++].toLong() and 0xff) }
            if (size == ((1L shl (7 * sizeWidth)) - 1)) return result
            val dataStart = offset
            val elementEnd = dataStart.toLong() + size
            if (elementEnd > end || elementEnd > Int.MAX_VALUE) error("Invalid test EBML element at $idStart")
            result += TestEbmlElement(id, dataStart, elementEnd.toInt())
            offset = elementEnd.toInt()
        }
        if (offset != end) error("Misaligned test EBML element list")
        return result
    }

    private fun ebmlVintWidth(first: Int): Int = Integer.numberOfLeadingZeros(first) - 24 + 1

    private fun ByteArray.indexOfId(id: ByteArray): Int {
        if (id.size > size) return -1
        for (index in 0..(size - id.size)) if (copyOfRange(index, index + id.size).contentEquals(id)) return index
        return -1
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class BlockingInputStream(private val payload: ByteArray, private val started: CountDownLatch,
                                      private val proceed: CountDownLatch) : InputStream() {
        private var offset = 0
        private var paused = false
        override fun read(): Int {
            if (offset >= payload.size) return -1
            pauseOnce()
            return payload[offset++].toInt() and 0xff
        }
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (offset >= payload.size) return -1
            pauseOnce()
            val count = minOf(length, payload.size - offset)
            System.arraycopy(payload, offset, buffer, start, count)
            offset += count
            return count
        }
        private fun pauseOnce() {
            if (paused) return
            paused = true
            started.countDown()
            if (!proceed.await(5, TimeUnit.SECONDS)) throw IllegalStateException("Upload gate timed out")
        }
    }

    private class CountingInputStream(private val payload: ByteArray) : InputStream() {
        var readCount = 0
            private set
        private var offset = 0
        override fun read(): Int {
            readCount++
            if (offset >= payload.size) return -1
            return payload[offset++].toInt() and 0xff
        }
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            readCount++
            if (offset >= payload.size) return -1
            val count = minOf(length, payload.size - offset)
            System.arraycopy(payload, offset, buffer, start, count)
            offset += count
            return count
        }
    }

    companion object {
        private val mediaRoot: Path = Files.createTempDirectory("fsp-proctoring-flow-")
        @JvmStatic
        @DynamicPropertySource
        fun proctoringProperties(registry: DynamicPropertyRegistry) {
            registry.add("fsp.proctoring.media-root") { mediaRoot.toString() }
            registry.add("fsp.public-origin") { "http://127.0.0.1:8080" }
            registry.add("fsp.proctoring.retention-days") { "7" }
            registry.add("fsp.proctoring.max-chunk-bytes") { "4194304" }
            registry.add("fsp.proctoring.max-session-bytes") { "268435456" }
        }
    }
}
