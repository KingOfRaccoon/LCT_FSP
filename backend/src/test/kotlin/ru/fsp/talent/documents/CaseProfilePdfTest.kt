package ru.fsp.talent.documents

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import ru.fsp.talent.core.DbTestBase

class CaseProfilePdfTest : DbTestBase() {
    @Autowired private lateinit var pdf: ProfilePdfService

    @Test
    fun `standardized profile includes explicitly declared industry roles and soft skills`() {
        val bytes = pdf.render(mapOf(
            "display_name" to "Case candidate",
            "industry" to "Fintech",
            "role_preferences" to listOf("Backend engineer"),
            "soft_skills" to listOf("Clear writing"),
        ))
        val text = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }
        assertTrue(text.contains("Fintech"), text)
        assertTrue(text.contains("Backend engineer"), text)
        assertTrue(text.contains("Clear writing"), text)
        assertTrue(text.contains("заявлено кандидатом"), text)
    }
}
