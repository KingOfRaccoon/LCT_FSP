package ru.fsp.talent.profiles

import jakarta.servlet.http.HttpServletRequest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import ru.fsp.talent.consents.ConsentService
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import java.io.IOException
import java.io.Writer
import java.util.UUID

@RestController
@RequestMapping("/api/v1/me/resume")
class ResumeImportController(
    private val auth: AuthAccess,
    private val consents: ConsentService,
    private val imports: ResumeImportService,
    private val contracts: Contracts,
) {
    @PostMapping("/import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun import(
        request: HttpServletRequest,
        @RequestPart("file") file: MultipartFile,
    ): Map<String, Any?> {
        val candidate = auth.candidate(request)
        auth.verified(candidate)
        consents.requireProcessing(candidate.id)
        return contracts.envelope(imports.createDraft(file.bytes))
    }
}

@Component
class ResumeImportService(private val parser: ResumeImportParser) {
    fun createDraft(bytes: ByteArray): Map<String, Any?> {
        if (bytes.size > MAX_PDF_BYTES) {
            throw ApiException(413, "RESUME_IMPORT_TOO_LARGE", "PDF must be no larger than 5 MiB", "file")
        }
        if (bytes.isEmpty()) invalidPdf()

        val text = try {
            Loader.loadPDF(bytes).use { document ->
                if (document.isEncrypted) encryptedPdf()
                if (document.numberOfPages > MAX_PDF_PAGES) {
                    throw ApiException(422, "RESUME_IMPORT_TOO_MANY_PAGES", "PDF must contain no more than 20 pages", "file")
                }
                val writer = BoundedTextWriter(MAX_EXTRACTED_CHARACTERS)
                PDFTextStripper().writeText(document, writer)
                writer.toString()
            }
        } catch (_: InvalidPasswordException) {
            encryptedPdf()
        } catch (_: ExtractedTextTooLargeException) {
            throw ApiException(422, "RESUME_IMPORT_TEXT_TOO_LARGE", "PDF text exceeds the safe extraction limit", "file")
        } catch (error: ApiException) {
            throw error
        } catch (_: IOException) {
            invalidPdf()
        } catch (_: RuntimeException) {
            invalidPdf()
        }

        if (text.isBlank()) {
            throw ApiException(422, "RESUME_IMPORT_NO_TEXT", "PDF contains no extractable text; scanned documents are not supported", "file")
        }
        return parser.parse(text).asMap()
    }

    private fun invalidPdf(): Nothing =
        throw ApiException(400, "RESUME_IMPORT_INVALID_PDF", "The uploaded file is not a readable PDF", "file")

    private fun encryptedPdf(): Nothing =
        throw ApiException(400, "RESUME_IMPORT_ENCRYPTED", "Password-protected PDFs are not supported", "file")

    private class BoundedTextWriter(private val limit: Int) : Writer() {
        private val text = StringBuilder()

        override fun write(buffer: CharArray, offset: Int, length: Int) {
            if (length > limit - text.length) throw ExtractedTextTooLargeException()
            text.append(buffer, offset, length)
        }

        override fun flush() = Unit
        override fun close() = Unit
        override fun toString(): String = text.toString()
    }

    private class ExtractedTextTooLargeException : IOException()

    private companion object {
        const val MAX_PDF_BYTES = 5 * 1024 * 1024
        const val MAX_PDF_PAGES = 20
        const val MAX_EXTRACTED_CHARACTERS = 200_000
    }
}

data class ResumeImportDraft(
    val candidatePatch: Map<String, Any?>,
    val suggestions: Map<String, Any?>,
    val warnings: List<String>,
    val needsReview: Boolean = true,
) {
    fun asMap(): Map<String, Any?> = mapOf(
        "candidate_patch" to candidatePatch,
        "suggestions" to suggestions,
        "warnings" to warnings,
        "needs_review" to needsReview,
    )
}

@Component
class ResumeImportParser {
    fun parse(text: String): ResumeImportDraft {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val candidatePatch = linkedMapOf<String, Any?>()
        findName(lines)?.let { candidatePatch["display_name"] = it }
        labeledValue(lines, HEADLINE_LABELS)?.let { candidatePatch["headline"] = it }
        labeledValue(lines, CITY_LABELS)?.let { city -> candidatePatch["cities"] = listOf(city) }

        val skills = extractSkills(lines)
        if (skills.isNotEmpty()) candidatePatch["skills"] = skills

        val experiences = extractExperience(lines)
        if (experiences.isNotEmpty()) candidatePatch["experience_entries"] = experiences

        val contacts = extractContacts(text)
        return ResumeImportDraft(
            candidatePatch = candidatePatch,
            suggestions = mapOf("contacts" to contacts),
            warnings = listOf(
                "Проверьте все распознанные сведения перед переносом в профиль.",
                "Контактные данные показаны отдельно и не будут добавлены в профиль автоматически.",
            ),
        )
    }

    private fun findName(lines: List<String>): String? =
        labeledValue(lines, NAME_LABELS)?.takeIf(NAME_REGEX::matches)
            ?: lines.firstOrNull { line ->
                !hasLabel(line) && !EMAIL_REGEX.containsMatchIn(line) && !PHONE_REGEX.containsMatchIn(line) &&
                    NAME_REGEX.matches(line) && line.split(Regex("\\s+")).all { it.first().isUpperCase() } &&
                    !line.containsAnyIgnoreCase(RESUME_HEADINGS)
            }

    private fun labeledValue(lines: List<String>, labels: Set<String>): String? = lines.firstNotNullOfOrNull { line ->
        val match = LABELED_VALUE_REGEX.matchEntire(line) ?: return@firstNotNullOfOrNull null
        match.groupValues[1].normalizedLabel().takeIf(labels::contains)?.let { match.groupValues[2].trim() }
            ?.takeIf { it.isNotBlank() && it.length <= 120 }
    }

    private fun extractSkills(lines: List<String>): List<String> {
        val value = labeledValue(lines, SKILL_LABELS) ?: return emptyList()
        val normalized = value.lowercase().replace('ё', 'е')
        return SKILL_ALIASES.filter { (_, aliases) -> aliases.any { alias -> aliasRegex(alias).containsMatchIn(normalized) } }
            .map { it.first }
            .distinct()
            .take(7)
    }

    private fun extractContacts(text: String): List<Map<String, String>> = buildList {
        EMAIL_REGEX.findAll(text).map { it.value.trim().trimEnd('.', ',', ';', ')', ']') }.distinct().take(5)
            .forEach { add(mapOf("type" to "email", "value" to it)) }

        val phones = text.lineSequence().flatMap { line ->
            val label = LABELED_VALUE_REGEX.matchEntire(line)
            val labeled = label?.takeIf { it.groupValues[1].normalizedLabel() in PHONE_LABELS }
                ?.groupValues?.get(2)
            val candidates = buildList {
                if (labeled != null) PHONE_REGEX.findAll(labeled).forEach { add(it.value) }
                INTERNATIONAL_PHONE_REGEX.findAll(line).forEach { add(it.value) }
            }
            candidates.asSequence()
                .filterNot(DATE_RANGE_REGEX::containsMatchIn)
                .filter { candidate -> candidate.count(Char::isDigit) in 8..15 }
                .map { it.trim().replace(Regex("\\s+"), " ") }
        }.distinct().take(5)
        phones.forEach { add(mapOf("type" to "phone", "value" to it)) }
    }

    private fun extractExperience(lines: List<String>): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        lines.forEachIndexed { index, line ->
            if (!EXPERIENCE_HEADING_REGEX.matches(line)) return@forEachIndexed
            val companyRole = lines.getOrNull(index + 1)?.let(COMPANY_ROLE_REGEX::matchEntire) ?: return@forEachIndexed
            val datesLine = lines.getOrNull(index + 2) ?: return@forEachIndexed
            val dateValue = LABELED_VALUE_REGEX.matchEntire(datesLine)?.let { match ->
                match.takeIf { match.groupValues[1].normalizedLabel() in EMPLOYMENT_DATE_LABELS }?.groupValues?.get(2)
            } ?: return@forEachIndexed
            val dates = parseEmploymentDates(dateValue) ?: return@forEachIndexed
            val company = companyRole.groupValues[1].trim().takeIf(String::isNotBlank) ?: return@forEachIndexed
            val role = companyRole.groupValues[2].trim().takeIf(String::isNotBlank) ?: return@forEachIndexed
            result += mapOf(
                "id" to UUID.randomUUID().toString(),
                "company" to company,
                "role" to role,
                "description" to "",
                "start_month" to dates.first,
                "end_month" to dates.second,
                "skill_ids" to emptyList<String>(),
            )
        }
        return result.take(10)
    }

    private fun parseEmploymentDates(value: String): Pair<String, String?>? {
        val lowered = value.lowercase().replace('ё', 'е')
        val matches = MONTH_DATE_REGEX.findAll(value).toList()
        if (matches.isEmpty()) return null
        val start = matches.first().value.toEmploymentMonth() ?: return null
        val openEnded = PRESENT_REGEX.containsMatchIn(lowered)
        if (matches.size == 1 && !openEnded) return null
        val end = if (openEnded) null else matches.last().value.toEmploymentMonth() ?: return null
        if (end != null && start > end) return null
        return start to end
    }

    private fun String.toEmploymentMonth(): String? {
        val value = trim().lowercase().replace('ё', 'е')
        val iso = ISO_MONTH_REGEX.matchEntire(value)
        if (iso != null) {
            val month = iso.groupValues[2].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
            return "%04d-%02d".format(iso.groupValues[1].toInt(), month)
        }
        val numeric = NUMERIC_MONTH_REGEX.matchEntire(value)
        if (numeric != null) {
            val month = numeric.groupValues[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
            return "%04d-%02d".format(numeric.groupValues[2].toInt(), month)
        }
        val named = NAMED_MONTH_REGEX.matchEntire(value) ?: return null
        val month = MONTHS[named.groupValues[1]] ?: return null
        val year = named.groupValues[2].toIntOrNull() ?: return null
        return "%04d-%02d".format(year, month)
    }

    private fun String.normalizedLabel() = trim().lowercase().replace('ё', 'е').replace(Regex("[\\s_]+"), " ")
    private fun String.containsAnyIgnoreCase(values: Set<String>) = values.any { contains(it, ignoreCase = true) }
    private fun hasLabel(line: String) = LABELED_VALUE_REGEX.matchEntire(line) != null

    private fun aliasRegex(alias: String): Regex = Regex("(?<![\\p{L}\\d])${Regex.escape(alias)}(?![\\p{L}\\d])", RegexOption.IGNORE_CASE)

    private companion object {
        val NAME_LABELS = setOf("фио", "полное имя", "name", "full name")
        val HEADLINE_LABELS = setOf("должность", "желаемая должность", "позиция", "профессия", "headline", "position", "desired position", "role")
        val CITY_LABELS = setOf("город", "город проживания", "место проживания", "city", "location")
        val SKILL_LABELS = setOf("навыки", "ключевые навыки", "технологии", "skills", "key skills", "technologies")
        val EMPLOYMENT_DATE_LABELS = setOf("период работы", "период", "даты работы", "work period", "employment period", "period", "dates")
        val PHONE_LABELS = setOf("телефон", "тел.", "мобильный", "мобильный телефон", "моб. телефон", "phone", "phone number", "telephone", "mobile", "mobile phone", "cell", "cell phone")
        val RESUME_HEADINGS = setOf("резюме", "cv", "resume")
        val SKILL_ALIASES = listOf(
            "python-basics" to listOf("python"),
            "http-api" to listOf("rest api", "http api", "rest", "http"),
            "sql-transactions" to listOf("sql", "транзакции sql", "sql transactions"),
            "reliability" to listOf("reliability", "надёжность", "надежность"),
            "postgres" to listOf("postgresql", "postgres", "postgre sql"),
            "docker" to listOf("docker"),
            "fastapi" to listOf("fastapi", "fast api"),
        )
        val LABELED_VALUE_REGEX = Regex("^\\s*([^:：]{1,48})\\s*[:：]\\s*(.*?)\\s*$")
        val NAME_REGEX = Regex("^[\\p{L}][\\p{L}'’.-]*(?:\\s+[\\p{L}][\\p{L}'’.-]*){1,3}$")
        val EMAIL_REGEX = Regex("(?i)(?<![\\p{L}\\d._%+-])[\\p{L}\\d._%+-]{1,64}@[\\p{L}\\d.-]+\\.[\\p{L}]{2,}(?![\\p{L}\\d._%+-])")
        val PHONE_REGEX = Regex("(?<![\\p{L}\\d])\\+?\\d[\\d\\s().-]*\\d(?![\\p{L}\\d])")
        val INTERNATIONAL_PHONE_REGEX = Regex("(?<![\\p{L}\\d])\\+\\s*\\d[\\d\\s().-]*\\d(?![\\p{L}\\d])")
        val DATE_RANGE_REGEX = Regex("\\b\\d{4}[-/]\\d{1,2}\\s*[-–—]\\s*\\d{4}[-/]\\d{1,2}\\b")
        val EXPERIENCE_HEADING_REGEX = Regex("^(опыт работы|опыт|experience|work experience)$", RegexOption.IGNORE_CASE)
        val COMPANY_ROLE_REGEX = Regex("^(.{2,100}?)\\s+(?:—|–|\\|)\\s+(.{2,100})$")
        val MONTH_DATE_REGEX = Regex("(?i)(?:\\d{4}[-/](?:0?[1-9]|1[0-2])|(?:0?[1-9]|1[0-2])[./-]\\d{4}|(?:январ[ьяе]|феврал[ьяе]|март[а]?|апрел[ьяе]|ма[йяе]|июн[ьяе]|июл[ьяе]|август[а]?|сентябр[ьяе]|октябр[ьяе]|ноябр[ьяе]|декабр[ьяе]|january|february|march|april|may|june|july|august|september|october|november|december)\\s+\\d{4})")
        val ISO_MONTH_REGEX = Regex("^(\\d{4})[-/](\\d{1,2})$")
        val NUMERIC_MONTH_REGEX = Regex("^(\\d{1,2})[./-](\\d{4})$")
        val NAMED_MONTH_REGEX = Regex("^([\\p{L}]+)\\s+(\\d{4})$", RegexOption.IGNORE_CASE)
        val PRESENT_REGEX = Regex("(?i)(настояще[ея] время|по настоящее время|present|current|по настоящее|по н\\.?в\\.?)")
        val MONTHS = mapOf(
            "январь" to 1, "января" to 1, "январе" to 1, "january" to 1,
            "февраль" to 2, "февраля" to 2, "феврале" to 2, "february" to 2,
            "март" to 3, "марта" to 3, "марте" to 3, "march" to 3,
            "апрель" to 4, "апреля" to 4, "апреле" to 4, "april" to 4,
            "май" to 5, "мая" to 5, "мае" to 5, "may" to 5,
            "июнь" to 6, "июня" to 6, "июне" to 6, "june" to 6,
            "июль" to 7, "июля" to 7, "июле" to 7, "july" to 7,
            "август" to 8, "августа" to 8, "августе" to 8, "august" to 8,
            "сентябрь" to 9, "сентября" to 9, "сентябре" to 9, "september" to 9,
            "октябрь" to 10, "октября" to 10, "октябре" to 10, "october" to 10,
            "ноябрь" to 11, "ноября" to 11, "ноябре" to 11, "november" to 11,
            "декабрь" to 12, "декабря" to 12, "декабре" to 12, "december" to 12,
        )
    }
}
