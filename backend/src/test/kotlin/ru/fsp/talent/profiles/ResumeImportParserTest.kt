package ru.fsp.talent.profiles

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResumeImportParserTest {
    private val parser = ResumeImportParser()

    @Test
    fun `extracts only labeled profile fields supported skills and date-labeled experience`() {
        val draft = parser.parse(
            """Алексей Иванов
                |Должность: Backend-разработчик
                |Город: Москва
                |Email: alex@example.com
                |Телефон: +7 999 123-45-67
                |Навыки: Python, FastAPI, PostgreSQL, Docker, SQL, REST API, Kubernetes
                |Опыт работы
                |ООО Ромашка — Backend developer
                |Период работы: январь 2022 — настоящее время
            """.trimMargin(),
        )

        assertEquals("Алексей Иванов", draft.candidatePatch["display_name"])
        assertEquals("Backend-разработчик", draft.candidatePatch["headline"])
        assertEquals(listOf("Москва"), draft.candidatePatch["cities"])
        assertEquals(
            setOf("python-basics", "fastapi", "postgres", "docker", "sql-transactions", "http-api"),
            (draft.candidatePatch["skills"] as List<*>).toSet(),
        )
        val experience = (draft.candidatePatch["experience_entries"] as List<*>).single() as Map<*, *>
        assertEquals("ООО Ромашка", experience["company"])
        assertEquals("Backend developer", experience["role"])
        assertEquals("2022-01", experience["start_month"])
        assertEquals(null, experience["end_month"])
        assertFalse(experience.containsKey("provenance"))
        assertEquals(
            listOf(
                mapOf("type" to "email", "value" to "alex@example.com"),
                mapOf("type" to "phone", "value" to "+7 999 123-45-67"),
            ),
            draft.suggestions["contacts"],
        )
        assertTrue(draft.needsReview)
        assertFalse(draft.candidatePatch.containsKey("contacts"))
        assertFalse(draft.candidatePatch.containsKey("declared_grade"))
        assertFalse(draft.candidatePatch.containsKey("verified_grade"))
    }

    @Test
    fun `does not infer identity headline city skills or experience from unlabeled prose`() {
        val draft = parser.parse(
            """Привет, меня зовут Алексей Иванов и я ищу работу в Москве.
                |Я работал Python developer в ООО Ромашка с 2022 года.
                |Мой номер опыта 15 лет, ожидаемый грейд middle.
            """.trimMargin(),
        )

        assertTrue(draft.candidatePatch.isEmpty())
        assertTrue((draft.suggestions["contacts"] as List<*>).isEmpty())
        assertTrue(draft.warnings.isNotEmpty())
    }

    @Test
    fun `imports explicitly labeled names and role headlines`() {
        listOf("ФИО", "Полное имя", "Name", "Full name").forEach { label ->
            val draft = parser.parse("$label: Алексей Иванов\nRole: Python Developer")

            assertEquals("Алексей Иванов", draft.candidatePatch["display_name"], label)
            assertEquals("Python Developer", draft.candidatePatch["headline"], label)
        }
    }

    @Test
    fun `parses English period employment dates without treating date or salary ranges as phones`() {
        val draft = parser.parse(
            """Experience
                |Demo Lab | Backend Developer
                |Period: 2021-04 - 2023-03
                |Salary: 120000 - 180000 RUB
            """.trimMargin(),
        )

        val experience = (draft.candidatePatch["experience_entries"] as List<*>).single() as Map<*, *>
        assertEquals("Demo Lab", experience["company"])
        assertEquals("Backend Developer", experience["role"])
        assertEquals("2021-04", experience["start_month"])
        assertEquals("2023-03", experience["end_month"])
        assertTrue((draft.suggestions["contacts"] as List<*>).isEmpty())
    }

    @Test
    fun `suggests local numbers only from phone labels and accepts international plus numbers`() {
        val draft = parser.parse(
            """Unlabeled number: 999 123-45-67
                |Phone: 8 (999) 123-45-67
                |Mobile: +1 415 555 0100
            """.trimMargin(),
        )

        assertEquals(
            listOf("8 (999) 123-45-67", "+1 415 555 0100"),
            (draft.suggestions["contacts"] as List<*>).map { (it as Map<*, *>)["value"] },
        )
    }
}
