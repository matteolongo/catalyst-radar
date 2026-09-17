package com.catalystradar.domain.catalyst

import java.time.Instant
import java.util.UUID

/**
 * Versioned point-in-time record of a company's catalyst score, state, and
 * velocity. Snapshots make history replayable and keep evaluation free of
 * look-ahead bias: a historical read only ever sees snapshots at or before
 * its cutoff.
 */
data class CatalystSnapshot(
    val id: UUID = UUID.randomUUID(),
    val companyId: UUID,
    val score: CatalystScore,
    val state: CatalystState,
    val velocity: ScoreVelocity,
    val asOf: Instant,
    val taxonomyVersion: String,
) {
    init {
        require(taxonomyVersion.isNotBlank()) { "taxonomyVersion must not be blank" }
    }
}
