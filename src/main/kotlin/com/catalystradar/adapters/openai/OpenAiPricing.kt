package com.catalystradar.adapters.openai

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Estimated LLM spend per model, USD per million tokens. Estimates only:
 * update when OpenAI republishes pricing. Unknown models estimate zero
 * rather than a fabricated number.
 */
object OpenAiPricing {

    private data class Price(val inputPerMillionUsd: Double, val outputPerMillionUsd: Double)

    private val PRICES = mapOf(
        "gpt-4o-mini" to Price(inputPerMillionUsd = 0.15, outputPerMillionUsd = 0.60),
        "text-embedding-3-small" to Price(inputPerMillionUsd = 0.02, outputPerMillionUsd = 0.0),
    )

    fun estimateUsd(model: String, inputTokens: Int, outputTokens: Int): BigDecimal {
        val price = PRICES[model] ?: return BigDecimal.ZERO
        return BigDecimal.valueOf(inputTokens.toLong()).multiply(BigDecimal.valueOf(price.inputPerMillionUsd))
            .add(BigDecimal.valueOf(outputTokens.toLong()).multiply(BigDecimal.valueOf(price.outputPerMillionUsd)))
            .divide(BigDecimal.valueOf(1_000_000))
            .setScale(6, RoundingMode.HALF_UP)
    }
}
