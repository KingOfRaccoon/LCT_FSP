package ru.fsp.talent.profiles

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.stereotype.Service
import java.util.regex.Pattern

data class ParsedResume(
    val displayName: String?,
    val email: String?,
    val phone: String?,
    val headline: String?,
    val summary: String?,
    val declaredGrade: String?,
    val skills: List<String>,
    val softSkills: List<String>,
    val rolePreferences: List<String>,
    val cities: List<String>,
    val rawTextSnippet: String
)

@Service
class ResumeParserService {

    private val emailPattern = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}")
    private val phonePattern = Pattern.compile("(?:\\+7|8|7)?[\\s\\-.(]*(\\d{3})[\\s\\-.)]*(\\d{3})[\\s\\-.]*(\\d{2})[\\s\\-.]*(\\d{2})")

    fun parse(pdfBytes: ByteArray): ParsedResume {
        val fullText = try {
            Loader.loadPDF(pdfBytes).use { document ->
                PDFTextStripper().getText(document)
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Не удалось прочитать PDF файл: ${e.message}", e)
        }

        val lines = fullText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val lowerText = fullText.lowercase()

        // 1. Email extraction
        val emailMatcher = emailPattern.matcher(fullText)
        val email = if (emailMatcher.find()) emailMatcher.group().lowercase() else null

        // 2. Phone extraction
        val phoneMatcher = phonePattern.matcher(fullText)
        val phone = if (phoneMatcher.find()) phoneMatcher.group() else null

        // 3. Name extraction (heuristic: first non-header line that looks like 2 or 3 capitalized words)
        val displayName = findCandidateName(lines)

        // 4. Headline / Title
        val headline = findHeadline(lines)

        // 5. Grade estimation
        val grade = estimateGrade(lowerText)

        // 6. Hard skills
        val skills = extractSkills(lowerText)

        // 7. Soft skills
        val softSkills = extractSoftSkills(lowerText)

        // 8. Role preferences
        val rolePreferences = extractRolePreferences(lowerText)

        // 9. Cities
        val cities = extractCities(lowerText)

        // 10. Summary snippet
        val snippet = lines.take(15).joinToString(" ").take(300)

        return ParsedResume(
            displayName = displayName,
            email = email,
            phone = phone,
            headline = headline,
            summary = snippet.ifBlank { null },
            declaredGrade = grade,
            skills = skills,
            softSkills = softSkills,
            rolePreferences = rolePreferences,
            cities = cities,
            rawTextSnippet = fullText.take(1000)
        )
    }

    private fun findCandidateName(lines: List<String>): String? {
        val skipKeywords = setOf("резюме", "cv", "curriculum", "vitae", "опыт", "навыки", "контакты", "обо мне", "страница")
        for (line in lines.take(8)) {
            val words = line.split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (words.size in 2..4) {
                val isNameCandidate = words.all { w ->
                    w.first().isUpperCase() && w.all { it.isLetter() || it == '-' }
                }
                val containsForbidden = words.any { it.lowercase() in skipKeywords }
                if (isNameCandidate && !containsForbidden) {
                    return line
                }
            }
        }
        return null
    }

    private fun findHeadline(lines: List<String>): String? {
        val keywords = listOf("разработчик", "инженер", "developer", "engineer", "архитектор", "backend", "devops", "lead")
        for (line in lines.take(12)) {
            val lower = line.lowercase()
            if (keywords.any { lower.contains(it) } && line.length in 5..80) {
                return line
            }
        }
        return "Backend Python разработчик"
    }

    private fun estimateGrade(text: String): String {
        return when {
            text.contains("senior") || text.contains("сеньор") || text.contains("тимлид") || text.contains("ведущий") -> "middle" // max MVP grade is middle
            text.contains("middle") || text.contains("мидл") || text.contains("3 года") || text.contains("4 года") || text.contains("5 лет") -> "middle"
            else -> "junior"
        }
    }

    private fun extractSkills(text: String): List<String> {
        val detected = mutableSetOf<String>()
        if (text.contains("python") || text.contains("питон")) detected.add("python-basics")
        if (text.contains("fastapi") || text.contains("flask") || text.contains("django") || text.contains("rest") || text.contains("http") || text.contains("api")) detected.add("http-api")
        if (text.contains("sql") || text.contains("postgres") || text.contains("postgresql") || text.contains("транзакц")) detected.add("sql-transactions")
        if (text.contains("docker") || text.contains("k8s") || text.contains("надежност") || text.contains("ci/cd") || text.contains("linux") || text.contains("git")) detected.add("reliability")
        if (text.contains("postgres") || text.contains("postgresql")) detected.add("postgres")
        if (text.contains("docker") || text.contains("контейнер")) detected.add("docker")
        if (text.contains("fastapi")) detected.add("fastapi")
        return detected.toList()
    }

    private fun extractSoftSkills(text: String): List<String> {
        val options = mapOf(
            "коммуникация" to "Коммуникация",
            "командн" to "Командная работа",
            "наставничеств" to "Наставничество",
            "ментор" to "Менторство",
            "scrum" to "Scrum/Agile",
            "agile" to "Agile",
            "критическ" to "Критическое мышление",
            "самоорганизац" to "Самоорганизация"
        )
        return options.filterKeys { text.contains(it) }.values.distinct().take(5)
    }

    private fun extractRolePreferences(text: String): List<String> {
        val detected = mutableListOf<String>()
        if (text.contains("backend") || text.contains("бэкенд")) detected.add("Backend-разработчик")
        if (text.contains("python")) detected.add("Python-инженер")
        if (text.contains("платформ") || text.contains("platform")) detected.add("Инженер платформы")
        if (detected.isEmpty()) detected.add("Backend Python разработчик")
        return detected.distinct()
    }

    private fun extractCities(text: String): List<String> {
        val knownCities = listOf("Москва", "Санкт-Петербург", "Новосибирск", "Екатеринбург", "Казань", "Нижний Новгород", "Томск", "Иннополис")
        return knownCities.filter { text.contains(it.lowercase()) }
    }
}
