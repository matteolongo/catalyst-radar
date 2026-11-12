package com.catalystradar.adapters.openai

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Estimated LLM spend per model, USD per million tokens. Estimates only:
 * update when OpenAI republishes pricing. Unknown models leave cost unknown.
 */
object OpenAiPricing {

    private data class Price(val inputPerMillionUsd: Double, val outputPerMillionUsd: Double)

    private val PRICES = mapOf(
        "gpt-4o-mini" to Price(inputPerMillionUsd = 0.15, outputPerMillionUsd = 0.60),
        "text-embedding-3-small" to Price(inputPerMillionUsd = 0.02, outputPerMillionUsd = 0.0),
    )

    private val SNAPSHOT_SUFFIX = Regex("-\\d{4}-\\d{2}-\\d{2}$")

    /**
     * Model family without a dated snapshot suffix. OpenAI may answer a
     * request for `gpt-4o-mini` with `gpt-4o-mini-2024-07-18`; recording
     * and pricing the family keeps one model in one place.
     */
    fun canonicalModel(model: String): String = model.replace(SNAPSHOT_SUFFIX, "")

    fun estimateUsd(model: String, inputTokens: Int, outputTokens: Int): BigDecimal? {
        val price = PRICES[model] ?: PRICES[canonicalModel(model)] ?: return null
        return BigDecimal.valueOf(inputTokens.toLong()).multiply(BigDecimal.valueOf(price.inputPerMillionUsd))
            .add(BigDecimal.valueOf(outputTokens.toLong()).multiply(BigDecimal.valueOf(price.outputPerMillionUsd)))
            .divide(BigDecimal.valueOf(1_000_000))
            .setScale(6, RoundingMode.HALF_UP)
    }
}
