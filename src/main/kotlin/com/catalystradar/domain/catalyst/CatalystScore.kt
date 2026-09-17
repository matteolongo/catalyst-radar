package com.catalystradar.domain.catalyst

/**
 * Deterministic company catalyst score on a 0..100 scale.
 *
 * Every score carries the scoring version that produced it (for example
 * score-v1) so historical snapshots stay reproducible when scoring changes.
 */
data class CatalystScore(
    val value: Double,
    val version: String,
) {
    init {
        require(value in 0.0..100.0) { "score must be within 0..100, was $value" }
        require(version.isNotBlank()) { "version must not be blank" }
    }
}
