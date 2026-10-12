package ru.fsp.talent.companyverification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompanyVerificationRulesTest {
    @Test
    fun `validates Russian organization INN checksum`() {
        assertTrue(CompanyVerificationRules.isValidInn("7707083893"))
        assertFalse(CompanyVerificationRules.isValidInn("7707083894"))
        assertFalse(CompanyVerificationRules.isValidInn("770708389"))
    }

    @Test
    fun `normalizes only website host case and www prefix without fetching a site`() {
        assertEquals("example.com", CompanyVerificationRules.siteHost("https://WWW.Example.com/careers"))
        assertEquals("example.com", CompanyVerificationRules.siteHost("example.com"))
        assertEquals(null, CompanyVerificationRules.siteHost("https://user@example.com"))
    }

    @Test
    fun `recognizes common public mailbox domains`() {
        assertTrue(CompanyVerificationRules.isPublicMailboxDomain("gmail.com"))
        assertTrue(CompanyVerificationRules.isPublicMailboxDomain("googlemail.com"))
        assertFalse(CompanyVerificationRules.isPublicMailboxDomain("company.example"))
    }

    @Test
    fun `registry lookup confirms only an active exact legal entity match`() {
        val matchingActive = RegistryLookup(
            available = true,
            provider = "dadata",
            inn = "7707083893",
            legalName = "ПАО Сбербанк",
            status = "ACTIVE",
        )
        assertEquals(
            RegistryDecision("registry_confirmed", "available"),
            CompanyVerificationRules.registryDecision("7707083893", "ПАО Сбербанк", matchingActive),
        )
        assertEquals(
            RegistryDecision("needs_changes", "available"),
            CompanyVerificationRules.registryDecision("7707083893", "ПАО Сбербанк", matchingActive.copy(inn = "7707083894")),
        )
        assertEquals(
            RegistryDecision("needs_changes", "available"),
            CompanyVerificationRules.registryDecision("7707083893", "ПАО Сбербанк", matchingActive.copy(status = "TERMINATED")),
        )
        assertEquals(
            RegistryDecision("unavailable", "unavailable"),
            CompanyVerificationRules.registryDecision("7707083893", "ПАО Сбербанк", RegistryLookup(available = false)),
        )
    }
}
