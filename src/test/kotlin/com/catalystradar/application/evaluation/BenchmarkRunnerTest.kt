package com.catalystradar.application.evaluation

import com.catalystradar.application.replay.ReplayService
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.ports.DailyBar
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.ports.EvidenceSpan
import com.catalystradar.ports.ExtractedEvent
import com.catalystradar.ports.ExtractionRequest
import com.catalystradar.ports.ExtractionResult
import com.catalystradar.ports.MarketDataProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Transactional
class BenchmarkRunnerTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Autowired
    private lateinit var events: EventStore

    @Autowired
    private lateinit var replay: com.catalystradar.application.replay.ReplayService

    private fun runner() = BenchmarkRunner(replay, FixedMarketData)

    @Test
    fun `separates movers from controls`() = runTest {
        val t0 = LocalDate.parse("2026-09-10")
        val dell = companies.save(Company(ticker = "DELL", name = "Dell"))
        companies.save(Company(ticker = "HPQ", name = "HP"))
        seedEvidence(dell.id)
        val definition = BenchmarkDefinition(
            cases = listOf(BenchmarkCase("dell-2026-09", "DELL", t0)),
            controls = mapOf("dell-2026-09" to listOf("HPQ")),
            lookbacks = listOf(3, 1),
            horizons = listOf(1, 2),
        )

        val report = runner().run(definition, TitleExtraction)

        val positive = report.positives.single()
        assertEquals(true, positive.detected)
        assertEquals(1, positive.leadTimeDays)
        assertEquals(CatalystState.NORMAL, positive.statesByLookback[3])
        assertEquals(CatalystState.BUILDING, positive.statesByLookback[1])
        assertEquals(0.05, positive.returns[1]!!, 1e-9)
        assertEquals(0.12, positive.returns[2]!!, 1e-9)
        assertEquals(0.13, positive.mfe!!, 1e-9)
        assertEquals(0.04, positive.mae!!, 1e-9)

        assertEquals(1.0, report.recall, 1e-9)
        assertEquals(1.0, report.precision!!, 1e-9)
        assertEquals(0.0, report.falsePositiveRate, 1e-9)
        assertEquals(1.0, report.medianLeadTimeDays!!, 1e-9)
        assertEquals(1.0, report.coverage, 1e-9)
        assertEquals(0.05, report.avgPositiveReturns[1]!!, 1e-9)
        assertEquals(0.01, report.avgControlReturns[1]!!, 1e-9)
        assertNull(report.positives.single().returns[99])
    }

    private fun seedEvidence(companyId: UUID) {
        val docs = listOf(
            Triple("raise", EventType.GUIDANCE_RAISE, "2026-09-06T10:00:00Z"),
            Triple("beat", EventType.EARNINGS_BEAT, "2026-09-08T10:00:00Z"),
            Triple("contract", EventType.CONTRACT_WIN, "2026-09-08T15:00:00Z"),
            Triple("upgrade", EventType.ANALYST_UPGRADE, "2026-09-09T10:00:00Z"),
        )
        docs.forEachIndexed { index, (marker, type, at) ->
            val document = documents.save(
                SourceDocument(
                    provider = "polygon",
                    providerDocumentId = "poly-$index",
                    title = "Dell $marker story",
                    body = "Dell $marker body.",
                    discoveredAt = Instant.parse(at),
                ),
            )
            events.save(
                CatalystEvent(
                    companyId = companyId,
                    type = type,
                    direction = Direction.POSITIVE,
                    confidence = 1.0,
                    sourceQuality = SourceQuality.TIER1_NEWS,
                    expectedHorizon = EventHorizon.WEEKS,
                    directness = Directness.DIRECT,
                    eventTimestamp = Instant.parse(at),
                    discoveredAt = Instant.parse(at),
                    taxonomyVersion = "taxonomy-v1",
                    extractorVersion = "event-extractor-v1",
                ),
                sourceDocumentId = document.id,
            )
        }
    }

    object TitleExtraction : EventExtractionProvider {
        override suspend fun extract(request: ExtractionRequest): ExtractionResult {
            val title = request.document.title
            val type = when {
                "raise" in title -> EventType.GUIDANCE_RAISE
                "beat" in title -> EventType.EARNINGS_BEAT
                "contract" in title -> EventType.CONTRACT_WIN
                else -> EventType.ANALYST_UPGRADE
            }
            return ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    ExtractedEvent(
                        ticker = request.companies.first().ticker,
                        type = type,
                        direction = Direction.POSITIVE,
                        confidence = 1.0,
                        magnitude = null,
                        surprise = null,
                        materiality = null,
                        expectedHorizon = EventHorizon.WEEKS,
                        directness = Directness.DIRECT,
                        eventTimestamp = null,
                        evidence = listOf(EvidenceSpan("quote", null)),
                        attributes = emptyMap(),
                    ),
                ),
            )
        }
    }

    object FixedMarketData : MarketDataProvider {
        private fun bars(ticker: String, closes: List<Pair<String, Double>>): List<DailyBar> {
            val all = mapOf(
                "2026-09-05" to 98.0, "2026-09-06" to 99.0, "2026-09-07" to 99.5,
                "2026-09-08" to 100.0, "2026-09-09" to 100.0,
            )
            return closes.map { (date, close) ->
                val day = LocalDate.parse(date)
                DailyBar(ticker, day, close - 1.0, close + 1.0, close - 1.0, close, 1_000_000L)
            } + all.filterKeys { it < "2026-09-10" }.map { (date, close) ->
                val day = LocalDate.parse(date)
                DailyBar(ticker, day, close, close + 1.0, close - 1.0, close, 1_000_000L)
            }
        }

        override suspend fun dailyBars(ticker: String, from: LocalDate, to: LocalDate): List<DailyBar> =
            when (ticker) {
                "DELL" -> bars(
                    ticker,
                    listOf(
                        "2026-09-10" to 100.0,
                        "2026-09-11" to 105.0,
                        "2026-09-12" to 112.0,
                    ),
                )
                else -> bars(
                    ticker,
                    listOf(
                        "2026-09-10" to 50.0,
                        "2026-09-11" to 50.5,
                        "2026-09-12" to 49.0,
                    ),
                )
            }
    }
}
