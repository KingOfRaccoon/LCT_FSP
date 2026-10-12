package ru.fsp.talent.delivery

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OutboxRulesTest {
    @Test
    fun `five attempts have bounded backoff and then stop`() {
        assertThat((1..5).map(OutboxRules::delayAfterFailureMinutes)).containsExactly(1L, 5L, 15L, 60L, null)
    }
}
