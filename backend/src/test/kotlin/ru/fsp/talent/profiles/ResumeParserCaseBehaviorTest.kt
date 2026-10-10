package ru.fsp.talent.profiles

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import ru.fsp.talent.core.ApiException

class ResumeParserCaseBehaviorTest {
    private val parser = ResumeParserService()

    @Test
    fun `unknown title and grade stay empty instead of inventing backend and junior`() {
        val parsed = parser.parse(pdf("Опыт работы\nРазработка продукта\nКоммуникация"))

        assertNull(parsed.headline)
        assertNull(parsed.declaredGrade)
        assertEquals(emptyList<String>(), parsed.rolePreferences)
    }

    @Test
    fun `unsupported senior grade does not become middle`() {
        val senior = parser.parse(pdf("Senior developer Python"))

        assertNull(senior.declaredGrade)
    }

    @Test
    fun `years of experience do not produce a declared grade`() {
        val yearsOnly = parser.parse(pdf("Backend developer\nОпыт работы: 5 лет"))

        assertEquals("Backend developer", yearsOnly.headline)
        assertNull(yearsOnly.declaredGrade)
    }

    @Test
    fun `malformed PDF has a sanitized client error and retains the parser cause`() {
        val error = assertThrows(RuntimeException::class.java) { parser.parse(byteArrayOf(0x01, 0x02)) }

        assertEquals("INVALID_FILE", (error as? ApiException)?.code)
        assertTrue(error.cause is IOException)
    }

    private fun pdf(text: String): ByteArray = PDDocument().use { document ->
        document.addPage(PDPage())
        PDPageContentStream(document, document.getPage(0)).use { stream ->
            stream.beginText()
            val font = requireNotNull(javaClass.getResourceAsStream("/fonts/JetBrainsMono-Regular.ttf")).use { bytes ->
                PDType0Font.load(document, bytes)
            }
            stream.setFont(font, 12f)
            stream.newLineAtOffset(40f, 750f)
            text.lines().forEachIndexed { index, line ->
                if (index > 0) stream.newLineAtOffset(0f, -16f)
                stream.showText(line)
            }
            stream.endText()
        }
        ByteArrayOutputStream().also(document::save).toByteArray()
    }
}
