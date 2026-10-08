package ru.fsp.talent.documents

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import ru.fsp.talent.core.Store
import ru.fsp.talent.invitations.listValue
import java.io.ByteArrayOutputStream

@Component
class ProfilePdfService(private val store: Store) {
    fun render(candidate: Map<String, Any?>, contacts: List<Map<String, String>> = emptyList()): ByteArray {
        PDDocument().use { document ->
            val font = ClassPathResource("fonts/JetBrainsMono-Regular.ttf").inputStream.use {
                PDType0Font.load(document, it)
            }
            val lines = passportLines(candidate, contacts)
            var page = PDPage(PDRectangle.A4)
            document.addPage(page)
            var stream = PDPageContentStream(document, page)
            var y = page.mediaBox.height - 48f
            try {
                lines.forEach { rawLine ->
                    val wrapped = wrap(supportedText(rawLine, font), font, 9f, page.mediaBox.width - 96f)
                    wrapped.forEach { line ->
                        if (y < 48f) {
                            stream.close()
                            page = PDPage(PDRectangle.A4)
                            document.addPage(page)
                            stream = PDPageContentStream(document, page)
                            y = page.mediaBox.height - 48f
                        }
                        stream.beginText()
                        stream.setFont(font, 9f)
                        stream.newLineAtOffset(48f, y)
                        stream.showText(line)
                        stream.endText()
                        y -= 14f
                    }
                    y -= 5f
                }
            } finally {
                stream.close()
            }
            return ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        }
    }

    private fun passportLines(candidate: Map<String, Any?>, contacts: List<Map<String, String>>): List<String> = buildList {
        add("ПАСПОРТ КАНДИДАТА")
        add(candidate["display_name"]?.toString().orEmpty())
        add("Специализация: ${candidate["specialization_id"] ?: "не указана"}")
        add("Заявленный грейд: ${candidate["declared_grade"] ?: "не указан"}")
        val qualification = candidate["qualification"] as? Map<*, *>
        if (qualification == null) {
            add("Подтвержденная квалификация: нет данных")
        } else {
            add("Подтвержденный грейд: ${qualification["grade"]}")
            add("Метод: серверная оценка; политика ${qualification["policy_id"]} ${qualification["policy_version"]}")
            add("Версия формы: ${qualification["bank_version"]}; дата: ${qualification["assessed_at"]}")
            add(if (qualification["demo"] == true) "Оценка помечена как демонстрационная" else "Оценка не помечена как демонстрационная")
        }
        candidate["headline"]?.toString()?.takeIf(String::isNotBlank)?.let { add("Заголовок: $it") }
        candidate["summary"]?.toString()?.takeIf(String::isNotBlank)?.let { add("О кандидате: $it") }
        candidate["industry"]?.toString()?.takeIf(String::isNotBlank)?.let { add("Отрасль (заявлено кандидатом): $it") }
        val roles = stringList(candidate["role_preferences"])
        if (roles.isNotEmpty()) add("Рассматриваемые роли (заявлено кандидатом): ${roles.joinToString(", ")}")
        val softSkills = stringList(candidate["soft_skills"])
        if (softSkills.isNotEmpty()) add("Гибкие навыки (заявлено кандидатом): ${softSkills.joinToString(", ")}")
        add("Рабочие режимы: ${stringList(candidate["allowed_work_modes"]).joinToString(", ")}")
        add("Города: ${stringList(candidate["cities"]).joinToString(", ").ifBlank { "не указаны" }}")
        add("Максимальная нагрузка: ${candidate["max_weekly_hours"] ?: "не указана"} ч/нед.")
        val experience = store.listValue(candidate["experience_entries"])
        if (experience.isNotEmpty()) {
            add("Опыт по заявлению кандидата")
            experience.forEach { entry ->
                val dates = listOfNotNull(entry["start_month"]?.toString(), entry["end_month"]?.toString()).joinToString(" — ")
                add("${entry["role"] ?: "Роль не указана"}${entry["company"]?.toString()?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}${dates.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}")
                entry["description"]?.toString()?.takeIf(String::isNotBlank)?.let { add(it) }
            }
        }
        val projects = store.listValue(candidate["project_entries"])
        if (projects.isNotEmpty()) {
            add("Проекты по заявлению кандидата")
            projects.forEach { entry ->
                add(entry["title"]?.toString().orEmpty())
                entry["description"]?.toString()?.takeIf(String::isNotBlank)?.let { add(it) }
                entry["url"]?.toString()?.takeIf(String::isNotBlank)?.let { add("Ссылка: $it") }
            }
        }
        add("Компетенции и происхождение")
        store.listValue(candidate["competency_evidence"]).forEach { proof ->
            add("• ${proof["skill_id"]}: ${proof["status"]} (${proof["source"]})")
        }
        add("Данные ФСП")
        val fspEvidence = store.listValue(candidate["fsp_evidence"])
        if (fspEvidence.isEmpty()) add("Нет доступных записей")
        fspEvidence.forEach { evidence ->
            add("• ${evidence["event_title"]}; дата: ${evidence["event_date"] ?: "не указана"}; результат: ${evidence["result"]}")
            add("  Командный результат: ${evidence["team_result"]}; участие подтверждено: ${evidence["membership_confirmed"]}")
            add("  Источник: ${evidence["source"]}; демонстрация: ${evidence["demo"]}; статус: ${evidence["validity"]}")
        }
        add("Записи ФСП показаны с указанным источником и не означают официальную проверку.")
        if (contacts.isNotEmpty()) {
            add("Контакты, доступные по текущему согласию")
            contacts.forEach { channel -> add("${channel["type"]}: ${channel["value"]}") }
        }
    }

    private fun stringList(value: Any?): List<String> = (value as? Collection<*>)?.mapNotNull { it?.toString() }.orEmpty()

    private fun supportedText(value: String, font: PDType0Font): String = buildString {
        value.codePoints().toArray().forEach { codePoint ->
            if (Character.isWhitespace(codePoint)) {
                append(' ')
            } else {
                val glyph = String(Character.toChars(codePoint))
                if (runCatching { font.getStringWidth(glyph) }.isSuccess) append(glyph) else append('?')
            }
        }
    }

    private fun wrap(text: String, font: PDType0Font, fontSize: Float, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        var line = ""
        text.split(Regex("\\s+")).forEach { word ->
            val next = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && font.getStringWidth(next) * fontSize / 1000f > maxWidth) {
                result += line
                line = word
            } else line = next
        }
        if (line.isNotEmpty()) result += line
        return result
    }
}
