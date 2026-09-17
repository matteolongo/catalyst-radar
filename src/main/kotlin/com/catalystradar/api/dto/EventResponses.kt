package com.catalystradar.api.dto

import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.persistence.event.EventWithSource
import java.time.Instant
import java.util.UUID

data class CatalystEventResponse(
    val id: UUID,
    val type: String,
    val family: String,
    val direction: String,
    val confidence: Double,
    val magnitude: Double?,
    val surprise: Double?,
    val materiality: Double?,
    val sourceQuality: String,
    val expectedHorizon: String,
    val directness: String,
    val scheduled: Boolean,
    val eventTimestamp: Instant?,
    val discoveredAt: Instant,
    val clusterId: UUID?,
    val sourceDocumentId: UUID?,
    val taxonomyVersion: String,
    val extractorVersion: String,
)

fun EventWithSource.toResponse() = CatalystEventResponse(
    id = event.id,
    type = event.type.name,
    family = event.family.name,
    direction = event.direction.name,
    confidence = event.confidence,
    magnitude = event.magnitude,
    surprise = event.surprise,
    materiality = event.materiality,
    sourceQuality = event.sourceQuality.name,
    expectedHorizon = event.expectedHorizon.name,
    directness = event.directness.name,
    scheduled = event.scheduled,
    eventTimestamp = event.eventTimestamp,
    discoveredAt = event.discoveredAt,
    clusterId = event.clusterId,
    sourceDocumentId = sourceDocumentId,
    taxonomyVersion = event.taxonomyVersion,
    extractorVersion = event.extractorVersion,
)

data class EventsFeedResponse(
    val events: List<CatalystEventResponse>,
    val nextCursor: String?,
)

fun CatalystEvent.toResponse(): CatalystEventResponse =
    EventWithSource(this, sourceDocumentId = null).toResponse()
