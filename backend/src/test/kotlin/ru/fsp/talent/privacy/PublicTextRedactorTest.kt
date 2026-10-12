package ru.fsp.talent.privacy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicTextRedactorTest {
    @Test
    fun `redacts email mailto phone and tel while preserving ordinary dates years counts and salaries`() {
        val result = PublicTextRedactor.redact(
            "2026-10-08, 2024, 12 задач, 70 000–100 000 ₽, 80000 - 120000 ₽, 70000-100000 руб; mail me a.ivanov+dev@example.org or mailto:team@example.org and call +7 (921) 555-12-34 / tel:+7 (921) 555-12-34 / 79215551234 / 89215551234",
        )
        assertEquals(
            "2026-10-08, 2024, 12 задач, 70 000–100 000 ₽, 80000 - 120000 ₽, 70000-100000 руб; mail me [контакт скрыт] or [контакт скрыт] and call [контакт скрыт] / [контакт скрыт] / [контакт скрыт] / [контакт скрыт]",
            result,
        )
        assertTrue(PublicTextRedactor.redact("Опыт: 8 лет, задачи: 12") == "Опыт: 8 лет, задачи: 12")
    }

    @Test
    fun `redaction expansion is clipped after masking to public field limit`() {
        val source = "x".repeat(113) + " a@b.co"
        val result = PublicTextRedactor.redact(source, 120)!!
        assertEquals(120, result.codePointCount(0, result.length))
        assertTrue(result.endsWith("[контакт скрыт]"))
        assertTrue(!result.contains("a@b.co"))
    }

    @Test
    fun `public URL removes direct contacts or userinfo without fetching`() {
        assertEquals("https://portfolio.example/work", PublicTextRedactor.publicUrl("https://portfolio.example/work"))
        assertNull(PublicTextRedactor.publicUrl("https://user:secret@portfolio.example/work"))
        assertNull(PublicTextRedactor.publicUrl("http://portfolio.example/work"))
        assertNull(PublicTextRedactor.publicUrl("https://portfolio.example/?email=a%40example.org"))
        assertNull(PublicTextRedactor.publicUrl("https://portfolio.example/contact/%2B79215551234"))
        assertNull(PublicTextRedactor.publicUrl("https://example.invalid/?phone=+15551234567"))
        assertNull(PublicTextRedactor.publicUrl("https://example.invalid/?phone=%2B15551234567"))
        assertEquals("https://example.invalid/work/2026-10-08", PublicTextRedactor.publicUrl("https://example.invalid/work/2026-10-08"))
        assertNull(PublicTextRedactor.publicUrl("https://portfolio.example/${"a".repeat(2050)}"))
    }
}
