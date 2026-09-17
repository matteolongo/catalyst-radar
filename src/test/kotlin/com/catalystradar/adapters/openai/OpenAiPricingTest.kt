package com.catalystradar.adapters.openai

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

class OpenAiPricingTest {

    @Test
    fun `estimates extraction cost from token usage`() {
        assertEquals(BigDecimal("0.000045"), OpenAiPricing.estimateUsd("gpt-4o-mini", 100, 50))
    }

    @Test
    fun `unknown models estimate zero instead of fiction`() {
        assertEquals(BigDecimal.ZERO, OpenAiPricing.estimateUsd("gpt-99", 100, 50))
    }
}
