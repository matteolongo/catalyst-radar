package com.catalystradar.persistence.catalyst

import com.catalystradar.domain.catalyst.CatalystScore
import com.catalystradar.domain.catalyst.CatalystSnapshot
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.catalyst.ScoreVelocity
import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Transactional
class SnapshotStoreTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var snapshots: CatalystSnapshotStore

    @Autowired
    private lateinit var companies: CompanyStore

    @Test
    fun `persists and reloads snapshot with score state and velocity`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell Technologies"))
        val asOf = Instant.parse("2026-09-16T10:00:00Z")

        snapshots.save(
            CatalystSnapshot(
                companyId = company.id,
                score = CatalystScore(value = 63.5, version = "score-v1"),
                state = CatalystState.BUILDING,
                velocity = ScoreVelocity(velocity1d = 4.0, velocity3d = 11.5, velocity7d = 32.0),
                asOf = asOf,
                taxonomyVersion = "taxonomy-v1",
            ),
        )

        val latest = snapshots.latestSnapshot(company.id)

        assertNotNull(latest)
        assertEquals(63.5, latest.score.value)
        assertEquals("score-v1", latest.score.version)
        assertEquals(CatalystState.BUILDING, latest.state)
        assertEquals(32.0, latest.velocity.velocity7d)
        assertEquals(asOf, latest.asOf)
    }

    @Test
    fun `latest snapshot wins over older ones`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell Technologies"))
        snapshots.save(newSnapshot(company.id, 40.0, "2026-09-14T10:00:00Z"))
        snapshots.save(newSnapshot(company.id, 63.5, "2026-09-16T10:00:00Z"))

        val latest = snapshots.latestSnapshot(company.id)

        assertNotNull(latest)
        assertEquals(63.5, latest.score.value)
    }

    @Test
    fun `records state transitions explicitly`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell Technologies"))
        val at = Instant.parse("2026-09-16T10:00:00Z")

        snapshots.recordTransition(
            companyId = company.id,
            from = CatalystState.WATCH,
            to = CatalystState.BUILDING,
            score = CatalystScore(value = 50.0, version = "score-v1"),
            at = at,
        )

        val transitions = snapshots.findTransitions(company.id)

        assertEquals(1, transitions.size)
        assertEquals(CatalystState.WATCH, transitions[0].from)
        assertEquals(CatalystState.BUILDING, transitions[0].to)
        assertEquals(at, transitions[0].at)
    }

    private fun newSnapshot(companyId: java.util.UUID, score: Double, asOf: String) =
        CatalystSnapshot(
            companyId = companyId,
            score = CatalystScore(value = score, version = "score-v1"),
            state = CatalystState.BUILDING,
            velocity = ScoreVelocity(velocity1d = 0.0, velocity3d = 0.0, velocity7d = 0.0),
            asOf = Instant.parse(asOf),
            taxonomyVersion = "taxonomy-v1",
        )
}
