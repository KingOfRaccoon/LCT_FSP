package ru.fsp.talent.profiles

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import ru.fsp.talent.core.DbTestBase
import ru.fsp.talent.core.TestActor
import java.io.ByteArrayOutputStream
import java.util.UUID

class ResumeImportIntegrationTest : DbTestBase() {
    @Test
    fun `imports a conservative reviewable draft and leaves the candidate profile unchanged`() {
        val candidate = seedActor()
        val before = profileSnapshot(candidate.id)
        val response = upload(candidate, resumePdf())

        assertEquals(200, response.statusCode.value(), response.body.toString())
        val data = response.body!!.path("data")
        val patch = data.path("candidate_patch")
        assertEquals("Алексей Иванов", patch.path("display_name").asText())
        assertEquals("Backend-разработчик", patch.path("headline").asText())
        assertTrue(patch.path("cities").any { it.asText() == "Москва" })
        assertEquals(setOf("python-basics", "http-api", "sql-transactions", "postgres", "docker", "fastapi"),
            patch.path("skills").map { it.asText() }.toSet())
        assertTrue(patch.path("skills").size() <= 7)
        assertTrue(patch.path("experience_entries").isArray, patch.toPrettyString())
        assertTrue(patch.path("experience_entries").any {
            it.path("company").asText() == "ООО Ромашка" &&
                it.path("role").asText() == "Backend developer" &&
                it.path("start_month").asText() == "2022-01" &&
                (it.path("end_month").isMissingNode || it.path("end_month").isNull)
        }, patch.toPrettyString())
        assertTrue(data.path("suggestions").path("contacts").any { it.path("type").asText() == "email" && it.path("value").asText() == "alex@example.com" })
        assertTrue(data.path("suggestions").path("contacts").any { it.path("type").asText() == "phone" })
        assertTrue(data.path("needs_review").asBoolean())
        assertTrue(data.path("warnings").isArray)
        assertFalse(patch.has("contacts"))
        assertFalse(patch.has("declared_grade"))
        assertFalse(patch.has("verified_grade"))
        assertFalse(patch.has("qualifications"))
        assertEquals(before, profileSnapshot(candidate.id), "Import must only return a draft; applying it is a separate profile patch")
    }

    @Test
    fun `rejects invalid corrupt encrypted oversized and overlong PDFs with explicit errors`() {
        val candidate = seedActor()

        assertUploadError(candidate, "not a PDF".toByteArray(), "RESUME_IMPORT_INVALID_PDF")
        assertUploadError(candidate, encryptedPdf(), "RESUME_IMPORT_ENCRYPTED")
        assertUploadError(candidate, ByteArray(5 * 1024 * 1024 + 1), "RESUME_IMPORT_TOO_LARGE")
        assertUploadError(candidate, pdfWithPages(21), "RESUME_IMPORT_TOO_MANY_PAGES")
    }

    @Test
    fun `rejects PDFs without extractable text as scanned or empty documents`() {
        val candidate = seedActor()
        assertUploadError(candidate, pdfWithPages(1), "RESUME_IMPORT_NO_TEXT")
    }

    @Test
    fun `requires a verified candidate and current processing consent`() {
        val unverified = seedActor(verified = false)
        val unverifiedResponse = upload(unverified, resumePdf())
        assertEquals(422, unverifiedResponse.statusCode.value(), unverifiedResponse.body.toString())
        assertEquals("EMAIL_UNCONFIRMED", unverifiedResponse.body!!.path("error").path("code").asText())

        val employer = seedActor(role = "employer")
        val employerResponse = upload(employer, resumePdf())
        assertEquals(403, employerResponse.statusCode.value(), employerResponse.body.toString())
        assertEquals("FORBIDDEN", employerResponse.body!!.path("error").path("code").asText())

        val noConsent = seedActor(defaultConsents = false)
        val consentResponse = upload(noConsent, resumePdf())
        assertEquals(403, consentResponse.statusCode.value(), consentResponse.body.toString())
        assertEquals("CONSENT_REQUIRED", consentResponse.body!!.path("error").path("code").asText())
    }

    private fun assertUploadError(actor: TestActor, bytes: ByteArray, code: String) {
        val response = upload(actor, bytes)
        val expectedStatus = when (code) {
            "RESUME_IMPORT_TOO_LARGE" -> 413
            "RESUME_IMPORT_TOO_MANY_PAGES", "RESUME_IMPORT_NO_TEXT" -> 422
            else -> 400
        }
        assertEquals(expectedStatus, response.statusCode.value(), response.body.toString())
        assertEquals(code, response.body!!.path("error").path("code").asText())
    }

    private fun upload(actor: TestActor, bytes: ByteArray) = run {
        val fileHeaders = HttpHeaders().apply { contentType = MediaType.APPLICATION_PDF }
        val file = object : ByteArrayResource(bytes) {
            override fun getFilename() = "resume.pdf"
        }
        val parts: MultiValueMap<String, Any> = LinkedMultiValueMap<String, Any>().apply {
            add("file", HttpEntity(file, fileHeaders))
        }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            accept = listOf(MediaType.APPLICATION_JSON)
            set("Origin", "http://testserver")
            set("Cookie", "${ru.fsp.talent.core.SessionSupport.COOKIE_NAME}=${actor.sessionCookie}")
            set("X-CSRF-Token", actor.csrfToken)
        }
        http.postForEntity("http://localhost:$port/api/v1/me/resume/import", HttpEntity(parts, headers), com.fasterxml.jackson.databind.JsonNode::class.java)
    }

    private fun profileSnapshot(userId: String): Map<String, Any?> = jdbc.queryForMap(
        "SELECT version, data::text AS data, contacts::text AS contacts FROM candidate_profiles WHERE user_id = ?::uuid",
        userId,
    )

    private fun resumePdf(): ByteArray = pdfWithText(
        """Алексей Иванов
            |Должность: Backend-разработчик
            |Город: Москва
            |Email: alex@example.com
            |Телефон: +7 999 123-45-67
            |Навыки: Python, FastAPI, PostgreSQL, Docker, SQL, REST API
            |Опыт работы
            |ООО Ромашка — Backend developer
            |Период работы: январь 2022 — настоящее время
        """.trimMargin(),
    )

    private fun pdfWithText(text: String): ByteArray = PDDocument().use { document ->
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        val font = javaClass.getResourceAsStream("/fonts/JetBrainsMono-Regular.ttf")!!.use { PDType0Font.load(document, it) }
        PDPageContentStream(document, page).use { stream ->
            stream.beginText()
            stream.setFont(font, 11f)
            stream.newLineAtOffset(48f, 780f)
            text.lines().forEachIndexed { index, line ->
                if (index > 0) stream.newLineAtOffset(0f, -18f)
                stream.showText(line)
            }
            stream.endText()
        }
        ByteArrayOutputStream().also { document.save(it) }.toByteArray()
    }

    private fun pdfWithPages(count: Int): ByteArray = PDDocument().use { document ->
        repeat(count) { document.addPage(PDPage(PDRectangle.A4)) }
        ByteArrayOutputStream().also { document.save(it) }.toByteArray()
    }

    private fun encryptedPdf(): ByteArray = PDDocument().use { document ->
        document.addPage(PDPage(PDRectangle.A4))
        val policy = StandardProtectionPolicy("owner-password", "user-password", AccessPermission())
        policy.setEncryptionKeyLength(128)
        document.protect(policy)
        ByteArrayOutputStream().also { document.save(it) }.toByteArray()
    }
}
