package ru.fsp.talent.demo

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import ru.fsp.talent.core.Store

/** Explicit, synthetic demonstration accounts; never run by tests or a real-data deployment. */
@Component
@ConditionalOnProperty(prefix = "fsp", name = ["seed-demo"], havingValue = "true")
class DemoSeeder(private val store: Store, private val encoder: PasswordEncoder) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        store.tx.execute {
            if (store.one("SELECT id FROM users WHERE email='candidate@example.test'") != null) return@execute
            val password = encoder.encode("DemoPassword123!")
            fun user(email: String, role: String): String {
                val id = store.id()
                store.exec(
                    "INSERT INTO users(id,email,password_hash,role,email_verified_at) VALUES(:id,:email,:password,:role,:now)",
                    mapOf("id" to id,"email" to email,"password" to password,"role" to role,"now" to store.now()),
                )
                return id
            }
            fun profile(id: String, name: String, grade: String, verified: Boolean, skills: List<String>) {
                val data = mapOf("display_name" to name, "headline" to "Backend · демонстрационный профиль",
                    "summary" to "Синтетический профиль для проверки рабочего сценария.", "specialization_id" to "backend-python-v1",
                    "declared_grade" to grade, "allowed_work_modes" to listOf("remote"), "cities" to emptyList<String>(),
                    "desired_hours_per_week" to 40, "max_weekly_hours" to 40, "demo" to true,
                    "skills" to skills.map { mapOf("skill_id" to it, "provenance" to "candidate_declared") })
                val email = store.one("SELECT email FROM users WHERE id=:id", mapOf("id" to id))!!.getValue("email")
                store.exec(
                    "INSERT INTO candidate_profiles(user_id,visibility,data,contacts) VALUES(:id,'public',:data,:contacts)",
                    mapOf("id" to id,"data" to store.json(data),"contacts" to store.json(mapOf("channels" to listOf(
                        mapOf("type" to "email", "value" to email, "enabled" to true))))),
                )
                if (verified) {
                    val qualification = mapOf("demo" to true, "form_version" to "seed-demo-v1", "passed" to true,
                        "competency_summary" to listOf("python-basics","http-api","sql-transactions","reliability")
                            .map { mapOf("skill_id" to it,"status" to "verified","summary" to "Синтетический пример подтверждения") })
                    store.exec(
                        "INSERT INTO qualifications(id,user_id,specialization_id,grade,assessed_at,data) " +
                            "VALUES(:id,:user_id,'backend-python-v1',:grade,:now,:data)",
                        mapOf("id" to store.id(),"user_id" to id,"grade" to grade,"now" to store.now(),"data" to store.json(qualification)),
                    )
                }
            }
            val candidate = user("candidate@example.test", "candidate")
            profile(candidate,"Демо-кандидат","junior",false,listOf("python-basics","http-api","fastapi"))
            for ((index,name) in listOf("Демо Алексей","Демо Мария","Демо Никита").withIndex()) {
                val id = user("talent-${index + 1}@example.test","candidate")
                profile(id,name,if(index==2)"middle" else "junior",true,listOf("fastapi","postgres","docker"))
            }
            for ((email,name) in listOf("employer@example.test" to "Демо · Продуктовая команда",
                "other-employer@example.test" to "Демо · Другая компания")) {
                val id=user(email,"employer"); val company=store.id()
                store.exec("INSERT INTO companies(id,owner_id,data) VALUES(:id,:owner_id,:data)",
                    mapOf("id" to company,"owner_id" to id,"data" to store.json(mapOf("name" to name,
                        "description" to "Синтетическая компания для проверки подбора.","website" to null,"industry" to "ИТ","contact_email" to email))))
                store.exec("INSERT INTO memberships(id,user_id,company_id,role) VALUES(:id,:user_id,:company_id,'owner')",
                    mapOf("id" to store.id(),"user_id" to id,"company_id" to company))
            }
        }
    }
}
