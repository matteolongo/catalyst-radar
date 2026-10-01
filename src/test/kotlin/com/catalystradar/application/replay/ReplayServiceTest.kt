package com.catalystradar.application.replay

import com.catalystradar.application.company.CompanyNotFoundException
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
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.ports.EvidenceSpan
import com.catalystradar.ports.ExtractedEvent
import com.catalystradar.ports.ExtractionRequest
import com.catalystradar.ports.ExtractionResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.math.pow
import kotlin.test.assertEquals

@Transactional
class ReplayServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var replay: ReplayService

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Autowired
    private lateinit var events: EventStore

    private val t0 = Instant.parse("2026-09-16T10:00:00Z")

    @Test
    fun `replay ignores documents discovered after the cutoff`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val before = documents.save(newDocument("poly-1", t0.minusSeconds(5L * 86_400)))
        documents.save(newDocument("poly-2", t0.plusSeconds(5L * 86_400)))
        // Live event links the early document to the company.
        val live = events.save(liveEvent(company.id), sourceDocumentId = before.id)

        val result = replay.replay(
            ReplayRequest(ticker = "DELL", cutoff = t0),
            FixedExtractionProvider,
        )

        // Exactly the lone guidance raise, decayed five days to the cutoff:
        // 9.0 * 0.5^(5/20), then normalized.
        val contribution = 9.0 * 0.5.pow(5.0 / 20.0)
        assertEquals(1, result.eventCount)
        assertEquals(100 * contribution / (contribution + 30.0), result.score, 1e-9)
        assertEquals(t0, result.asOf)
        assertEquals("score-v1", result.scoreVersion)
        // Replay writes nothing: the live event set is untouched.
        assertEquals(1, events.findByCompanyId(company.id).size)
        assertEquals(live.id, events.findByCompanyId(company.id)[0].id)
    }

    @Test
    fun `replay is deterministic`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val before = documents.save(newDocument("poly-1", t0.minusSeconds(5L * 86_400)))
        events.save(liveEvent(company.id), sourceDocumentId = before.id)

        val first = replay.replay(ReplayRequest(ticker = "DELL", cutoff = t0), FixedExtractionProvider)
        val second = replay.replay(ReplayRequest(ticker = "DELL", cutoff = t0), FixedExtractionProvider)

        assertEquals(first.score, second.score, 1e-12)
        assertEquals(first.state, second.state)
    }

    @Test
    fun `unclustered events never collapse together`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val first = documents.save(newDocument("poly-1", t0.minusSeconds(5L * 86_400)))
        val second = documents.save(newDocument("poly-2", t0.minusSeconds(4L * 86_400)))
        events.save(liveEvent(company.id), sourceDocumentId = first.id)
        events.save(liveEvent(company.id), sourceDocumentId = second.id)

        val result = replay.replay(ReplayRequest(ticker = "DELL", cutoff = t0), FixedExtractionProvider)

        assertEquals(2, result.eventCount)
    }

    @Test
    fun `unknown versions are rejected`() = runTest {
        assertThrows<IllegalArgumentException> {
            replay.replay(
                ReplayRequest(ticker = "DELL", cutoff = t0, scoreVersion = "score-v99"),
                FixedExtractionProvider,
            )
        }
    }

    @Test
    fun `unknown ticker is rejected`() = runTest {
        assertThrows<CompanyNotFoundException> {
            replay.replay(ReplayRequest(ticker = "NOPE", cutoff = t0), FixedExtractionProvider)
        }
    }

    private fun newDocument(providerDocumentId: String, discoveredAt: Instant) = SourceDocument(
        provider = "polygon",
        providerDocumentId = providerDocumentId,
        title = "Dell raises forecast",
        body = "Dell raised.",
        discoveredAt = discoveredAt,
    )

    private fun liveEvent(companyId: java.util.UUID) = CatalystEvent(
        companyId = companyId,
        type = EventType.GUIDANCE_RAISE,
        direction = Direction.POSITIVE,
        confidence = 1.0,
        sourceQuality = SourceQuality.TIER1_NEWS,
        expectedHorizon = EventHorizon.WEEKS,
        directness = Directness.DIRECT,
        eventTimestamp = t0.minusSeconds(5L * 86_400),
        discoveredAt = t0.minusSeconds(5L * 86_400),
        taxonomyVersion = "taxonomy-v1",
        extractorVersion = "event-extractor-v1",
    )

    object FixedExtractionProvider : EventExtractionProvider {
        override suspend fun extract(request: ExtractionRequest): ExtractionResult {
            val ticker = request.companies.firstOrNull()?.ticker ?: "DELL"
            return ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    ExtractedEvent(
                        ticker = ticker,
                        type = EventType.GUIDANCE_RAISE,
                        direction = Direction.POSITIVE,
                        confidence = 1.0,
                        magnitude = null,
                        surprise = null,
                        materiality = null,
                        expectedHorizon = EventHorizon.WEEKS,
                        directness = Directness.DIRECT,
                        eventTimestamp = null,
                        evidence = listOf(EvidenceSpan("raised", null)),
                        attributes = emptyMap(),
                    ),
                ),
            )
        }
    }
}
