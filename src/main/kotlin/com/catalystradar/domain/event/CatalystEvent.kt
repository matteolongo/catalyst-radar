package com.catalystradar.domain.event

import java.time.Instant
import java.util.UUID

/**
 * A structured, evidence-linked catalyst extracted for one company.
 *
 * An event is not an article: it carries validated extraction attributes and
 * joins its canonical [EventCluster] once deduplicated. Scoring reads these
 * attributes but never trusts unvalidated model output.
 */
data class CatalystEvent(
    val id: UUID = UUID.randomUUID(),
    val companyId: UUID,
    val clusterId: UUID? = null,
    val type: EventType,
    val direction: Direction,
    val confidence: Double,
    val magnitude: Double? = null,
    val surprise: Double? = null,
    val materiality: Double? = null,
    val sourceQuality: SourceQuality,
    val expectedHorizon: EventHorizon,
    val directness: Directness,
    val scheduled: Boolean = false,
    val eventTimestamp: Instant? = null,
    val discoveredAt: Instant,
    val taxonomyVersion: String,
    val extractorVersion: String,
) {
    val family: EventFamily get() = type.family

    init {
        require(confidence in 0.0..1.0) { "confidence must be within 0..1, was $confidence" }
        surprise?.let { require(it in 0.0..1.0) { "surprise must be within 0..1, was $it" } }
        materiality?.let { require(it in 0.0..1.0) { "materiality must be within 0..1, was $it" } }
        magnitude?.let { require(it >= 0.0) { "magnitude must be non-negative, was $it" } }
        require(taxonomyVersion.isNotBlank()) { "taxonomyVersion must not be blank" }
        require(extractorVersion.isNotBlank()) { "extractorVersion must not be blank" }
    }
}
