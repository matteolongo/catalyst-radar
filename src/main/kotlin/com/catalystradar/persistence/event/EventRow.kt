package com.catalystradar.persistence.event

import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.jdbc.JsonB
import com.catalystradar.persistence.jdbc.toJsonB
import com.catalystradar.persistence.jdbc.toStringMap
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * events row. Enums travel as names; schemaless extraction details live in
 * the attributes JSONB so family-specific fields evolve without migrations.
 */
@Table("events")
data class EventRow(
    @Id val id: UUID,
    @Column("company_id") val companyId: UUID,
    @Column("cluster_id") val clusterId: UUID?,
    @Column("source_document_id") val sourceDocumentId: UUID?,
    @Column("event_type") val eventType: String,
    val family: String,
    val direction: String,
    val confidence: Double,
    val magnitude: Double?,
    val surprise: Double?,
    val materiality: Double?,
    @Column("source_quality") val sourceQuality: String,
    @Column("expected_horizon") val expectedHorizon: String,
    val directness: String,
    val scheduled: Boolean,
    @Column("event_timestamp") val eventTimestamp: Instant?,
    @Column("discovered_at") val discoveredAt: Instant,
    @Column("taxonomy_version") val taxonomyVersion: String,
    @Column("extractor_version") val extractorVersion: String,
    val attributes: JsonB,
)

fun EventRow.toDomain() = CatalystEvent(
    id = id,
    companyId = companyId,
    clusterId = clusterId,
    type = EventType.valueOf(eventType),
    direction = Direction.valueOf(direction),
    confidence = confidence,
    magnitude = magnitude,
    surprise = surprise,
    materiality = materiality,
    sourceQuality = SourceQuality.valueOf(sourceQuality),
    expectedHorizon = EventHorizon.valueOf(expectedHorizon),
    directness = Directness.valueOf(directness),
    scheduled = scheduled,
    eventTimestamp = eventTimestamp,
    discoveredAt = discoveredAt,
    taxonomyVersion = taxonomyVersion,
    extractorVersion = extractorVersion,
    attributes = attributes.toStringMap(),
)

fun CatalystEvent.toRow(    sourceDocumentId: UUID? = null,
) = EventRow(
    id = id,
    companyId = companyId,
    clusterId = clusterId,
    sourceDocumentId = sourceDocumentId,
    eventType = type.name,
    family = family.name,
    direction = direction.name,
    confidence = confidence,
    magnitude = magnitude,
    surprise = surprise,
    materiality = materiality,
    sourceQuality = sourceQuality.name,
    expectedHorizon = expectedHorizon.name,
    directness = directness.name,
    scheduled = scheduled,
    eventTimestamp = eventTimestamp,
    discoveredAt = discoveredAt,
    taxonomyVersion = taxonomyVersion,
    extractorVersion = extractorVersion,
    attributes = attributes.toJsonB(),
)

/** An event with its source linkage for API and evaluation reads. */
data class EventWithSource(
    val event: CatalystEvent,
    val sourceDocumentId: UUID?,
)
