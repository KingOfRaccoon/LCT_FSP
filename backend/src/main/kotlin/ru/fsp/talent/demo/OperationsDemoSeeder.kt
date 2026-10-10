package ru.fsp.talent.demo

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import ru.fsp.talent.core.Store
import ru.fsp.talent.operations.OperationsAccess

/** A hidden, synthetic operator account for the local demonstration only. */
@Component
@ConditionalOnProperty(prefix = "fsp", name = ["seed-demo", "demo-mode"], havingValue = "true")
class OperationsDemoSeeder(private val store: Store, private val passwords: PasswordEncoder) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        store.tx.execute {
            val prior = store.one("SELECT id FROM users WHERE email='operator@example.test'")
            val userId = prior?.get("id")?.toString() ?: store.id().also { id ->
                store.exec("""INSERT INTO users(id,email,password_hash,role,email_verified_at)
                    VALUES(:id,'operator@example.test',:password,'candidate',now())""".trimIndent(),
                    mapOf("id" to id, "password" to passwords.encode("DemoPassword123!")))
                store.exec("""INSERT INTO candidate_profiles(user_id,visibility,data)
                    VALUES(:id,'hidden',:data)""".trimIndent(), mapOf("id" to id,
                    "data" to store.json(mapOf("display_name" to "Демо-оператор", "demo" to true))))
            }
            OperationsAccess.PERMISSIONS.forEach { permission ->
                store.exec("""INSERT INTO operations_grants(user_id,permission,active) VALUES(:userId,:permission,true)
                    ON CONFLICT(user_id,permission) DO NOTHING""".trimIndent(),
                    mapOf("userId" to userId, "permission" to permission))
            }
        }
    }
}
