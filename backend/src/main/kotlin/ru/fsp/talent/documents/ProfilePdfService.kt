package ru.fsp.talent.documents

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import ru.fsp.talent.core.Store
import ru.fsp.talent.invitations.listValue
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Component
class ProfilePdfService(
    private val store: Store,
    @Value("\${fsp.public-origin:http://localhost:5173}") private val publicOrigin: String = "http://localhost:5173",
) {
    companion object {
        private val BRAND_BLUE = floatArrayOf(0.251f, 0.184f, 1.0f)       // #402FFF
        private val TEXT_DARK = floatArrayOf(0.106f, 0.110f, 0.129f)      // #1B1C21
        private val TEXT_MUTED = floatArrayOf(0.384f, 0.396f, 0.459f)     // #626575
        private val BORDER_GRAY = floatArrayOf(0.855f, 0.863f, 0.898f)    // #DADCE5
        private val BG_CARD = floatArrayOf(0.961f, 0.961f, 0.980f)        // #F5F5FA
        private val VERIFIED_GREEN = floatArrayOf(0.086f, 0.455f, 0.298f) // #16744C
        private val VERIFIED_BG = floatArrayOf(0.918f, 0.965f, 0.937f)    // #EAF6EF
        private val DECLARED_AMBER = floatArrayOf(0.600f, 0.376f, 0.0f)   // #996000
        private val DECLARED_BG = floatArrayOf(1.0f, 0.960f, 0.875f)      // #FFF5DF
        private val WHITE = floatArrayOf(1.0f, 1.0f, 1.0f)
    }

    fun render(candidate: Map<String, Any?>, contacts: List<Map<String, String>> = emptyList()): ByteArray {
        PDDocument().use { document ->
            val regularFont = ClassPathResource("fonts/JetBrainsMono-Regular.ttf").inputStream.use {
                PDType0Font.load(document, it)
            }
            val boldFont = runCatching {
                ClassPathResource("fonts/JetBrainsMono-Bold.ttf").inputStream.use {
                    PDType0Font.load(document, it)
                }
            }.getOrDefault(regularFont)

            val renderer = PdfDocumentRenderer(document, regularFont, boldFont, publicOrigin, store)
            renderer.renderPassport(candidate, contacts)

            return ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        }
    }

    private class PdfDocumentRenderer(
        private val document: PDDocument,
        private val regularFont: PDType0Font,
        private val boldFont: PDType0Font,
        private val publicOrigin: String,
        private val store: Store,
    ) {
        private val pageWidth = PDRectangle.A4.width
        private val pageHeight = PDRectangle.A4.height
        private val marginX = 40f
        private val contentWidth = pageWidth - marginX * 2
        private val bottomMargin = 45f

        private var currentPage = PDPage(PDRectangle.A4)
        private var stream: PDPageContentStream
        private var currentY: Float = pageHeight - 36f
        private var pageNumber: Int = 1

        init {
            document.addPage(currentPage)
            stream = PDPageContentStream(document, currentPage)
            drawBrandTopBar()
        }

        fun renderPassport(candidate: Map<String, Any?>, contacts: List<Map<String, String>>) {
            try {
                drawHeader()
                drawCandidateSummaryCard(candidate)
                drawVerifiedSection(candidate)
                drawDeclaredSection(candidate)
                if (contacts.isNotEmpty()) {
                    drawContactsSection(contacts)
                }
                drawVerificationFooter(candidate)
            } finally {
                stream.close()
            }
        }

        private fun ensureSpace(neededHeight: Float, preserveHeader: Boolean = true) {
            if (currentY - neededHeight < bottomMargin) {
                stream.close()
                currentPage = PDPage(PDRectangle.A4)
                document.addPage(currentPage)
                stream = PDPageContentStream(document, currentPage)
                pageNumber++
                currentY = pageHeight - 36f
                drawBrandTopBar()
                if (preserveHeader) {
                    drawPageContinuationHeader()
                }
            }
        }

        private fun drawBrandTopBar() {
            stream.setNonStrokingColor(BRAND_BLUE[0], BRAND_BLUE[1], BRAND_BLUE[2])
            stream.addRect(0f, pageHeight - 6f, pageWidth, 6f)
            stream.fill()
        }

        private fun drawPageContinuationHeader() {
            stream.beginText()
            stream.setFont(boldFont, 8f)
            stream.setNonStrokingColor(BRAND_BLUE[0], BRAND_BLUE[1], BRAND_BLUE[2])
            stream.newLineAtOffset(marginX, currentY)
            stream.showText("ФЕДЕРАЦИЯ СПОРТИВНОГО ПРОГРАММИРОВАНИЯ РОССИИ · «ФСП · ТАЛАНТЫ»")
            stream.endText()

            val pageLabel = "Страница $pageNumber"
            val labelW = textWidth(pageLabel, regularFont, 7.5f)
            stream.beginText()
            stream.setFont(regularFont, 7.5f)
            stream.setNonStrokingColor(TEXT_MUTED[0], TEXT_MUTED[1], TEXT_MUTED[2])
            stream.newLineAtOffset(pageWidth - marginX - labelW, currentY)
            stream.showText(pageLabel)
            stream.endText()

            currentY -= 6f
            stream.setStrokingColor(BORDER_GRAY[0], BORDER_GRAY[1], BORDER_GRAY[2])
            stream.setLineWidth(0.5f)
            stream.moveTo(marginX, currentY)
            stream.lineTo(pageWidth - marginX, currentY)
            stream.stroke()
            currentY -= 14f
        }

        private fun drawHeader() {
            val dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))

            // Title line 1: Federation name
            stream.beginText()
            stream.setFont(boldFont, 11f)
            stream.setNonStrokingColor(BRAND_BLUE[0], BRAND_BLUE[1], BRAND_BLUE[2])
            stream.newLineAtOffset(marginX, currentY)
            stream.showText("ФЕДЕРАЦИЯ СПОРТИВНОГО ПРОГРАММИРОВАНИЯ РОССИИ")
            stream.endText()

            val dateText = "Выписка от $dateStr"
            val dateW = textWidth(dateText, regularFont, 8f)
            stream.beginText()
            stream.setFont(regularFont, 8f)
            stream.setNonStrokingColor(TEXT_MUTED[0], TEXT_MUTED[1], TEXT_MUTED[2])
            stream.newLineAtOffset(pageWidth - marginX - dateW, currentY)
            stream.showText(dateText)
            stream.endText()
            currentY -= 13f

            // Title line 2: Platform and document title
            stream.beginText()
            stream.setFont(boldFont, 8.5f)
            stream.setNonStrokingColor(TEXT_DARK[0], TEXT_DARK[1], TEXT_DARK[2])
            stream.newLineAtOffset(marginX, currentY)
            stream.showText("ПЛАТФОРМА «ФСП · ТАЛАНТЫ» · ЦИФРОВОЙ ПАСПОРТ КАНДИДАТА")
            stream.endText()
            currentY -= 8f

            // Separator rule
            stream.setStrokingColor(BORDER_GRAY[0], BORDER_GRAY[1], BORDER_GRAY[2])
            stream.setLineWidth(0.75f)
            stream.moveTo(marginX, currentY)
            stream.lineTo(pageWidth - marginX, currentY)
            stream.stroke()
            currentY -= 14f
        }

        private fun drawCandidateSummaryCard(candidate: Map<String, Any?>) {
            val name = candidate["display_name"]?.toString()?.ifBlank { "Кандидат платформы" } ?: "Кандидат платформы"
            val spec = candidate["specialization_id"]?.toString() ?: "не указана"
            val declaredGrade = candidate["declared_grade"]?.toString() ?: "не указан"

            val qualification = candidate["qualification"] as? Map<*, *>
            val verifiedGrade = qualification?.get("grade")?.toString() ?: candidate["verified_grade"]?.toString()

            val allowedModes = stringList(candidate["allowed_work_modes"]).joinToString(", ").ifBlank { "не указаны" }
            val cities = stringList(candidate["cities"]).joinToString(", ").ifBlank { "не указаны" }
            val maxHours = candidate["max_weekly_hours"]?.toString()?.let { "$it ч/нед." } ?: "не указана"

            val headline = candidate["headline"]?.toString()?.takeIf(String::isNotBlank)
            val summary = candidate["summary"]?.toString()?.takeIf(String::isNotBlank)

            // Calculate card height dynamically
            var estimatedHeight = 72f
            if (headline != null) estimatedHeight += 16f
            if (summary != null) estimatedHeight += 26f

            ensureSpace(estimatedHeight + 10f)

            val cardTop = currentY
            val cardBottom = cardTop - estimatedHeight

            // Background & border
            drawFilledRoundedRect(marginX, cardBottom, contentWidth, estimatedHeight, BG_CARD, BORDER_GRAY)

            var textY = cardTop - 18f

            // Candidate Name
            stream.beginText()
            stream.setFont(boldFont, 14f)
            stream.setNonStrokingColor(TEXT_DARK[0], TEXT_DARK[1], TEXT_DARK[2])
            stream.newLineAtOffset(marginX + 14f, textY)
            stream.showText(cleanText(name, boldFont))
            stream.endText()

            // Specialization
            val specText = "Специализация: $spec"
            val specW = textWidth(specText, regularFont, 8.5f)
            stream.beginText()
            stream.setFont(regularFont, 8.5f)
            stream.setNonStrokingColor(TEXT_MUTED[0], TEXT_MUTED[1], TEXT_MUTED[2])
            stream.newLineAtOffset(pageWidth - marginX - 14f - specW, textY + 2f)
            stream.showText(cleanText(specText, regularFont))
            stream.endText()

            textY -= 18f

            // Grades Badges Row
            if (verifiedGrade != null) {
                val badgeText = "Подтвержденный грейд: $verifiedGrade"
                val bW = textWidth(badgeText, boldFont, 8f) + 12f
                drawPillBadge(marginX + 14f, textY - 3f, bW, 14f, VERIFIED_BG, VERIFIED_GREEN)
                stream.beginText()
                stream.setFont(boldFont, 8f)
                stream.setNonStrokingColor(VERIFIED_GREEN[0], VERIFIED_GREEN[1], VERIFIED_GREEN[2])
                stream.newLineAtOffset(marginX + 20f, textY)
                stream.showText(badgeText)
                stream.endText()

                val declText = "Заявленный: $declaredGrade"
                val dW = textWidth(declText, regularFont, 8f) + 12f
                drawPillBadge(marginX + 20f + bW, textY - 3f, dW, 14f, DECLARED_BG, DECLARED_AMBER)
                stream.beginText()
                stream.setFont(regularFont, 8f)
                stream.setNonStrokingColor(DECLARED_AMBER[0], DECLARED_AMBER[1], DECLARED_AMBER[2])
                stream.newLineAtOffset(marginX + 26f + bW, textY)
                stream.showText(declText)
                stream.endText()
            } else {
                val declText = "Заявленный грейд: $declaredGrade (без верификации)"
                val dW = textWidth(declText, regularFont, 8f) + 12f
                drawPillBadge(marginX + 14f, textY - 3f, dW, 14f, DECLARED_BG, DECLARED_AMBER)
                stream.beginText()
                stream.setFont(regularFont, 8f)
                stream.setNonStrokingColor(DECLARED_AMBER[0], DECLARED_AMBER[1], DECLARED_AMBER[2])
                stream.newLineAtOffset(marginX + 20f, textY)
                stream.showText(declText)
                stream.endText()
            }

            textY -= 17f

            // Conditions row
            val conditionsText = "Формат: $allowedModes  |  Города: $cities  |  Нагрузка: $maxHours"
            stream.beginText()
            stream.setFont(regularFont, 8f)
            stream.setNonStrokingColor(TEXT_MUTED[0], TEXT_MUTED[1], TEXT_MUTED[2])
            stream.newLineAtOffset(marginX + 14f, textY)
            stream.showText(cleanText(conditionsText, regularFont))
            stream.endText()

            textY -= 14f

            if (headline != null) {
                stream.beginText()
                stream.setFont(boldFont, 8f)
                stream.setNonStrokingColor(TEXT_DARK[0], TEXT_DARK[1], TEXT_DARK[2])
                stream.newLineAtOffset(marginX + 14f, textY)
                stream.showText("Заголовок: " + cleanText(headline, boldFont))
                stream.endText()
                textY -= 14f
            }

            if (summary != null) {
                val wrapped = wrap(cleanText("О кандидате: $summary", regularFont), regularFont, 7.5f, contentWidth - 28f)
                wrapped.take(2).forEach { line ->
                    stream.beginText()
                    stream.setFont(regularFont, 7.5f)
                    stream.setNonStrokingColor(TEXT_MUTED[0], TEXT_MUTED[1], TEXT_MUTED[2])
                    stream.newLineAtOffset(marginX + 14f, textY)
                    stream.showText(line)
                    stream.endText()
                    textY -= 10f
                }
            }

            currentY = cardBottom - 14f
        }

        private fun drawVerifiedSection(candidate: Map<String, Any?>) {
            ensureSpace(60f)

            // Section Banner: Verified by Test (Green theme)
            drawSectionBanner(
                title = "ПОДТВЕРЖДЕНО ТЕСТОМ (НЕЗАВИСИМАЯ ОЦЕНКА ФСП)",
                bg = VERIFIED_BG,
                accent = VERIFIED_GREEN,
            )

            // Qualification test details
            val qualification = candidate["qualification"] as? Map<*, *>
            if (qualification != null) {
                val grade = qualification["grade"] ?: "не определен"
                val policyId = qualification["policy_id"] ?: "fsp-assessment"
                val policyVer = qualification["policy_version"] ?: "1.0"
                val bankVer = qualification["bank_version"] ?: "v1"
                val assessedAt = qualification["assessed_at"] ?: "дата не указана"
                val isDemo = qualification["demo"] == true

                val lines = listOf(
                    "Подтвержденный грейд: $grade",
                    "Метод: серверная оценка; политика $policyId $policyVer",
                    "Версия формы: $bankVer; дата: $assessedAt",
                    if (isDemo) "Оценка помечена как демонстрационная" else "Оценка не помечена как демонстрационная",
                )
                drawBulletBlock(lines, VERIFIED_GREEN)
            } else {
                drawTextLine("Подтвержденная квалификация: нет данных независимого тестирования", TEXT_MUTED, regularFont, 8f)
            }

            // Test verified skills
            val evidence = store.listValue(candidate["competency_evidence"])
            val verifiedSkills = evidence.filter { it["status"]?.toString() == "test_verified" }

            currentY -= 4f
            drawTextLine("Навыки, подтвержденные тестом платформы ФСП:", TEXT_DARK, boldFont, 8.5f)

            if (verifiedSkills.isNotEmpty()) {
                val skillLines = verifiedSkills.map { proof ->
                    val skillId = proof["skill_id"]?.toString() ?: "навык"
                    val source = proof["source"]?.toString() ?: "оценка ФСП"
                    val verifiedAt = proof["verified_at"]?.toString()?.let { "; дата: $it" }.orEmpty()
                    "[✓] $skillId: подтверждено тестом (источник: $source$verifiedAt)"
                }
                drawBulletBlock(skillLines, VERIFIED_GREEN)
            } else {
                drawTextLine("  • Нет подтвержденных тестом навыков на момент выписки", TEXT_MUTED, regularFont, 8f)
            }

            // FSP Competitions evidence
            val fspEvidence = store.listValue(candidate["fsp_evidence"])
            currentY -= 4f
            drawTextLine("Данные ФСП (соревнования и турниры):", TEXT_DARK, boldFont, 8.5f)

            if (fspEvidence.isNotEmpty()) {
                val fspLines = mutableListOf<String>()
                fspEvidence.forEach { item ->
                    val event = item["event_title"]?.toString() ?: "Турнир ФСП"
                    val date = item["event_date"]?.toString() ?: "не указана"
                    val result = item["result"]?.toString() ?: "участник"
                    val teamResult = item["team_result"]?.toString() ?: "нет данных"
                    val confirmed = item["membership_confirmed"] ?: "нет"
                    val src = item["source"] ?: "реестр ФСП"
                    val validity = item["validity"] ?: "valid"
                    fspLines += "• $event; дата: $date; результат: $result"
                    fspLines += "  Командный результат: $teamResult; участие подтверждено: $confirmed"
                    fspLines += "  Источник: $src; статус: $validity"
                }
                drawBulletBlock(fspLines, TEXT_DARK)
                drawTextLine("Записи ФСП показаны с указанным источником и не означают официальную проверку.", TEXT_MUTED, regularFont, 7.5f)
            } else {
                drawTextLine("  • Нет доступных записей участия в турнирах ФСП", TEXT_MUTED, regularFont, 8f)
            }

            currentY -= 12f
        }

        private fun drawDeclaredSection(candidate: Map<String, Any?>) {
            ensureSpace(70f)

            // Section Banner: Candidate Declared (Amber theme)
            drawSectionBanner(
                title = "ЗАЯВЛЕНО КАНДИДАТОМ (БЕЗ НЕЗАВИСИМОЙ ПРОВЕРКИ)",
                bg = DECLARED_BG,
                accent = DECLARED_AMBER,
            )

            drawTextLine(
                "Сведения указаны кандидатом при заполнении профиля, объективная проверка тестом не проводилась.",
                DECLARED_AMBER,
                regularFont,
                7.5f,
            )
            currentY -= 4f

            // Profile attributes with CRITICAL test assertions
            val industry = candidate["industry"]?.toString()?.takeIf(String::isNotBlank)
            if (industry != null) {
                drawTextLine("Отрасль (заявлено кандидатом): $industry", TEXT_DARK, regularFont, 8.5f)
            }

            val roles = stringList(candidate["role_preferences"])
            if (roles.isNotEmpty()) {
                drawTextLine("Рассматриваемые роли (заявлено кандидатом): ${roles.joinToString(", ")}", TEXT_DARK, regularFont, 8.5f)
            }

            val softSkills = stringList(candidate["soft_skills"])
            if (softSkills.isNotEmpty()) {
                drawTextLine("Гибкие навыки (заявлено кандидатом): ${softSkills.joinToString(", ")}", TEXT_DARK, regularFont, 8.5f)
            }

            // Declared skills list
            val evidence = store.listValue(candidate["competency_evidence"])
            val declaredSkills = evidence.filter { it["status"]?.toString() != "test_verified" }

            if (declaredSkills.isNotEmpty()) {
                currentY -= 4f
                drawTextLine("Навыки по заявлению кандидата (самооценка):", TEXT_DARK, boldFont, 8.5f)
                val declaredLines = declaredSkills.map { proof ->
                    val skillId = proof["skill_id"]?.toString() ?: "навык"
                    val source = proof["source"]?.toString() ?: "профиль"
                    "• $skillId: заявлено кандидатом ($source)"
                }
                drawBulletBlock(declaredLines, DECLARED_AMBER)
            }

            // Experience entries
            val experience = store.listValue(candidate["experience_entries"])
            if (experience.isNotEmpty()) {
                currentY -= 4f
                ensureSpace(40f)
                drawTextLine("Опыт по заявлению кандидата:", TEXT_DARK, boldFont, 8.5f)
                experience.forEach { entry ->
                    ensureSpace(24f)
                    val role = entry["role"]?.toString() ?: "Роль не указана"
                    val company = entry["company"]?.toString()?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                    val dates = listOfNotNull(entry["start_month"]?.toString(), entry["end_month"]?.toString())
                        .filter(String::isNotBlank)
                        .joinToString(" — ")
                        .takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()

                    drawTextLine("• $role$company$dates", TEXT_DARK, boldFont, 8f)
                    entry["description"]?.toString()?.takeIf(String::isNotBlank)?.let { desc ->
                        val wrapped = wrap(cleanText(desc, regularFont), regularFont, 7.5f, contentWidth - 16f)
                        wrapped.forEach { line ->
                            drawTextLine("  $line", TEXT_MUTED, regularFont, 7.5f)
                        }
                    }
                }
            }

            // Project entries
            val projects = store.listValue(candidate["project_entries"])
            if (projects.isNotEmpty()) {
                currentY -= 4f
                ensureSpace(35f)
                drawTextLine("Проекты по заявлению кандидата:", TEXT_DARK, boldFont, 8.5f)
                projects.forEach { entry ->
                    ensureSpace(24f)
                    val title = entry["title"]?.toString()?.ifBlank { "Проект" } ?: "Проект"
                    drawTextLine("• $title", TEXT_DARK, boldFont, 8f)
                    entry["description"]?.toString()?.takeIf(String::isNotBlank)?.let { desc ->
                        val wrapped = wrap(cleanText(desc, regularFont), regularFont, 7.5f, contentWidth - 16f)
                        wrapped.forEach { line ->
                            drawTextLine("  $line", TEXT_MUTED, regularFont, 7.5f)
                        }
                    }
                    entry["url"]?.toString()?.takeIf(String::isNotBlank)?.let { url ->
                        drawTextLine("  Ссылка: $url", BRAND_BLUE, regularFont, 7.5f)
                    }
                }
            }

            currentY -= 12f
        }

        private fun drawContactsSection(contacts: List<Map<String, String>>) {
            ensureSpace(30f + contacts.size * 12f)
            drawTextLine("Контакты, доступные по текущему согласию:", TEXT_DARK, boldFont, 8.5f)
            contacts.forEach { channel ->
                val type = channel["type"] ?: "Контакт"
                val value = channel["value"] ?: "—"
                drawTextLine("• $type: $value", TEXT_DARK, regularFont, 8f)
            }
            currentY -= 12f
        }

        private fun drawVerificationFooter(candidate: Map<String, Any?>) {
            val candidateId = candidate["id"]?.toString()
            val verificationUrl = if (!candidateId.isNullOrBlank()) {
                "${publicOrigin.trimEnd('/')}/candidates/$candidateId"
            } else {
                publicOrigin
            }

            val footerHeight = 68f
            ensureSpace(footerHeight + 10f, preserveHeader = false)

            val boxY = currentY - footerHeight
            // Background box with subtle border
            drawFilledRoundedRect(marginX, boxY, contentWidth, footerHeight, BG_CARD, BORDER_GRAY)

            // Vector QR Code (54x54pt)
            val qrSize = 54f
            val qrX = marginX + 8f
            val qrY = boxY + 7f
            drawVectorQrCode(verificationUrl, qrX, qrY, qrSize)

            // Verification Text alongside QR
            val textX = qrX + qrSize + 14f
            var vTextY = boxY + footerHeight - 16f

            stream.beginText()
            stream.setFont(boldFont, 8.5f)
            stream.setNonStrokingColor(BRAND_BLUE[0], BRAND_BLUE[1], BRAND_BLUE[2])
            stream.newLineAtOffset(textX, vTextY)
            stream.showText("ПРОВЕРКА ПОДЛИННОСТИ ЦИФРОВОГО ПАСПОРТА")
            stream.endText()

            vTextY -= 12f
            stream.beginText()
            stream.setFont(regularFont, 7.5f)
            stream.setNonStrokingColor(TEXT_DARK[0], TEXT_DARK[1], TEXT_DARK[2])
            stream.newLineAtOffset(textX, vTextY)
            stream.showText("Отсканируйте QR-код для верификации цифрового паспорта в реестре ФСП.")
            stream.endText()

            vTextY -= 11f
            val idDisplay = if (!candidateId.isNullOrBlank()) "ID: $candidateId" else "ID: тестовый профиль кандидата"
            stream.beginText()
            stream.setFont(regularFont, 7.5f)
            stream.setNonStrokingColor(TEXT_MUTED[0], TEXT_MUTED[1], TEXT_MUTED[2])
            stream.newLineAtOffset(textX, vTextY)
            stream.showText(cleanText(idDisplay, regularFont))
            stream.endText()

            vTextY -= 11f
            stream.beginText()
            stream.setFont(regularFont, 7f)
            stream.setNonStrokingColor(BRAND_BLUE[0], BRAND_BLUE[1], BRAND_BLUE[2])
            stream.newLineAtOffset(textX, vTextY)
            stream.showText(cleanText(verificationUrl, regularFont))
            stream.endText()

            currentY = boxY - 10f
        }

        private fun drawVectorQrCode(text: String, x: Float, y: Float, displaySize: Float) {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 0,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 29, 29, hints)
            val moduleW = displaySize / matrix.width
            val moduleH = displaySize / matrix.height

            // White background padding
            stream.setNonStrokingColor(WHITE[0], WHITE[1], WHITE[2])
            stream.addRect(x - 2f, y - 2f, displaySize + 4f, displaySize + 4f)
            stream.fill()

            // Dark QR modules
            stream.setNonStrokingColor(TEXT_DARK[0], TEXT_DARK[1], TEXT_DARK[2])
            for (row in 0 until matrix.height) {
                for (col in 0 until matrix.width) {
                    if (matrix.get(col, row)) {
                        val modX = x + col * moduleW
                        val modY = y + (matrix.height - 1 - row) * moduleH
                        stream.addRect(modX, modY, moduleW, moduleH)
                    }
                }
            }
            stream.fill()
        }

        private fun drawSectionBanner(title: String, bg: FloatArray, accent: FloatArray) {
            val h = 18f
            val top = currentY
            val bottom = top - h

            stream.setNonStrokingColor(bg[0], bg[1], bg[2])
            stream.addRect(marginX, bottom, contentWidth, h)
            stream.fill()

            // Accent left bar (3pt)
            stream.setNonStrokingColor(accent[0], accent[1], accent[2])
            stream.addRect(marginX, bottom, 3.5f, h)
            stream.fill()

            // Banner border
            stream.setStrokingColor(accent[0], accent[1], accent[2])
            stream.setLineWidth(0.5f)
            stream.addRect(marginX, bottom, contentWidth, h)
            stream.stroke()

            stream.beginText()
            stream.setFont(boldFont, 8.5f)
            stream.setNonStrokingColor(accent[0], accent[1], accent[2])
            stream.newLineAtOffset(marginX + 8f, bottom + 5f)
            stream.showText(cleanText(title, boldFont))
            stream.endText()

            currentY = bottom - 9f
        }

        private fun drawTextLine(text: String, color: FloatArray, font: PDType0Font, size: Float) {
            val cleaned = cleanText(text, font)
            val wrapped = wrap(cleaned, font, size, contentWidth)
            wrapped.forEach { line ->
                ensureSpace(size + 4f)
                stream.beginText()
                stream.setFont(font, size)
                stream.setNonStrokingColor(color[0], color[1], color[2])
                stream.newLineAtOffset(marginX, currentY)
                stream.showText(line)
                stream.endText()
                currentY -= (size + 4f)
            }
        }

        private fun drawBulletBlock(lines: List<String>, color: FloatArray) {
            lines.forEach { rawLine ->
                val cleaned = cleanText(rawLine, regularFont)
                val wrapped = wrap(cleaned, regularFont, 8f, contentWidth - 10f)
                wrapped.forEachIndexed { idx, line ->
                    ensureSpace(12f)
                    stream.beginText()
                    stream.setFont(regularFont, 8f)
                    stream.setNonStrokingColor(color[0], color[1], color[2])
                    val indent = if (idx == 0) marginX + 6f else marginX + 16f
                    stream.newLineAtOffset(indent, currentY)
                    stream.showText(line)
                    stream.endText()
                    currentY -= 11.5f
                }
            }
        }

        private fun drawFilledRoundedRect(x: Float, y: Float, w: Float, h: Float, bg: FloatArray, stroke: FloatArray) {
            stream.setNonStrokingColor(bg[0], bg[1], bg[2])
            stream.addRect(x, y, w, h)
            stream.fill()

            stream.setStrokingColor(stroke[0], stroke[1], stroke[2])
            stream.setLineWidth(0.5f)
            stream.addRect(x, y, w, h)
            stream.stroke()
        }

        private fun drawPillBadge(x: Float, y: Float, w: Float, h: Float, bg: FloatArray, stroke: FloatArray) {
            stream.setNonStrokingColor(bg[0], bg[1], bg[2])
            stream.addRect(x, y, w, h)
            stream.fill()

            stream.setStrokingColor(stroke[0], stroke[1], stroke[2])
            stream.setLineWidth(0.5f)
            stream.addRect(x, y, w, h)
            stream.stroke()
        }

        private fun textWidth(text: String, font: PDType0Font, fontSize: Float): Float =
            runCatching { font.getStringWidth(cleanText(text, font)) * fontSize / 1000f }.getOrDefault(0f)

        private fun cleanText(value: String, font: PDType0Font): String = buildString {
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
                if (line.isNotEmpty() && textWidth(next, font, fontSize) > maxWidth) {
                    result += line
                    line = word
                } else {
                    line = next
                }
            }
            if (line.isNotEmpty()) result += line
            return result
        }

        private fun stringList(value: Any?): List<String> =
            (value as? Collection<*>)?.mapNotNull { it?.toString() }.orEmpty()
    }
}
