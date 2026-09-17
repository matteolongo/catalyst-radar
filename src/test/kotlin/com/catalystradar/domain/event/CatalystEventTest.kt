package com.catalystradar.domain.event

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalystEventTest {

    private fun validEvent() = CatalystEvent(
        companyId = UUID.randomUUID(),
        type = EventType.GUIDANCE_RAISE,
        direction = Direction.POSITIVE,
        confidence = 0.85,
        magnitude = 0.12,
        surprise = 0.6,
        materiality = 0.8,
        sourceQuality = SourceQuality.TIER1_NEWS,
        expectedHorizon = EventHorizon.WEEKS,
        directness = Directness.DIRECT,
        scheduled = false,
        eventTimestamp = Instant.parse("2026-09-15T20:00:00Z"),
        discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
        taxonomyVersion = "taxonomy-v1",
        extractorVersion = "event-extractor-v1",
    )

    @Test
    fun `creates event with valid attributes`() {
        val event = validEvent()

        assertEquals(EventType.GUIDANCE_RAISE, event.type)
        assertEquals(Direction.POSITIVE, event.direction)
        assertNull(event.clusterId)
    }

    @Test
    fun `derives family from event type`() {
        assertEquals(EventFamily.GUIDANCE, validEvent().family)
    }

    @Test
    fun `rejects confidence outside zero to one`() {
        assertThrows<IllegalArgumentException> {
            validEvent().copy(confidence = 1.5)
        }
        assertThrows<IllegalArgumentException> {
            validEvent().copy(confidence = -0.1)
        }
    }

    @Test
    fun `rejects surprise outside zero to one when present`() {
        assertThrows<IllegalArgumentException> {
            validEvent().copy(surprise = 2.0)
        }
    }

    @Test
    fun `rejects materiality outside zero to one when present`() {
        assertThrows<IllegalArgumentException> {
            validEvent().copy(materiality = -0.5)
        }
    }

    @Test
    fun `rejects negative magnitude`() {
        assertThrows<IllegalArgumentException> {
            validEvent().copy(magnitude = -1.0)
        }
    }

    @Test
    fun `rejects blank taxonomy version`() {
        assertThrows<IllegalArgumentException> {
            validEvent().copy(taxonomyVersion = " ")
        }
    }

    @Test
    fun `rejects blank extractor version`() {
        assertThrows<IllegalArgumentException> {
            validEvent().copy(extractorVersion = "")
        }
    }
}
