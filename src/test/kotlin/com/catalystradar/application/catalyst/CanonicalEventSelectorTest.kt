package com.catalystradar.application.catalyst

import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceQuality
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class CanonicalEventSelectorTest {

    private val selector = CanonicalEventSelector()
    private val at = Instant.parse("2026-09-16T10:00:00Z")

    @Test
    fun `keeps one earliest event per cluster`() {
        val cluster = UUID.randomUUID()
        val events = listOf(
            newEvent(type = EventType.GUIDANCE_RAISE, at = at.plusSeconds(3600), cluster = cluster),
            newEvent(type = EventType.GUIDANCE_RAISE, at = at, cluster = cluster),
        )

        val selected = selector.select(events)

        assertEquals(listOf(at), selected.map { it.eventTimestamp })
    }

    @Test
    fun `unclustered events never collapse together`() {
        val events = listOf(
            newEvent(type = EventType.GUIDANCE_RAISE, at = at, cluster = null),
            newEvent(type = EventType.GUIDANCE_RAISE, at = at, cluster = null),
        )

        assertEquals(2, selector.select(events).size)
    }

    private fun newEvent(type: EventType, at: Instant, cluster: UUID?) = CatalystEvent(
        companyId = UUID.randomUUID(),
        clusterId = cluster,
        type = type,
        direction = Direction.POSITIVE,
        confidence = 0.9,
        sourceQuality = SourceQuality.TIER1_NEWS,
        expectedHorizon = EventHorizon.WEEKS,
        directness = Directness.DIRECT,
        eventTimestamp = at,
        discoveredAt = at,
        taxonomyVersion = "taxonomy-v1",
        extractorVersion = "event-extractor-v1",
    )
}
