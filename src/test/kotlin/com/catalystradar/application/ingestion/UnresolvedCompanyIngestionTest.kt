package com.catalystradar.application.ingestion

import com.catalystradar.adapters.polygon.PolygonNewsProvider
import com.catalystradar.adapters.polygon.PolygonProperties
import com.catalystradar.domain.company.Company
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.DocumentProcessingStore
import com.catalystradar.persistence.document.SourceDocumentCompanyStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.ingestion.IngestionRunStore
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An article that mentions no configured company is still worth keeping,
 * but nothing downstream should act on it: no queue row, no extraction,
 * and one bounded reason an operator can find it by.
 */
@Transactional
class UnresolvedCompanyIngestionTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Autowired
    private lateinit var documentCompanies: SourceDocumentCompanyStore

    @Autowired
    private lateinit var processing: DocumentProcessingStore

    @Autowired
    private lateinit var registrations: SourceDocumentRegistrationService

    @Autowired
    private lateinit var runs: IngestionRunStore

    private val meterRegistry = SimpleMeterRegistry()

    private fun service() = IngestionService(
        providers = listOf(
            PolygonNewsProvider(RestClient.builder(), PolygonProperties(wireMock.baseUrl(), "k")),
        ),
        properties = IngestionProperties(provider = "polygon", fallbackProvider = "none"),
        companies = companies,
        registrations = registrations,
        runs = runs,
        metrics = CatalystMetrics(meterRegistry),
    )

    @Test
    fun `keeps the document, queues nothing, and records one bounded reason`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(UNRELATED_TICKER_PAGE)),
        )
        val now = Instant.parse("2026-09-16T15:00:00Z")

        val result = service().ingestCycle(now)

        val stored = documents.findByProviderAndProviderDocumentId("polygon", "poly-unrelated")
        assertNotNull(stored, "the article stays available for audit")
        assertEquals(1, result.runs[0].added)
        assertEquals(emptyList(), documentCompanies.findCompanyIds(stored.id))
        assertNull(processing.findBySourceDocumentId(stored.id), "an unresolvable document is not queued")
        assertEquals(
            1.0,
            unresolvedDocuments(provider = "polygon"),
            "the reason must be counted once per retained document",
        )
    }

    @Test
    fun `a document left unresolved is never offered to the pipeline`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(UNRELATED_TICKER_PAGE)),
        )
        val now = Instant.parse("2026-09-16T15:00:00Z")

        service().ingestCycle(now)

        val due = processing.findDue(now.plusSeconds(3600), limit = 50).map { it.sourceDocumentId }
        assertTrue(due.isEmpty(), "extraction only ever runs on documents the queue offers")
    }

    @Test
    fun `re-ingesting the same unresolvable article does not inflate the diagnostic`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(UNRELATED_TICKER_PAGE)),
        )
        val now = Instant.parse("2026-09-16T15:00:00Z")

        service().ingestCycle(now)
        val second = service().ingestCycle(now.plusSeconds(600))

        assertEquals(0, second.runs[0].added)
        assertEquals(1, second.runs[0].duplicates)
        assertEquals(1.0, unresolvedDocuments(provider = "polygon"))
    }

    private fun unresolvedDocuments(provider: String): Double =
        meterRegistry.counter(
            "catalyst_ingestion_documents_total",
            "provider",
            provider,
            "status",
            "unresolved_company",
        ).count()

    companion object {
        @RegisterExtension
        @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        // The provider labels this story with a ticker nobody tracks.
        const val UNRELATED_TICKER_PAGE = """{
  "results": [
    {"id": "poly-unrelated", "title": "Unlisted Systems wins contract", "description": "Unlisted Systems won a contract.", "article_url": "https://example.com/u", "published_utc": "2026-09-16T14:30:00Z", "tickers": ["ZZZZ"]}
  ],
  "status": "OK", "request_id": "r", "count": 1
}"""
    }
}
