package com.catalystradar.application.catalyst

import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventEvidence
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.persistence.document.SourceDocumentStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Transactional
class CatalystViewServiceTest : PostgresIntegrationTest() {

    @Test
    fun `state band shares every state boundary with scoring`() {
        listOf(
            Triple(25.0, CatalystState.WATCH, CatalystState.NORMAL),
            Triple(45.0, CatalystState.BUILDING, CatalystState.WATCH),
            Triple(65.0, CatalystState.CATALYZED, CatalystState.BUILDING),
            Triple(80.0, CatalystState.HIGH, CatalystState.CATALYZED),
        ).forEach { (boundary, at, below) ->
            assertEquals(at, stateForScore(boundary))
            assertEquals(at, stateBandForScore(boundary).state)
            assertEquals(boundary, stateBandForScore(boundary).minScore)
            assertEquals(below, stateForScore(boundary - 0.001))
            assertEquals(below, stateBandForScore(boundary - 0.001).state)
            assertEquals(boundary, stateBandForScore(boundary - 0.001).maxScore)
        }
        assertEquals(CatalystState.NORMAL, stateBandForScore(0.0).state)
        assertEquals(CatalystState.HIGH, stateBandForScore(100.0).state)
        assertEquals(100.0, stateBandForScore(100.0).maxScore)
    }

    @Autowired
    private lateinit var views: CatalystViewService

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var events: EventStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Autowired
    private lateinit var catalyst: CatalystService

    @Autowired
    private lateinit var jdbc: JdbcTemplate

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
    fun `top driver preserves event evidence source and exact score calculation`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val source = documents.save(SourceDocument(
            provider = "polygon",
            title = "Dell raises outlook",
            body = "Private full article body",
            publishedAt = t0.minusSeconds(3600),
            discoveredAt = t0,
            canonicalUrl = "https://example.com/dell",
        ))
        val event = raise(company.id, EventType.GUIDANCE_RAISE).copy(
            evidence = listOf(EventEvidence("Dell raised its full-year outlook.")),
        )
        events.save(event, source.id)
        catalyst.recalculate(company.id, t0)

        val view = views.view("DELL")
        val driver = view.topDrivers.single()

        assertEquals(event.id, driver.eventId)
        assertEquals(event.family.name, driver.family)
        assertEquals(event.eventTimestamp, driver.eventTimestamp)
        assertEquals(event.discoveredAt, driver.discoveredAt)
        assertEquals(event.clusterId, driver.clusterId)
        assertEquals("Dell raised its full-year outlook.", driver.evidence.single().quoteOrFact)
        assertEquals(source.id, driver.source?.sourceDocumentId)
        assertEquals(source.title, driver.source?.title)
        assertEquals(9.0, assertNotNull(driver.factors).value, 1e-9)
        val calculation = assertNotNull(view.scoreCalculation)
        assertEquals(9.0, calculation.contributionSum, 1e-9)
        assertEquals(1, calculation.familyCount)
        assertEquals(1.0, calculation.convergenceMultiplier)
        assertEquals(9.0, calculation.rawScore, 1e-9)
        assertEquals(30.0, calculation.normalizationScale)
        assertEquals(0.01, calculation.contributionCutoff)
    }

    @Test
    fun `current explanation excludes event persisted after snapshot despite earlier discovery`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val first = raise(company.id, EventType.GUIDANCE_RAISE)
        events.save(first)
        val snapshot = catalyst.recalculate(company.id, t0)
        val late = events.save(raise(company.id, EventType.EARNINGS_BEAT))
        val snapshotCreatedAt = assertNotNull(jdbc.queryForObject(
            "SELECT created_at FROM catalyst_snapshots WHERE id = ?", Timestamp::class.java, snapshot.id,
        )).toInstant()
        jdbc.update("UPDATE events SET created_at = ? WHERE id = ?", Timestamp.from(snapshotCreatedAt.plusSeconds(1)), late.id)

        val view = views.view("DELL")

        assertEquals(snapshot.score.value, view.score)
        assertEquals(1, view.totalEvents)
        assertEquals(1, view.topDrivers.size)
        assertEquals(first.id, view.topDrivers.single().eventId)
        assertEquals(9.0, assertNotNull(view.scoreCalculation).rawScore, 1e-9)
        assertEquals(ExplanationStatus.MATCHED, view.explanationStatus)
    }

    @Test
    fun `score mismatch withholds drivers and aggregate explanation`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        events.save(raise(company.id, EventType.GUIDANCE_RAISE))
        val snapshot = catalyst.recalculate(company.id, t0)
        jdbc.update("UPDATE catalyst_snapshots SET score = score + 1 WHERE id = ?", snapshot.id)

        val view = views.view("DELL")

        assertEquals(ExplanationStatus.SCORE_MISMATCH, view.explanationStatus)
        assertTrue(view.topDrivers.isEmpty())
        assertEquals(null, view.scoreCalculation)
    }

    @Test
    fun `version mismatch withholds drivers and aggregate explanation`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        events.save(raise(company.id, EventType.GUIDANCE_RAISE))
        val snapshot = catalyst.recalculate(company.id, t0)
        jdbc.update("UPDATE catalyst_snapshots SET score_version = 'score-v2' WHERE id = ?", snapshot.id)

        val view = views.view("DELL")

        assertEquals(ExplanationStatus.VERSION_MISMATCH, view.explanationStatus)
        assertTrue(view.topDrivers.isEmpty())
        assertEquals(null, view.scoreCalculation)
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
