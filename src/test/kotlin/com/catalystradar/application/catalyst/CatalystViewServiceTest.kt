package com.catalystradar.application.catalyst

import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.event.EventStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Transactional
class CatalystViewServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var views: CatalystViewService

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var events: EventStore

    @Autowired
    private lateinit var catalyst: CatalystService

    private val t0 = Instant.parse("2026-09-16T10:00:00Z")

    @Test
    fun `builds catalyst view from latest snapshot`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        events.save(raise(company.id, EventType.GUIDANCE_RAISE))
        events.save(raise(company.id, EventType.EARNINGS_BEAT))
        val snapshot = catalyst.recalculate(company.id, t0)

        val view = views.view("DELL")

        assertEquals("DELL", view.ticker)
        assertEquals(snapshot.score.value, view.score, 1e-9)
        assertEquals(snapshot.state, view.state)
        assertEquals("score-v1", view.scoreVersion)
        assertEquals(t0, view.asOf)
        assertEquals(2, view.totalEvents)
        assertEquals(2, view.events7d)
        assertTrue(view.positiveScore > 0.0)
        assertEquals(0.0, view.negativeScore, 1e-9)
        assertTrue(view.topDrivers.size == 2)
        assertEquals(EventType.GUIDANCE_RAISE.name, view.topDrivers[0].type)
    }

    @Test
    fun `unknown company throws`() {
        assertThrows<CompanyNotFoundException> {
            views.view("NOPE")
        }
    }

    @Test
    fun `missing snapshot throws`() {
        companies.save(Company(ticker = "DELL", name = "Dell"))

        val error = assertThrows<CatalystNotFoundException> {
            views.view("DELL")
        }

        assertEquals("DELL", error.ticker)
    }

    private fun raise(companyId: UUID, type: EventType) = CatalystEvent(
        companyId = companyId,
        type = type,
        direction = Direction.POSITIVE,
        confidence = 1.0,
        sourceQuality = SourceQuality.TIER1_NEWS,
        expectedHorizon = EventHorizon.WEEKS,
        directness = Directness.DIRECT,
        eventTimestamp = t0,
        discoveredAt = t0,
        taxonomyVersion = "taxonomy-v1",
        extractorVersion = "event-extractor-v1",
    )
}
