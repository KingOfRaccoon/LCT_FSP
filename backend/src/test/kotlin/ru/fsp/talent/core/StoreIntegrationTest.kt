package ru.fsp.talent.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class StoreIntegrationTest : DbTestBase() {
    @Autowired private lateinit var store: Store

    @Test
    fun `UUID list binds to a UUID IN query rather than varchar`() {
        val actor=seedActor()
        val rows=store.rows("SELECT id FROM users WHERE id IN (:ids)",mapOf("ids" to listOf(actor.id)))
        assertThat(rows.map{it["id"]}).containsExactly(actor.id)
    }
}
