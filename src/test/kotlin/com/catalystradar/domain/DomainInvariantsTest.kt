package com.catalystradar.domain

import com.catalystradar.domain.catalyst.CatalystScore
import com.catalystradar.domain.catalyst.CatalystSnapshot
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.catalyst.ScoreVelocity
import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventCluster
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.domain.event.SourceQuality
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DomainInvariantsTest {

    @Test
    fun `every event type builds an event deriving its declared family`() {
        EventType.entries.forEach { type ->
            val event = CatalystEvent(
                companyId = UUID.randomUUID(),
                type = type,
                direction = Direction.POSITIVE,
                confidence = 0.5,
                sourceQuality = SourceQuality.TIER1_NEWS,
                expectedHorizon = EventHorizon.DAYS,
                directness = Directness.DIRECT,
                discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
                taxonomyVersion = "taxonomy-v1",
                extractorVersion = "event-extractor-v1",
            )

            assertEquals(type.family, event.family, "family of $type")
        }
    }

    @Test
    fun `taxonomy keeps its v1 breadth`() {
        assertTrue(EventType.entries.size >= 100, "taxonomy shrank to ${EventType.entries.size} types")
    }

    @Test
    fun `generated ids are unique per instance`() {
        assertNotEquals(Company(ticker = "DELL", name = "Dell").id, Company(ticker = "DELL", name = "Dell").id)
        assertNotEquals(newDocument().id, newDocument().id)
        assertNotEquals(newEvent().id, newEvent().id)
        assertNotEquals(newCluster().id, newCluster().id)
    }

    @Test
    fun `point-in-time timestamps stay independent`() {
        val eventTimestamp = Instant.parse("2026-09-15T20:00:00Z")
        val publishedAt = Instant.parse("2026-09-15T21:00:00Z")
        val discoveredAt = Instant.parse("2026-09-16T10:00:00Z")

        val document = newDocument().copy(publishedAt = publishedAt, discoveredAt = discoveredAt)
        val event = newEvent().copy(eventTimestamp = eventTimestamp, discoveredAt = discoveredAt)

        assertEquals(publishedAt, document.publishedAt)
        assertEquals(discoveredAt, document.discoveredAt)
        assertEquals(eventTimestamp, event.eventTimestamp)
        assertEquals(discoveredAt, event.discoveredAt)
    }

    @Test
    fun `snapshot preserves score version for replay`() {
        val snapshot = CatalystSnapshot(
            companyId = UUID.randomUUID(),
            score = CatalystScore(value = 70.0, version = "score-v1"),
            state = CatalystState.CATALYZED,
            velocity = ScoreVelocity(velocity1d = 1.0, velocity3d = 2.0, velocity7d = 3.0),
            asOf = Instant.parse("2026-09-16T10:00:00Z"),
            taxonomyVersion = "taxonomy-v1",
        )

        assertEquals("score-v1", snapshot.score.version)
        assertEquals("taxonomy-v1", snapshot.taxonomyVersion)
    }

    private fun newDocument() = SourceDocument(
        provider = "polygon",
        title = "title",
        body = "body",
        discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
    )

    private fun newEvent() = CatalystEvent(
        companyId = UUID.randomUUID(),
        type = EventType.EARNINGS_BEAT,
        direction = Direction.POSITIVE,
        confidence = 0.9,
        sourceQuality = SourceQuality.PRIMARY,
        expectedHorizon = EventHorizon.DAYS,
        directness = Directness.DIRECT,
        discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
        taxonomyVersion = "taxonomy-v1",
        extractorVersion = "event-extractor-v1",
    )

    private fun newCluster() = EventCluster(
        companyId = UUID.randomUUID(),
        eventType = EventType.EARNINGS_BEAT,
        firstSeenAt = Instant.parse("2026-09-16T10:00:00Z"),
    )
}
