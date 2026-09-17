package com.catalystradar.common

/**
 * Cross-cutting artifact versions. Anything that changes historical
 * interpretation (taxonomy, prompts, scoring) versions explicitly so
 * stored data stays reproducible.
 */
object Versions {
    const val TAXONOMY_V1 = "taxonomy-v1"
    const val PROMPT_V1 = "event-extractor-v1"
    const val EXTRACTOR_V1 = "event-extractor-v1"
    const val SCORE_V1 = "score-v1"
}
