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

    @Test
    fun `dated snapshot ids are priced as their model family`() {
        assertEquals(BigDecimal("0.000045"), OpenAiPricing.estimateUsd("gpt-4o-mini-2024-07-18", 100, 50))
    }

    @Test
    fun `canonical model strips only a trailing date suffix`() {
        assertEquals("gpt-4o-mini", OpenAiPricing.canonicalModel("gpt-4o-mini-2024-07-18"))
        assertEquals("gpt-4o-mini", OpenAiPricing.canonicalModel("gpt-4o-mini"))
        assertEquals("text-embedding-3-small", OpenAiPricing.canonicalModel("text-embedding-3-small"))
    }
}
