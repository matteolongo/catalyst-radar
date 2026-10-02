package com.catalystradar.application.catalyst

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.catalyst.CatalystSnapshotStore
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.event.EventStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class DailySnapshotServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var service: DailySnapshotService

    @Autowired
    private lateinit var catalyst: CatalystService

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var events: EventStore

    @Autowired
    private lateinit var snapshots: CatalystSnapshotStore

    private val t0 = Instant.parse("2026-09-16T10:00:00Z")

    @Test
    fun `recalculates active companies at one explicit asOf without touching inactive companies`() {
        val active = companies.save(Company(ticker = "DELL", name = "Dell"))
        val inactive = companies.save(Company(ticker = "OLD", name = "Old Co", active = false))
        events.save(event(active.id, t0))
        events.save(event(inactive.id, t0))
        val original = catalyst.recalculate(active.id, t0)
        val asOf = t0.plusSeconds(7L * 86_400)

        val result = service.recalculateActive(asOf)

        assertEquals(DailySnapshotStatus.SUCCESS, result.status)
        assertEquals(1, result.companiesConsidered)
        assertEquals(1, result.companiesRecalculated)
        assertEquals(0, result.failures)
        val refreshed = snapshots.latestSnapshot(active.id)
        assertEquals(asOf, refreshed?.asOf)
        assertTrue(requireNotNull(refreshed).score.value < original.score.value)
        assertNull(snapshots.latestSnapshot(inactive.id))
    }

    private fun event(companyId: java.util.UUID, at: Instant) = CatalystEvent(
        companyId = companyId,
        type = EventType.GUIDANCE_RAISE,
        direction = Direction.POSITIVE,
        confidence = 1.0,
        sourceQuality = SourceQuality.TIER1_NEWS,
        expectedHorizon = EventHorizon.WEEKS,
        directness = Directness.DIRECT,
        eventTimestamp = at,
        discoveredAt = at,
        taxonomyVersion = "taxonomy-v1",
        extractorVersion = "event-extractor-v1",
    )
}
