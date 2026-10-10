package ru.fsp.talent.documents

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import ru.fsp.talent.core.DbTestBase

class ProfilePdfLinkTest : DbTestBase() {
    @Autowired
    private lateinit var pdf: ProfilePdfService

    @Test
    fun `passport QR opens the current profile without promising registry verification`() {
        val id = "11111111-1111-4111-8111-111111111114"
        val expectedLink = "http://testserver/invitations?candidate_id=$id"
        val bytes = pdf.render(mapOf(
            "id" to id,
            "display_name" to "Кандидат",
            "declared_grade" to "junior",
            "allowed_work_modes" to listOf("remote"),
            "cities" to emptyList<String>(),
            "max_weekly_hours" to 40,
        ))
        Loader.loadPDF(bytes).use { document ->
            val text = PDFTextStripper().getText(document)
            assertFalse(text.contains("ПРОВЕРКА ПОДЛИННОСТИ"), text)
            assertFalse(text.contains("реестре ФСП"), text)
            assertTrue(text.contains("после входа"), text)
            assertTrue(text.contains(expectedLink), text)
            val image = PDFRenderer(document).renderImageWithDPI(document.numberOfPages - 1, 180f)
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))
            assertEquals(expectedLink, MultiFormatReader().decode(bitmap).text)
        }
    }
}
