package com.catalystradar.domain.catalyst

/**
 * Score change over trailing windows: current score minus the latest
 * snapshot score at or before now minus 1, 3, and 7 days.
 *
 * Velocity is a first-class discovery dimension alongside the score itself.
 */
data class ScoreVelocity(
    val velocity1d: Double,
    val velocity3d: Double,
    val velocity7d: Double,
)
