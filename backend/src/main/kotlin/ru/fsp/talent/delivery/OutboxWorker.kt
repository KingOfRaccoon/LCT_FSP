package ru.fsp.talent.delivery

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.fsp.talent.core.Store
import ru.fsp.talent.identity.IdentityService
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

object OutboxRules {
    fun delayAfterFailureMinutes(attempt: Int): Long? = listOf(1L, 5L, 15L, 60L).getOrNull(attempt - 1)
}

@Configuration
@EnableScheduling
class DeliveryScheduling

/** Sends only a token derived at delivery time; the outbox contains a record ID, not a secret. */
@Component
@ConditionalOnProperty(prefix = "fsp", name = ["outbox-enabled"], havingValue = "true")
class OutboxWorker(
    private val store: Store,
    private val identity: IdentityService,
    private val sender: JavaMailSender,
    @Value("\${fsp.public-origin}") private val publicOrigin: String,
    @Value("\${SENDER_EMAIL:no-reply@example.test}") private val senderAddress: String,
) {
    @Scheduled(
        initialDelayString = "\${fsp.outbox-initial-delay-ms:0}",
        fixedDelayString = "\${fsp.outbox-delay-ms:15000}",
    )
    fun tick() {
        repeat(10) { if (!deliverOne()) return }
    }

    fun deliverOne(): Boolean = store.tx.execute {
        val job = store.one(
            "SELECT * FROM outbox_events WHERE state='pending' AND next_attempt_at <= :now " +
                "ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1",
            mapOf("now" to store.now()),
        ) ?: return@execute false
        val jobId = job.getValue("id").toString()
        @Suppress("UNCHECKED_CAST")
        val payload = job["payload"] as Map<String, Any?>
        val tokenId = payload["verification_id"]?.toString()
        val verification = tokenId?.let {
            store.one(
                "SELECT ev.*, u.email AS current_email, u.email_verified_at, u.deleted_at " +
                    "FROM email_verification ev JOIN users u ON u.id=ev.user_id WHERE ev.id=:id",
                mapOf("id" to it),
            )
        }
        if (job["type"] != "email_verification" || verification == null ||
            verification["used_at"] != null || verification["deleted_at"] != null ||
            verification["email_verified_at"] != null ||
            verification["email"] != verification["current_email"] ||
            !(verification.getValue("expires_at") as Instant).isAfter(store.now())) {
            store.exec("UPDATE outbox_events SET state='discarded',last_error=NULL WHERE id=:id", mapOf("id" to jobId))
            return@execute true
        }
        val attempt = (job.getValue("attempts") as Number).toInt() + 1
        try {
            val token = identity.verificationToken(tokenId!!)
            val link = "${publicOrigin.trimEnd('/')}/confirm-email?token=" +
                URLEncoder.encode(token, StandardCharsets.UTF_8)
            val mail = SimpleMailMessage().apply {
                from = senderAddress
                setTo(verification.getValue("email").toString())
                subject = "Подтверждение почты — ФСП · Таланты"
                text = "Подтвердите адрес электронной почты:\n$link\n\nСсылка действует 30 минут."
            }
            sender.send(mail)
            store.exec(
                "UPDATE outbox_events SET state='delivered',attempts=:attempt,delivered_at=:now,last_error=NULL WHERE id=:id",
                mapOf("id" to jobId, "attempt" to attempt, "now" to store.now()),
            )
        } catch (_: Exception) {
            val delay = OutboxRules.delayAfterFailureMinutes(attempt)
            store.exec(
                "UPDATE outbox_events SET state=:state,attempts=:attempt,next_attempt_at=:next," +
                    "last_error='SMTP delivery failed' WHERE id=:id",
                mapOf("id" to jobId, "state" to if (delay == null) "failed" else "pending", "attempt" to attempt,
                    "next" to store.now().plusSeconds((delay ?: 0L) * 60)),
            )
        }
        true
    } ?: false
}
