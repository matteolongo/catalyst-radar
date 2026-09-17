package com.catalystradar.application.ingestion

import com.catalystradar.adapters.finnhub.FinnhubNewsProvider
import com.catalystradar.adapters.finnhub.FinnhubProperties
import com.catalystradar.adapters.polygon.PolygonNewsProvider
import com.catalystradar.adapters.polygon.PolygonProperties
import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.ingestion.IngestionRunStore
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Repeat and cross-provider safety: scheduled ingestion may overlap
 * windows and providers freely; stored documents never duplicate and
 * never re-score.
 */
@Transactional
class IngestionIdempotencyTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Autowired
    private lateinit var runs: IngestionRunStore

    private fun service(provider: String, fallback: String) = IngestionService(
        providers = listOf(
            PolygonNewsProvider(RestClient.builder(), PolygonProperties(wireMock.baseUrl(), "k")),
            FinnhubNewsProvider(RestClient.builder(), FinnhubProperties(wireMock.baseUrl(), "k")),
        ),
        properties = IngestionProperties(provider = provider, fallbackProvider = fallback),
        companies = companies,
        documents = documents,
        runs = runs,
    )

    @Test
    fun `same story across providers and cycles stores once`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(POLYGON_PAGE)),
        )
        wireMock.stubFor(
            get(urlPathEqualTo("/api/v1/company-news")).willReturn(okJson(FINNHUB_PAGE)),
        )

        val first = service("polygon", "none").ingestCycle()
        val second = service("finnhub", "none").ingestCycle()

        assertEquals(1, first.runs[0].added)
        assertEquals(0, second.runs[0].added)
        assertEquals(1, second.runs[0].duplicates)
        val stored = documents.findByProviderAndProviderDocumentId("polygon", "poly-1")
        assertNotNull(stored)
        assertNotNull(documents.findByContentHash(stored.contentHash!!))
    }

    companion object {
        @RegisterExtension
        @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        // Both pages normalize to the same title/body/url, hence the same hash.
        const val POLYGON_PAGE = """{
  "results": [
    {"id": "poly-1", "title": "Dell raises forecast", "description": "Dell raised.", "article_url": "https://example.com/a", "published_utc": "2026-09-16T14:30:00Z", "tickers": ["DELL"]}
  ],
  "status": "OK", "request_id": "r", "count": 1
}"""

        const val FINNHUB_PAGE = """[
  {"category": "company", "datetime": 1758028200, "headline": "Dell raises forecast", "id": 9, "image": "", "related": "DELL", "source": "Reuters", "summary": "Dell raised.", "url": "https://example.com/a"}
]"""
    }
}
