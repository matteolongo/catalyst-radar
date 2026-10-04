package com.catalystradar.adapters.openai

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenAiPricingTest {

    @Test
    fun `estimates extraction cost from token usage`() {
        assertEquals(BigDecimal("0.000045"), OpenAiPricing.estimateUsd("gpt-4o-mini", 100, 50))
    }

    @Test
    fun `unknown models leave cost unknown`() {
        assertNull(OpenAiPricing.estimateUsd("gpt-99", 100, 50))
    }

    @Test
    fun `known zero usage has zero estimated cost`() {
        assertEquals(BigDecimal("0.000000"), OpenAiPricing.estimateUsd("gpt-4o-mini", 0, 0))
    }
}
