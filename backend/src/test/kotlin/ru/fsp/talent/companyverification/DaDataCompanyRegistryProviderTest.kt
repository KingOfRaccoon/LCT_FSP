package ru.fsp.talent.companyverification

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.fsp.talent.operations.OperationalMetrics

class DaDataCompanyRegistryProviderTest {
    @Test
    fun `parses only minimal DaData party fields through an injected fake transport`() {
        var requestedToken: String? = null
        var requestedInn: String? = null
        val transport = object : RegistryHttpTransport {
            override fun lookup(token: String, inn: String): ByteArray {
                requestedToken = token
                requestedInn = inn
                return """{
                    "suggestions":[{"data":{
                      "inn":"7707083893",
                      "name":{"full_with_opf":"Публичное акционерное общество Сбербанк","short_with_opf":"ПАО Сбербанк"},
                      "state":{"status":"ACTIVE","actuality_date":"2026-10-01"},
                      "address":{"value":"ignored"}
                    }}]
                }""".toByteArray()
            }
        }
        val provider = DaDataCompanyRegistryProvider("dadata", "fake-token", transport, jacksonObjectMapper(), OperationalMetrics())

        val result = provider.lookup("7707083893")

        assertEquals("fake-token", requestedToken)
        assertEquals("7707083893", requestedInn)
        assertTrue(result.available)
        assertEquals("dadata", result.provider)
        assertEquals("7707083893", result.inn)
        assertEquals("Публичное акционерное общество Сбербанк", result.legalName)
        assertEquals("ACTIVE", result.status)
        assertEquals("2026-10-01", result.updatedAt)
    }

    @Test
    fun `manual default does not call the provider and reports unavailable distinctly`() {
        var called = false
        val provider = DaDataCompanyRegistryProvider(
            "manual",
            "",
            object : RegistryHttpTransport {
                override fun lookup(token: String, inn: String): ByteArray {
                    called = true
                    error("manual mode must not call transport")
                }
            },
            jacksonObjectMapper(),
            OperationalMetrics(),
        )

        val result = provider.lookup("7707083893")

        assertFalse(called)
        assertFalse(result.available)
        assertEquals("provider_unavailable", result.errorCode)
    }
}
