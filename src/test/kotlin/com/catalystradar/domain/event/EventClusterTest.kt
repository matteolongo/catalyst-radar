package com.catalystradar.domain.event

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class EventClusterTest {

    @Test
    fun `creates cluster for company type and first sighting`() {
        val companyId = UUID.randomUUID()
        val firstSeenAt = Instant.parse("2026-09-16T10:00:00Z")

        val cluster = EventCluster(
            companyId = companyId,
            eventType = EventType.GUIDANCE_RAISE,
            firstSeenAt = firstSeenAt,
        )

        assertEquals(companyId, cluster.companyId)
        assertEquals(EventType.GUIDANCE_RAISE, cluster.eventType)
        assertEquals(firstSeenAt, cluster.firstSeenAt)
    }
}
