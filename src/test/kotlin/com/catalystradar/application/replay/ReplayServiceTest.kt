package com.catalystradar.application.replay

import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventCluster
import com.catalystradar.domain.event.EventEvidence
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.SourceDocumentCompanyStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.event.EventClusterStore
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.ports.Embedding
import com.catalystradar.ports.EmbeddingProvider
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.ports.ExtractedEvent
import com.catalystradar.ports.ExtractionRequest
import com.catalystradar.ports.ExtractionResult
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.math.pow
import kotlin.test.assertEquals

@Import(ReplayServiceTest.ReplayTestConfiguration::class)
@Transactional
class ReplayServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var replay: ReplayService

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Autowired
    private lateinit var documentCompanies: SourceDocumentCompanyStore

    @Autowired
    private lateinit var events: EventStore

    @Autowired
    private lateinit var clusters: EventClusterStore

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    private val t0 = Instant.parse("2026-09-16T10:00:00Z")

    @Test
    fun `replay includes linked documents that have no live event`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val document = documents.save(newDocument("poly-1", t0.minusSeconds(5L * 86_400)))
        documentCompanies.link(document.id, company.id)
        val consideredBefore = meterRegistry.counter(
            "catalyst_replay_documents_total",
            "outcome",
            "considered",
        ).count()

        val result = replay.replay(
            ReplayRequest(ticker = "DELL", cutoff = t0),
            FixedExtractionProvider,
        )

        val contribution = 9.0 * 0.5.pow(5.0 / 20.0)
        assertEquals(1, result.documentsConsidered)
        assertEquals(1, result.candidatesAccepted)
        assertEquals(1, result.eventCount)
        assertEquals(
            consideredBefore + 1.0,
            meterRegistry.counter("catalyst_replay_documents_total", "outcome", "considered").count(),
        )
        assertEquals(100 * contribution / (contribution + 30.0), result.score, 1e-9)
        assertEquals(0, events.findByCompanyId(company.id).size)
    }

    @Test
    fun `replay ignores linked documents discovered after the cutoff`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val before = documents.save(newDocument("poly-1", t0.minusSeconds(5L * 86_400)))
        val after = documents.save(
            newDocument(
                "poly-2",
                discoveredAt = t0.plusSeconds(5L * 86_400),
                publishedAt = t0.minusSeconds(24 * 3_600),
            ),
        )
        documentCompanies.link(before.id, company.id)
        documentCompanies.link(after.id, company.id)

        val result = replay.replay(ReplayRequest(ticker = "DELL", cutoff = t0), FixedExtractionProvider)

        assertEquals(1, result.documentsConsidered)
        assertEquals(1, result.eventCount)
    }

    @Test
    fun `replay is deterministic`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val before = documents.save(newDocument("poly-1", t0.minusSeconds(5L * 86_400)))
        documentCompanies.link(before.id, company.id)

        val first = replay.replay(ReplayRequest(ticker = "DELL", cutoff = t0), FixedExtractionProvider)
        val second = replay.replay(ReplayRequest(ticker = "DELL", cutoff = t0), FixedExtractionProvider)

        assertEquals(first.score, second.score, 1e-12)
        assertEquals(first.state, second.state)
        assertEquals(first.eventCount, second.eventCount)
    }

    @Test
    fun `replay clusters syndicated linked documents in memory`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val first = documents.save(newDocument("poly-1", t0.minusSeconds(5L * 86_400)))
        val second = documents.save(newDocument("poly-2", t0.minusSeconds(4L * 86_400)))
        documentCompanies.link(first.id, company.id)
        documentCompanies.link(second.id, company.id)

        val result = replay.replay(ReplayRequest(ticker = "DELL", cutoff = t0), FixedExtractionProvider)

        assertEquals(2, result.documentsConsidered)
        assertEquals(2, result.candidatesAccepted)
        assertEquals(1, result.eventCount)
    }

    @Test
    fun `future live events and clusters cannot affect replay candidates`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val first = documents.save(newDocument("poly-1", t0.minusSeconds(5L * 86_400)))
        val second = documents.save(newDocument("poly-2", t0.minusSeconds(4L * 86_400)))
        documentCompanies.link(first.id, company.id)
        documentCompanies.link(second.id, company.id)
        val futureCluster = clusters.save(
            EventCluster(
                companyId = company.id,
                eventType = EventType.GUIDANCE_RAISE,
                firstSeenAt = t0.plusSeconds(86_400),
            ),
        )
        events.save(
            liveEvent(company.id, t0.plusSeconds(86_400)).copy(clusterId = futureCluster.id),
            sourceDocumentId = first.id,
        )

        val result = replay.replay(ReplayRequest(ticker = "DELL", cutoff = t0), DifferentFactsProvider)

        assertEquals(2, result.documentsConsidered)
        assertEquals(2, result.eventCount)
    }

    @Test
    fun `replay applies scope and timestamp validation to extraction candidates`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val document = documents.save(newDocument("poly-1", t0.minusSeconds(5L * 86_400)))
        documentCompanies.link(document.id, company.id)

        val result = replay.replay(ReplayRequest(ticker = "DELL", cutoff = t0), InvalidCandidatesProvider)

        assertEquals(1, result.documentsConsidered)
        assertEquals(1, result.documentsSkipped)
        assertEquals(0, result.candidatesAccepted)
        assertEquals(0, result.eventCount)
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

    private fun newDocument(
        providerDocumentId: String,
        discoveredAt: Instant,
        publishedAt: Instant? = null,
    ) = SourceDocument(
        provider = "polygon",
        providerDocumentId = providerDocumentId,
        title = "Dell raises forecast",
        body = "Dell raised.",
        publishedAt = publishedAt,
        discoveredAt = discoveredAt,
    )

    private fun liveEvent(companyId: java.util.UUID, at: Instant) = CatalystEvent(
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

    object FixedExtractionProvider : EventExtractionProvider {
        override suspend fun extract(request: ExtractionRequest): ExtractionResult = ExtractionResult(
            documentRelevant = true,
            events = listOf(
                ReplayServiceTest.extractedEvent(request.companies.single().ticker, "Dell raised its full-year outlook."),
            ),
        )
    }

    object DifferentFactsProvider : EventExtractionProvider {
        override suspend fun extract(request: ExtractionRequest): ExtractionResult {
            val fact = when (request.document.providerDocumentId) {
                "poly-1" -> "Dell raised fiscal 2027 EPS guidance."
                else -> "Dell raised fiscal 2028 revenue guidance."
            }
            return ExtractionResult(
                documentRelevant = true,
                events = listOf(ReplayServiceTest.extractedEvent(request.companies.single().ticker, fact)),
            )
        }
    }

    object InvalidCandidatesProvider : EventExtractionProvider {
        override suspend fun extract(request: ExtractionRequest): ExtractionResult = ExtractionResult(
            documentRelevant = true,
            events = listOf(
                ReplayServiceTest.extractedEvent("MSFT", "Microsoft raised guidance."),
                ReplayServiceTest.extractedEvent(
                    request.companies.single().ticker,
                    "Dell scheduled a future catalyst.",
                    eventTimestamp = request.document.discoveredAt.plusSeconds(1),
                ),
            ),
        )
    }

    companion object {
        private fun extractedEvent(
            ticker: String,
            evidence: String,
            eventTimestamp: Instant? = null,
        ) = ExtractedEvent(
            ticker = ticker,
            type = EventType.GUIDANCE_RAISE,
            direction = Direction.POSITIVE,
            confidence = 1.0,
            magnitude = null,
            surprise = null,
            materiality = null,
            expectedHorizon = EventHorizon.WEEKS,
            directness = Directness.DIRECT,
            eventTimestamp = eventTimestamp,
            evidence = listOf(EventEvidence(evidence, null)),
            attributes = emptyMap(),
        )
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ReplayTestConfiguration {
        @Bean
        @Primary
        fun replayEmbeddingProvider(): EmbeddingProvider = DeterministicEmbeddingProvider()
    }

    class DeterministicEmbeddingProvider : EmbeddingProvider {
        override val model: String = "replay-test"

        override suspend fun embed(text: String): Embedding {
            var seed = text.hashCode().toLong()
            val values = List(1536) {
                seed = (seed * 6364136223846793005L + 1442695040888963407L) shr 11
                ((seed ushr 32) % 2000).toFloat() / 1000f - 1f
            }
            return Embedding(values, model)
        }
    }
}
