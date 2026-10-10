package ru.fsp.talent.demo

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import ru.fsp.talent.core.Store

/** The methodist permission cannot be requested through public registration. */
@Component
@ConditionalOnProperty(prefix = "fsp", name = ["seed-demo", "demo-mode"], havingValue = "true")
class MethodistDemoSeeder(private val store: Store, private val passwords: PasswordEncoder) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        store.tx.execute {
            val existing = store.one("SELECT id FROM users WHERE email = 'methodist@example.test'")
            val userId = existing?.get("id")?.toString() ?: store.id().also { id ->
                store.exec("""INSERT INTO users(id,email,password_hash,role,email_verified_at)
                    VALUES (:id,'methodist@example.test',:password,'candidate',now())""".trimIndent(),
                    mapOf("id" to id, "password" to passwords.encode("DemoPassword123!")))
                store.exec("""INSERT INTO candidate_profiles(user_id,visibility,data)
                    VALUES (:id,'hidden',:data)""".trimIndent(), mapOf("id" to id,
                    "data" to store.json(mapOf("display_name" to "Демо-методист", "demo" to true))))
            }
            store.exec("""INSERT INTO assessment_methodists(user_id,active) VALUES (:id,true)
                ON CONFLICT(user_id) DO NOTHING""".trimIndent(), mapOf("id" to userId))
        }
    }
}
