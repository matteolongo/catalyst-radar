package com.catalystradar.domain.event

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventTaxonomyTest {

    @Test
    fun `exposes all eleven v1 families`() {
        assertEquals(11, EventFamily.entries.size)
    }

    @Test
    fun `maps mandatory coverage types to their families`() {
        val expected = mapOf(
            EventType.EARNINGS_BEAT to EventFamily.EARNINGS,
            EventType.EARNINGS_MISS to EventFamily.EARNINGS,
            EventType.REVENUE_BEAT to EventFamily.EARNINGS,
            EventType.REVENUE_MISS to EventFamily.EARNINGS,
            EventType.GUIDANCE_RAISE to EventFamily.GUIDANCE,
            EventType.GUIDANCE_CUT to EventFamily.GUIDANCE,
            EventType.BACKLOG_GROWTH to EventFamily.EARNINGS,
            EventType.BOOKINGS_ACCELERATION to EventFamily.EARNINGS,
            EventType.CONTRACT_WIN to EventFamily.COMMERCIAL,
            EventType.LARGE_ORDER to EventFamily.COMMERCIAL,
            EventType.CUSTOMER_WIN to EventFamily.COMMERCIAL,
            EventType.TAKEOVER_TARGET to EventFamily.CORPORATE_ACTION,
            EventType.ACQUISITION_ANNOUNCED to EventFamily.CORPORATE_ACTION,
            EventType.BUYBACK_ANNOUNCED to EventFamily.CAPITAL_ALLOCATION,
            EventType.ANALYST_UPGRADE to EventFamily.ANALYST,
            EventType.ANALYST_DOWNGRADE to EventFamily.ANALYST,
            EventType.APPROVAL_GRANTED to EventFamily.REGULATORY,
            EventType.APPROVAL_DENIED to EventFamily.REGULATORY,
            EventType.LEGAL_WIN to EventFamily.LEGAL,
            EventType.LEGAL_LOSS to EventFamily.LEGAL,
            EventType.CEO_DEPARTURE to EventFamily.MANAGEMENT_OWNERSHIP,
            EventType.INSIDER_BUY to EventFamily.MANAGEMENT_OWNERSHIP,
            EventType.INDEX_INCLUSION to EventFamily.CORPORATE_ACTION,
            EventType.INDEX_EXCLUSION to EventFamily.CORPORATE_ACTION,
            EventType.PEER_POSITIVE_READTHROUGH to EventFamily.INDUSTRY_EXTERNAL,
        )

        expected.forEach { (type, family) ->
            assertEquals(family, type.family, "family of $type")
        }
    }

    @Test
    fun `every family has at least one event type`() {
        val covered = EventType.entries.map { it.family }.toSet()

        assertEquals(EventFamily.entries.toSet(), covered)
    }

    @Test
    fun `direction uses the closed v1 set`() {
        assertEquals(
            setOf(Direction.POSITIVE, Direction.NEGATIVE, Direction.NEUTRAL, Direction.MIXED),
            Direction.entries.toSet(),
        )
    }

    @Test
    fun `directness distinguishes direct from inferred evidence`() {
        assertEquals(
            setOf(Directness.DIRECT, Directness.INFERRED),
            Directness.entries.toSet(),
        )
        assertTrue(Directness.entries.size == 2)
    }
}
