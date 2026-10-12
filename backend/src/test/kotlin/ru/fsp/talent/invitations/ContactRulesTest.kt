package ru.fsp.talent.invitations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ContactRulesTest {
    @Test
    fun `contact channel types are unique before save`() {
        val channels = listOf(
            mapOf("type" to "email", "value" to "a@example.test", "enabled" to true),
            mapOf("type" to "email", "value" to "b@example.test", "enabled" to false),
        )

        assertThrows(IllegalArgumentException::class.java) { ContactRules.validateChannels(channels) }
    }

    @Test
    fun `contact bundle only includes explicitly enabled channels`() {
        val channels = listOf(
            mapOf("type" to "email", "value" to "a@example.test", "enabled" to true),
            mapOf("type" to "phone", "value" to "+70000000000", "enabled" to false),
        )

        assertEquals(
            listOf(mapOf("type" to "email", "value" to "a@example.test")),
            ContactRules.grantedChannels(channels),
        )
    }
}
