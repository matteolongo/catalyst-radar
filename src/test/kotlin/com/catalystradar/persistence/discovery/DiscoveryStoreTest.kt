package com.catalystradar.persistence.discovery

import com.catalystradar.domain.catalyst.CatalystScore
import com.catalystradar.domain.catalyst.CatalystSnapshot
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.catalyst.ScoreVelocity
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
import kotlin.test.assertTrue

@Transactional
class DiscoveryStoreTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var store: DiscoveryStore

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var snapshots: CatalystSnapshotStore

    @Autowired
    private lateinit var events: EventStore

    private val t0 = Instant.parse("2026-09-16T10:00:00Z")

    @Test
    fun `ranks latest snapshots by score`() {
        seed()

        val page = store.discover(DiscoveryQuery(limit = 10, offset = 0))

        assertEquals(listOf("NVDA", "DELL", "HPQ"), page.results.map { it.ticker })
        assertEquals(3, page.total)
    }

    @Test
    fun `filters by states minimums and sector`() {
        seed()

        assertEquals(
            listOf("NVDA"),
            store.discover(DiscoveryQuery(states = setOf(CatalystState.CATALYZED), limit = 10, offset = 0))
                .results.map { it.ticker },
        )
        assertEquals(
            listOf("NVDA", "DELL"),
            store.discover(DiscoveryQuery(minScore = 40.0, limit = 10, offset = 0))
                .results.map { it.ticker },
        )
        assertEquals(
            listOf("NVDA"),
            store.discover(DiscoveryQuery(minVelocity7d = 10.0, limit = 10, offset = 0))
                .results.map { it.ticker },
        )
        assertEquals(
            listOf("DELL"),
            store.discover(DiscoveryQuery(sector = "Industrials", limit = 10, offset = 0))
                .results.map { it.ticker },
        )
    }

    @Test
    fun `sorts by velocity on request`() {
        seed()

        val page = store.discover(DiscoveryQuery(sort = DiscoverySort.VELOCITY, limit = 10, offset = 0))

        assertEquals(listOf("NVDA", "HPQ", "DELL"), page.results.map { it.ticker })
    }

    @Test
    fun `paginates with limit and offset`() {
        seed()

        val first = store.discover(DiscoveryQuery(limit = 2, offset = 0))
        val second = store.discover(DiscoveryQuery(limit = 2, offset = 2))

        assertEquals(2, first.results.size)
        assertEquals(1, second.results.size)
        assertEquals(3, second.total)
        assertTrue(first.results.none { r -> second.results.any { it.ticker == r.ticker } })
    }

    @Test
    fun `counts recent events per company`() {
        val dell = companies.save(Company(ticker = "DELL", name = "Dell"))
        snapshots.save(snapshot(dell.id, 46.6, CatalystState.BUILDING, t0))
        val now = Instant.now()
        events.save(
            CatalystEvent(
                companyId = dell.id,
                type = EventType.GUIDANCE_RAISE,
                direction = Direction.POSITIVE,
                confidence = 0.9,
                sourceQuality = SourceQuality.TIER1_NEWS,
                expectedHorizon = EventHorizon.WEEKS,
                directness = Directness.DIRECT,
                eventTimestamp = now,
                discoveredAt = now,
                taxonomyVersion = "taxonomy-v1",
                extractorVersion = "event-extractor-v1",
            ),
        )

        val page = store.discover(DiscoveryQuery(limit = 10, offset = 0))

        assertEquals(1, page.results.single().events7d)
    }

    @Test
    fun `only the latest snapshot per company counts`() {        val dell = companies.save(Company(ticker = "DELL", name = "Dell", sector = "Industrials"))
        snapshots.save(snapshot(dell.id, 90.0, CatalystState.HIGH, t0.minusSeconds(3600)))
        snapshots.save(snapshot(dell.id, 10.0, CatalystState.NORMAL, t0))

        val page = store.discover(DiscoveryQuery(minScore = 50.0, limit = 10, offset = 0))

        assertTrue(page.results.isEmpty())
    }

    private fun seed() {
        val dell = companies.save(Company(ticker = "DELL", name = "Dell", sector = "Industrials"))
        val nvda = companies.save(Company(ticker = "NVDA", name = "Nvidia", sector = "Technology"))
        val hpq = companies.save(Company(ticker = "HPQ", name = "HP", sector = "Technology"))
        snapshots.save(snapshot(dell.id, 46.6, CatalystState.BUILDING, t0, v7d = 5.0))
        snapshots.save(snapshot(nvda.id, 71.4, CatalystState.CATALYZED, t0, v7d = 18.7))
        snapshots.save(snapshot(hpq.id, 10.0, CatalystState.NORMAL, t0, v7d = 8.0))
    }

    private fun snapshot(
        companyId: java.util.UUID,
        score: Double,
        state: CatalystState,
        at: Instant,
        v7d: Double = 0.0,
    ) = CatalystSnapshot(
        companyId = companyId,
        score = CatalystScore(score, "score-v1"),
        state = state,
        velocity = ScoreVelocity(0.0, 0.0, v7d),
        asOf = at,
        taxonomyVersion = "taxonomy-v1",
    )
}
