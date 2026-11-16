package com.catalystradar.persistence.discovery

import com.catalystradar.common.InvalidInputException
import com.catalystradar.domain.catalyst.CatalystScore
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.catalyst.TransitionDirection
import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.catalyst.CatalystSnapshotStore
import com.catalystradar.persistence.company.CompanyStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Transactional
class TransitionFeedStoreTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var store: TransitionFeedStore

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var snapshots: CatalystSnapshotStore

    private val now = Instant.parse("2026-11-16T12:00:00Z")
    private val day = now.minusSeconds(86_400)

    @Test
    fun `returns transitions inside the window newest first with direction`() {
        seed()

        val page = store.find(TransitionFeedQuery(since = day, asOf = now))

        assertEquals(listOf("NVDA", "INTC", "DELL"), page.transitions.map { it.ticker })
        assertEquals(
            listOf(TransitionDirection.UPGRADE, TransitionDirection.DOWNGRADE, TransitionDirection.UPGRADE),
            page.transitions.map { it.direction },
        )
        assertNull(page.nextCursor)
    }

    @Test
    fun `filters by direction and target state`() {
        seed()

        val down = store.find(TransitionFeedQuery(since = day, asOf = now, direction = TransitionDirection.DOWNGRADE))
        val toHigh = store.find(TransitionFeedQuery(since = day, asOf = now, toStates = setOf(CatalystState.HIGH)))

        assertEquals(listOf("INTC"), down.transitions.map { it.ticker })
        assertEquals(listOf("NVDA"), toHigh.transitions.map { it.ticker })
    }

    @Test
    fun `filters by ticker`() {
        seed()

        val page = store.find(TransitionFeedQuery(since = day, asOf = now, tickers = setOf("DELL", "INTC")))

        assertEquals(listOf("INTC", "DELL"), page.transitions.map { it.ticker })
    }

    @Test
    fun `cursor pages are disjoint and stable`() {
        seed()

        val first = store.find(TransitionFeedQuery(since = day, asOf = now, limit = 2))
        val second = store.find(TransitionFeedQuery(since = day, asOf = now, limit = 2, cursor = first.nextCursor))

        assertEquals(listOf("NVDA", "INTC"), first.transitions.map { it.ticker })
        assertEquals(listOf("DELL"), second.transitions.map { it.ticker })
        assertNull(second.nextCursor)
    }

    @Test
    fun `inactive companies and transitions after the read instant are excluded`() {
        seed()
        val old = companies.save(Company(ticker = "OLD", name = "Delisted", active = false))
        transition(old.id, CatalystState.NORMAL, CatalystState.HIGH, now.minusSeconds(60))
        val dell = companies.findByTicker("DELL")!!
        transition(dell.id, CatalystState.WATCH, CatalystState.BUILDING, now.plusSeconds(60))

        val page = store.find(TransitionFeedQuery(since = day, asOf = now))

        assertEquals(listOf("NVDA", "INTC", "DELL"), page.transitions.map { it.ticker })
    }

    @Test
    fun `rejects a malformed cursor`() {
        assertThrows<InvalidInputException> { store.find(TransitionFeedQuery(since = day, asOf = now, cursor = "nope")) }
    }

    private fun seed() {
        val dell = companies.save(Company(ticker = "DELL", name = "Dell"))
        val intc = companies.save(Company(ticker = "INTC", name = "Intel"))
        val nvda = companies.save(Company(ticker = "NVDA", name = "Nvidia"))
        transition(dell.id, CatalystState.NORMAL, CatalystState.WATCH, now.minusSeconds(3 * 3_600))
        transition(intc.id, CatalystState.BUILDING, CatalystState.WATCH, now.minusSeconds(2 * 3_600))
        transition(nvda.id, CatalystState.CATALYZED, CatalystState.HIGH, now.minusSeconds(3_600))
        transition(nvda.id, CatalystState.BUILDING, CatalystState.CATALYZED, day.minusSeconds(3_600))
    }

    private fun transition(companyId: UUID, from: CatalystState, to: CatalystState, at: Instant) {
        snapshots.recordTransition(companyId, from, to, CatalystScore(50.0, "score-v1"), at)
    }
}
