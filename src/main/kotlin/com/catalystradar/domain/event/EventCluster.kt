package com.catalystradar.domain.event

import java.time.Instant
import java.util.UUID

/**
 * Canonical grouping of repeated reporting about the same underlying
 * real-world fact for one company and event type.
 *
 * Repeated coverage attaches here as evidence but contributes only one
 * canonical scoring contribution, so article count never becomes event count.
 */
data class EventCluster(
    val id: UUID = UUID.randomUUID(),
    val companyId: UUID,
    val eventType: EventType,
    val firstSeenAt: Instant,
)
