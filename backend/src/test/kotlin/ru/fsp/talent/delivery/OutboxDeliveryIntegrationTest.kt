package ru.fsp.talent.delivery

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessagePreparator
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.SessionSupport
import ru.fsp.talent.identity.IdentityService
import java.io.InputStream
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@TestPropertySource(properties = [
    "fsp.outbox-enabled=true",
    "fsp.outbox-initial-delay-ms=3600000",
    "fsp.outbox-delay-ms=3600000",
])
@Import(OutboxDeliveryIntegrationTest.MailTestConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxDeliveryIntegrationTest : DbTestBase() {
    @Autowired private lateinit var worker: OutboxWorker
    @Autowired private lateinit var sender: CapturingJavaMailSender
    @Autowired private lateinit var identity: IdentityService

    @BeforeEach
    fun resetSender() {
        sender.reset()
        jdbc.update("DELETE FROM outbox_events WHERE state='pending'")
    }

    @Test
    fun `verification outbox sends derived token then marks row delivered`() {
        val email = "delivery-${UUID.randomUUID()}@example.test"
        val (verificationId, jobId) = enqueueVerification(email)

        assertTrue(worker.deliverOne())

        val message = sender.messages.single { email in it.recipients }
        assertEquals(email, message.recipients.single())
        assertEquals("no-reply@example.test", message.from)
        assertTrue(message.subject.orEmpty().contains("Подтверждение почты"))
        assertTrue(message.text.orEmpty().contains("http://testserver/confirm-email?token="))
        assertTrue(message.text.orEmpty().contains(identity.verificationToken(verificationId)))
        val outbox = jdbc.queryForMap("SELECT state, attempts, delivered_at, payload FROM outbox_events WHERE id::text = ?", jobId)
        assertEquals("delivered", outbox["state"])
        assertEquals(1, (outbox["attempts"] as Number).toInt())
        assertNotNull(outbox["delivered_at"])
        assertTrue(outbox["payload"].toString().contains(verificationId))
        assertFalse(outbox["payload"].toString().contains(identity.verificationToken(verificationId)))
        assertFalse(worker.deliverOne())
    }

    @Test
    fun `used verification token is discarded without sending mail`() {
        val email = "used-${UUID.randomUUID()}@example.test"
        val (_, jobId) = enqueueVerification(email, used = true)

        assertTrue(worker.deliverOne())

        val outbox = jdbc.queryForMap("SELECT state, attempts, last_error FROM outbox_events WHERE id::text = ?", jobId)
        assertEquals("discarded", outbox["state"])
        assertEquals(0, (outbox["attempts"] as Number).toInt())
        assertNull(outbox["last_error"])
        assertTrue(sender.messages.none { email in it.recipients })
    }

    @Test
    fun `verification for an old email snapshot is discarded`() {
        val oldEmail = "old-${UUID.randomUUID()}@example.test"
        val currentEmail = "current-${UUID.randomUUID()}@example.test"
        val (_, jobId) = enqueueVerification(oldEmail, currentEmail = currentEmail)

        assertTrue(worker.deliverOne())

        val outbox = jdbc.queryForMap("SELECT state, attempts FROM outbox_events WHERE id::text = ?", jobId)
        assertEquals("discarded", outbox["state"])
        assertEquals(0, (outbox["attempts"] as Number).toInt())
        assertTrue(sender.messages.none { oldEmail in it.recipients })
    }

    @Test
    fun `delivery failures retry with bounded attempts then stop`() {
        val email = "retry-${UUID.randomUUID()}@example.test"
        val (_, jobId) = enqueueVerification(email)
        sender.failAll = true

        repeat(5) { attempt ->
            if (attempt > 0) jdbc.update("UPDATE outbox_events SET next_attempt_at=now() - interval '5 seconds' WHERE id::text=?", jobId)
            assertTrue(worker.deliverOne())
        }

        val outbox = jdbc.queryForMap("SELECT state, attempts, last_error FROM outbox_events WHERE id::text = ?", jobId)
        assertEquals("failed", outbox["state"])
        assertEquals(5, (outbox["attempts"] as Number).toInt())
        assertEquals("SMTP delivery failed", outbox["last_error"])
        assertEquals(5, sender.attemptsFor(email))
        assertFalse(worker.deliverOne())
    }

    private fun enqueueVerification(
        email: String,
        used: Boolean = false,
        currentEmail: String = email,
    ): Pair<String, String> {
        val userId = UUID.randomUUID().toString()
        val verificationId = UUID.randomUUID().toString()
        val jobId = UUID.randomUUID().toString()
        val token = identity.verificationToken(verificationId)
        jdbc.update(
            "INSERT INTO users(id, email, password_hash, role) VALUES (?::uuid, ?, ?, 'candidate')",
            userId, currentEmail, "unused-test-hash",
        )
        jdbc.update(
            """INSERT INTO email_verification(id, user_id, email, token_hash, expires_at, used_at)
               VALUES (?::uuid, ?::uuid, ?, ?, now() + interval '30 minutes', ?)""",
            verificationId, userId, email, SessionSupport.digest(token), if (used) java.sql.Timestamp.from(Instant.now()) else null,
        )
        jdbc.update(
            "INSERT INTO outbox_events(id, type, payload, next_attempt_at) VALUES (?::uuid, 'email_verification', ?::jsonb, now() - interval '5 seconds')",
            jobId, objectMapper.writeValueAsString(mapOf("verification_id" to verificationId)),
        )
        return verificationId to jobId
    }

    @TestConfiguration
    class MailTestConfiguration {
        @Bean
        @Primary
        fun capturingJavaMailSender() = CapturingJavaMailSender()
    }
}

data class CapturedMessage(
    val from: String?,
    val recipients: List<String>,
    val subject: String?,
    val text: String?,
)

class CapturingJavaMailSender : JavaMailSender {
    val messages = CopyOnWriteArrayList<CapturedMessage>()
    @Volatile var failAll = false
    private val attemptCounts = ConcurrentHashMap<String, AtomicInteger>()

    fun attemptsFor(email: String): Int = attemptCounts[email]?.get() ?: 0

    fun reset() {
        messages.clear()
        failAll = false
        attemptCounts.clear()
    }

    override fun send(simpleMessage: SimpleMailMessage) {
        simpleMessage.to.orEmpty().forEach { recipient ->
            attemptCounts.computeIfAbsent(recipient) { AtomicInteger() }.incrementAndGet()
        }
        if (failAll) throw IllegalStateException("fake SMTP failure")
        messages += CapturedMessage(
            simpleMessage.from,
            simpleMessage.to?.toList().orEmpty(),
            simpleMessage.subject,
            simpleMessage.text,
        )
    }

    override fun send(vararg simpleMessages: SimpleMailMessage) = simpleMessages.forEach(::send)

    override fun createMimeMessage(): MimeMessage = MimeMessage(Session.getInstance(Properties()))

    override fun createMimeMessage(contentStream: InputStream): MimeMessage =
        MimeMessage(Session.getInstance(Properties()), contentStream)

    override fun send(mimeMessage: MimeMessage) = Unit

    override fun send(vararg mimeMessages: MimeMessage) = Unit

    override fun send(mimeMessagePreparator: MimeMessagePreparator) {
        val mimeMessage = createMimeMessage()
        mimeMessagePreparator.prepare(mimeMessage)
        send(mimeMessage)
    }

    override fun send(vararg mimeMessagePreparators: MimeMessagePreparator) =
        mimeMessagePreparators.forEach(::send)
}
