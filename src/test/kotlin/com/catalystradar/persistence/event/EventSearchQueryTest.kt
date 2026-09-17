package com.catalystradar.persistence.event

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class EventSearchQueryTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var events: EventStore

    @Autowired
    private lateinit var companies: CompanyStore

    private val t1 = Instant.parse("2026-09-14T10:00:00Z")
    private val t2 = Instant.parse("2026-09-15T10:00:00Z")
    private val t3 = Instant.parse("2026-09-16T10:00:00Z")

    @Test
    fun `filters by ticker type direction and window`() {
        val dell = companies.save(Company(ticker = "DELL", name = "Dell"))
        val hp = companies.save(Company(ticker = "HPQ", name = "HP"))
        events.save(raise(dell.id, EventType.GUIDANCE_RAISE, t2))
        events.save(raise(dell.id, EventType.EARNINGS_MISS, t2, Direction.NEGATIVE))
        events.save(raise(hp.id, EventType.GUIDANCE_RAISE, t2))

        assertEquals(2, events.searchEvents(EventSearch(ticker = "DELL", limit = 10)).events.size)
        assertEquals(
            1,
            events.searchEvents(EventSearch(ticker = "DELL", type = EventType.GUIDANCE_RAISE, limit = 10)).events.size,
        )
        assertEquals(
            1,
            events.searchEvents(EventSearch(direction = Direction.NEGATIVE, limit = 10)).events.size,
        )
        assertEquals(
            0,
            events.searchEvents(EventSearch(from = t3, limit = 10)).events.size,
        )
        assertEquals(
            3,
            events.searchEvents(EventSearch(from = t1, to = t3, limit = 10)).events.size,
        )
    }

    @Test
    fun `walks pages newest first`() {
        val dell = companies.save(Company(ticker = "DELL", name = "Dell"))
        events.save(raise(dell.id, EventType.GUIDANCE_RAISE, t1))
        events.save(raise(dell.id, EventType.EARNINGS_BEAT, t2))
        events.save(raise(dell.id, EventType.REVENUE_BEAT, t3))

        val first = events.searchEvents(EventSearch(limit = 2))
        assertEquals(2, first.events.size)
        assertEquals(t3, first.events[0].event.eventTimestamp)
        assertNotNull(first.nextCursor)

        val second = events.searchEvents(EventSearch(limit = 2, cursor = first.nextCursor))
        assertEquals(1, second.events.size)
        assertEquals(t1, second.events[0].event.eventTimestamp)
        assertNull(second.nextCursor)
    }

    @Test
    fun `unknown ticker matches nothing`() {
        val page = events.searchEvents(EventSearch(ticker = "NOPE", limit = 10))

        assertTrue(page.events.isEmpty())
        assertNull(page.nextCursor)
    }

    private fun raise(
        companyId: UUID,
        type: EventType,
        at: Instant,
        direction: Direction = Direction.POSITIVE,
    ) = CatalystEvent(
        companyId = companyId,
        type = type,
        direction = direction,
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
