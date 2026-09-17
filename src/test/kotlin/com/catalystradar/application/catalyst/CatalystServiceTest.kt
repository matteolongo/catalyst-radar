package com.catalystradar.application.catalyst

import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventCluster
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.catalyst.CatalystSnapshotStore
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.event.EventClusterStore
import com.catalystradar.persistence.event.EventStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Transactional
class CatalystServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var service: CatalystService

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var events: EventStore

    @Autowired
    private lateinit var clusters: EventClusterStore

    @Autowired
    private lateinit var snapshots: CatalystSnapshotStore

    private val t0 = Instant.parse("2026-09-16T10:00:00Z")

    @Test
    fun `derives states from score bands`() {
        assertEquals(CatalystState.NORMAL, stateForScore(0.0))
        assertEquals(CatalystState.NORMAL, stateForScore(24.99))
        assertEquals(CatalystState.WATCH, stateForScore(25.0))
        assertEquals(CatalystState.WATCH, stateForScore(44.99))
        assertEquals(CatalystState.BUILDING, stateForScore(45.0))
        assertEquals(CatalystState.BUILDING, stateForScore(64.99))
        assertEquals(CatalystState.CATALYZED, stateForScore(65.0))
        assertEquals(CatalystState.CATALYZED, stateForScore(79.99))
        assertEquals(CatalystState.HIGH, stateForScore(80.0))
        assertEquals(CatalystState.HIGH, stateForScore(100.0))
    }

    @Test
    fun `empty company stays normal at zero`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))

        val snapshot = service.recalculate(company.id, t0)

        assertEquals(0.0, snapshot.score.value, 1e-9)
        assertEquals(CatalystState.NORMAL, snapshot.state)
        assertEquals(t0, snapshot.asOf)
        assertNotNull(snapshots.latestSnapshot(company.id))
    }

    @Test
    fun `accumulating events build state and snapshot`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        events.save(raise(company.id, EventType.GUIDANCE_RAISE))
        events.save(raise(company.id, EventType.EARNINGS_BEAT))
        events.save(raise(company.id, EventType.CONTRACT_WIN))

        val snapshot = service.recalculate(company.id, t0)

        assertEquals(CatalystState.BUILDING, snapshot.state)
        assertTrue(snapshot.score.value in 45.0..65.0)
        assertEquals("score-v1", snapshot.score.version)
        assertEquals("taxonomy-v1", snapshot.taxonomyVersion)
    }

    @Test
    fun `velocity measures change since lookbacks`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        events.save(raise(company.id, EventType.GUIDANCE_RAISE))

        val first = service.recalculate(company.id, t0)
        events.save(raise(company.id, EventType.EARNINGS_BEAT))
        val second = service.recalculate(company.id, t0.plusSeconds(3L * 24 * 3600))

        assertEquals(second.score.value - first.score.value, second.velocity.velocity3d, 1e-9)
        assertEquals(second.score.value - first.score.value, second.velocity.velocity1d, 1e-9)
        // No snapshot exists seven days back yet, so the window reports no change.
        assertEquals(0.0, second.velocity.velocity7d, 1e-9)
    }

    @Test
    fun `state changes record explicit transitions`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        events.save(raise(company.id, EventType.ANALYST_UPGRADE, confidence = 0.5))

        val first = service.recalculate(company.id, t0)
        assertEquals(CatalystState.NORMAL, first.state)
        assertTrue(snapshots.findTransitions(company.id).isEmpty())

        events.save(raise(company.id, EventType.GUIDANCE_RAISE))
        events.save(raise(company.id, EventType.EARNINGS_BEAT))
        events.save(raise(company.id, EventType.CONTRACT_WIN))
        val second = service.recalculate(company.id, t0.plusSeconds(3600))

        assertEquals(CatalystState.BUILDING, second.state)
        val transitions = snapshots.findTransitions(company.id)
        assertEquals(1, transitions.size)
        assertEquals(CatalystState.NORMAL, transitions[0].from)
        assertEquals(CatalystState.BUILDING, transitions[0].to)
    }

    @Test
    fun `clustered duplicates contribute once`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val first = events.save(raise(company.id, EventType.GUIDANCE_RAISE))
        val second = events.save(raise(company.id, EventType.GUIDANCE_RAISE))
        val cluster = clusters.save(
            EventCluster(companyId = company.id, eventType = EventType.GUIDANCE_RAISE, firstSeenAt = t0),
        )
        events.assignCluster(first.id, cluster.id)
        events.assignCluster(second.id, cluster.id)

        val snapshot = service.recalculate(company.id, t0)

        // Exactly one canonical contribution of 9.0, not two.
        assertEquals(100 * 9.0 / 39.0, snapshot.score.value, 1e-9)
    }

    private fun raise(companyId: UUID, type: EventType, confidence: Double = 1.0) = CatalystEvent(
        companyId = companyId,
        type = type,
        direction = Direction.POSITIVE,
        confidence = confidence,
        sourceQuality = SourceQuality.TIER1_NEWS,
        expectedHorizon = EventHorizon.WEEKS,
        directness = Directness.DIRECT,
        eventTimestamp = t0,
        discoveredAt = t0,
        taxonomyVersion = "taxonomy-v1",
        extractorVersion = "event-extractor-v1",
    )
}
