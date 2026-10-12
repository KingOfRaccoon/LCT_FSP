package ru.fsp.talent.core

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.nio.ByteBuffer
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Rolling windows persisted outside failed business transactions; subjects are HMAC-pseudonymised. */
@Component
class RateLimits(private val store: Store, @Value("\${fsp.session-secret}") private val secret: String) {
    data class Rule(val scope: String, val subject: String, val limit: Int, val windowSeconds: Long,
                    val minIntervalSeconds: Long = 0)
    private val independent = TransactionTemplate(store.tx.transactionManager!!).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    private val ordinary = TransactionTemplate(store.tx.transactionManager!!)

    fun clientIp(request: HttpServletRequest): String = request.remoteAddr

    fun consume(vararg rules: Rule) {
        ordinary.execute {
            val keyed = rules.map { it to bucket(it.scope,it.subject) }.sortedBy { it.second }
            keyed.forEach { (_, key) -> lock(key) }
            keyed.forEach { (rule,key) -> assertAllowed(rule,key) }
            keyed.forEach { (_,key) -> add(key) }
        }
    }

    fun failedLogin(subject: String, credentials: () -> Map<String,Any?>?): Map<String,Any?> {
        val rule = Rule("login-fail",subject,5,900)
        val key = bucket(rule.scope,rule.subject)
        val result = independent.execute {
            lock(key)
            val blocked = store.one("SELECT blocked_until FROM rate_limit_blocks WHERE bucket_key=:key",
                mapOf("key" to key))?.get("blocked_until") as? Instant
            if (blocked != null && blocked.isAfter(store.now())) limited(blocked)
            val user = credentials()
            if (user == null) {
                add(key)
                val count=(store.one("SELECT count(*) AS n FROM rate_limit_events WHERE bucket_key=:key AND occurred_at>:since",
                    mapOf("key" to key,"since" to store.now().minusSeconds(900)))!!.getValue("n") as Number).toInt()
                if(count>=5) store.exec("INSERT INTO rate_limit_blocks(bucket_key,blocked_until) VALUES(:key,:until) " +
                    "ON CONFLICT(bucket_key) DO UPDATE SET blocked_until=EXCLUDED.blocked_until",
                    mapOf("key" to key,"until" to store.now().plusSeconds(900)))
            } else {
                store.exec("DELETE FROM rate_limit_events WHERE bucket_key=:key",mapOf("key" to key))
                store.exec("DELETE FROM rate_limit_blocks WHERE bucket_key=:key",mapOf("key" to key))
            }
            user
        }
        return result ?: throw ApiException(401,"UNAUTHENTICATED","Неверная почта или пароль.")
    }

    private fun assertAllowed(rule:Rule,key:String) {
        val rows=store.rows("SELECT occurred_at FROM rate_limit_events WHERE bucket_key=:key AND occurred_at>:since ORDER BY occurred_at",
            mapOf("key" to key,"since" to store.now().minusSeconds(rule.windowSeconds)))
        if(rows.size>=rule.limit) limited((rows.first().getValue("occurred_at") as Instant).plusSeconds(rule.windowSeconds))
        val last=rows.lastOrNull()?.get("occurred_at") as? Instant
        if(last!=null && store.now().isBefore(last.plusSeconds(rule.minIntervalSeconds))) limited(last.plusSeconds(rule.minIntervalSeconds))
    }

    private fun add(key:String) {
        store.exec("INSERT INTO rate_limit_events(bucket_key,occurred_at) VALUES(:key,:now)",mapOf("key" to key,"now" to store.now()))
    }
    private fun lock(key:String) {
        val bytes=hexToBytes(key.substringAfter(':'))
        store.one("SELECT pg_advisory_xact_lock(:lock)",mapOf("lock" to ByteBuffer.wrap(bytes.copyOfRange(0,8)).long))
    }
    private fun limited(until:Instant):Nothing=throw ApiException(429,"RATE_LIMITED","Слишком много запросов. Повторите позже.",eligibleAt=until)
    private fun bucket(scope:String,subject:String):String {
        val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(secret.toByteArray(),"HmacSHA256"))
        val hash=mac.doFinal("$scope:$subject".toByteArray()).joinToString(""){"%02x".format(it)}
        return "$scope:$hash"
    }
    private fun hexToBytes(hex:String)=hex.chunked(2).map{it.toInt(16).toByte()}.toByteArray()
}
