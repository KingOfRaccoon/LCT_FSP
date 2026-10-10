package ru.fsp.talent.profiles

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.ResponseEntity
import ru.fsp.talent.core.DbTestBase

class ResumeParseConsentIntegrationTest : DbTestBase() {
    @Test
    fun `resume parse requires current processing consent before reading the uploaded document`() {
        val candidate = seedActor(defaultConsents = false)
        val response = upload(candidate, byteArrayOf(0x01, 0x02))

        assertEquals(403, response.statusCode.value(), response.body.toString())
        assertEquals("CONSENT_REQUIRED", response.body!!.path("error").path("code").asText())
    }

    @Test
    fun `malformed resume returns sanitized invalid file response after consent`() {
        val candidate = seedActor()

        val response = upload(candidate, byteArrayOf(0x01, 0x02))

        assertEquals(400, response.statusCode.value(), response.body.toString())
        assertEquals("INVALID_FILE", response.body!!.path("error").path("code").asText())
        assertEquals("Не удалось прочитать PDF-файл", response.body!!.path("error").path("message").asText())
        assertFalse(response.body!!.path("error").path("message").asText().contains("PDFBox"))
    }

    private fun upload(candidate: ru.fsp.talent.core.TestActor, bytes: ByteArray): ResponseEntity<JsonNode> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            set("Origin", "http://testserver")
            set("Cookie", "${ru.fsp.talent.core.SessionSupport.COOKIE_NAME}=${candidate.sessionCookie}")
            set("X-CSRF-Token", candidate.csrfToken)
            accept = listOf(MediaType.APPLICATION_JSON)
        }
        val body = LinkedMultiValueMap<String, Any>().apply {
            add("file", object : ByteArrayResource(bytes) {
                override fun getFilename() = "resume.pdf"
            })
        }
        return http.exchange(
            "http://localhost:$port/api/v1/me/resume/parse",
            HttpMethod.POST,
            HttpEntity(body, headers),
            JsonNode::class.java,
        )
    }
}
