package com.catalystradar.application.pipeline

import com.catalystradar.adapters.polygon.PolygonNewsProvider
import com.catalystradar.adapters.polygon.PolygonProperties
import com.catalystradar.application.catalyst.CatalystService
import com.catalystradar.application.clustering.EventClusteringService
import com.catalystradar.application.clustering.DedupProperties
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.application.event.EventNormalizationService
import com.catalystradar.application.extraction.ExtractionValidator
import com.catalystradar.application.ingestion.IngestionProperties
import com.catalystradar.application.ingestion.IngestionService
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.application.company.CompanyService
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventType
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.catalyst.CatalystSnapshotStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.event.EventClusterStore
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.persistence.ingestion.IngestionRunStore
import com.catalystradar.ports.Embedding
import com.catalystradar.ports.EmbeddingProvider
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.ports.EvidenceSpan
import com.catalystradar.ports.ExtractedEvent
import com.catalystradar.ports.ExtractionRequest
import com.catalystradar.ports.ExtractionResult
import com.catalystradar.ports.NewsProvider
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals

@Transactional
class PipelineServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var companies: CompanyService

    @Autowired
    private lateinit var companyStore: CompanyStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Autowired
    private lateinit var runs: IngestionRunStore

    @Autowired
    private lateinit var events: EventStore

    @Autowired
    private lateinit var clusters: EventClusterStore

    @Autowired
    private lateinit var snapshots: CatalystSnapshotStore

    @Autowired
    private lateinit var validator: ExtractionValidator

    @Autowired
    private lateinit var normalization: EventNormalizationService

    @Autowired
    private lateinit var catalyst: CatalystService

    private val metrics = CatalystMetrics(SimpleMeterRegistry())

    private fun pipeline(providers: List<NewsProvider> = listOf(polygon())) = PipelineService(
        ingestion = IngestionService(
            providers = providers,
            properties = IngestionProperties(),
            companies = companyStore,
            documents = documents,
            runs = runs,
            metrics = metrics,
        ),
        extraction = TitleExtraction,
        normalization = normalization,
        clustering = EventClusteringService(
            embeddings = FakeEmbeddings,
            clusters = clusters,
            events = events,
            companies = companyStore,
            properties = DedupProperties(),
        ),
        catalyst = catalyst,
        documents = documents,
        companies = companies,
        metrics = metrics,
    )

    private fun polygon() = PolygonNewsProvider(
        RestClient.builder(),
        PolygonProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
    )

    @Test
    fun `runs news to snapshot end to end`() = runTest {
        companyStore.save(Company(ticker = "DELL", name = "Dell"))
        val published = Instant.now().minusSeconds(3600).toString()
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(newsPage(published))),
        )

        val result = pipeline().runCycle()

        assertEquals("SUCCESS", result.status)
        assertEquals(4, result.documentsProcessed)
        assertEquals(4, result.eventsExtracted)
        assertEquals(1, result.companiesRescored)
        val snapshot = snapshots.latestSnapshot(companies.findByTicker("DELL")!!.id)
        assertEquals(CatalystState.BUILDING, snapshot?.state)
    }

    private fun newsPage(published: String): String {
        fun article(id: String, marker: String) = """
    {"id": "$id", "title": "Dell $marker story", "description": "Dell $marker body.", "article_url": "https://example.com/$id", "published_utc": "$published", "tickers": ["DELL"]}"""
        return """{
  "results": [
${article("poly-1", "raise")},
${article("poly-2", "beat")},
${article("poly-3", "contract")},
${article("poly-4", "takeover")}
  ],
  "status": "OK", "request_id": "r", "count": 4
}"""
    }

    companion object {
        @RegisterExtension
        @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()
    }

    object TitleExtraction : EventExtractionProvider {
        override suspend fun extract(request: ExtractionRequest): ExtractionResult {
            val title = request.document.title
            val type = when {
                "raise" in title -> EventType.GUIDANCE_RAISE
                "beat" in title -> EventType.EARNINGS_BEAT
                "takeover" in title -> EventType.TAKEOVER_TARGET
                else -> EventType.CONTRACT_WIN
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

    object FakeEmbeddings : EmbeddingProvider {
        override val model: String = "fake"

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
