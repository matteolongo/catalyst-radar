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
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
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

@Transactional
class IngestionServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Autowired
    private lateinit var runs: IngestionRunStore

    private val polygon = PolygonNewsProvider(
        RestClient.builder(),
        PolygonProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
    )
    private val finnhub = FinnhubNewsProvider(
        RestClient.builder(),
        FinnhubProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
    )

    private fun service(
        provider: String = "polygon",
        fallback: String = "finnhub",
    ) = IngestionService(
        providers = listOf(polygon, finnhub),
        properties = IngestionProperties(provider = provider, fallbackProvider = fallback),
        companies = companies,
        documents = documents,
        runs = runs,
    )

    @Test
    fun `ingests provider articles as source documents`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(NEWS_PAGE)),
        )

        val result = service().ingestCycle()

        assertEquals(1, result.runs.size)
        assertEquals("polygon", result.runs[0].provider)
        assertEquals(IngestionStatus.SUCCESS, result.runs[0].status)
        assertEquals(2, result.runs[0].fetched)
        assertEquals(2, result.runs[0].added)
        assertNotNull(documents.findByProviderAndProviderDocumentId("polygon", "poly-1"))
    }

    @Test
    fun `rerun marks known documents as duplicates`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(NEWS_PAGE)),
        )
        service().ingestCycle()

        val second = service().ingestCycle()

        assertEquals(1, second.runs.size)
        assertEquals(IngestionStatus.SUCCESS, second.runs[0].status)
        assertEquals(2, second.runs[0].fetched)
        assertEquals(0, second.runs[0].added)
        assertEquals(2, second.runs[0].duplicates)
    }

    @Test
    fun `falls back to secondary provider when primary fails`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news"))
                .willReturn(aResponse().withStatus(500)),
        )
        wireMock.stubFor(
            get(urlPathEqualTo("/api/v1/company-news")).willReturn(okJson(FINNHUB_NEWS)),
        )

        val result = service().ingestCycle()

        assertEquals(2, result.runs.size)
        assertEquals(IngestionStatus.FAILED, result.runs[0].status)
        assertEquals(IngestionStatus.SUCCESS, result.runs[1].status)
        assertEquals("finnhub", result.runs[1].provider)
        assertNotNull(documents.findByProviderAndProviderDocumentId("finnhub", "9"))
    }

    @Test
    fun `rate limited primary without fallback ends partial`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news"))
                .willReturn(aResponse().withStatus(429)),
        )

        val result = service(provider = "polygon", fallback = "none").ingestCycle()

        assertEquals(1, result.runs.size)
        assertEquals(IngestionStatus.PARTIAL, result.runs[0].status)
    }

    companion object {
        @RegisterExtension
        @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        const val NEWS_PAGE = """{
  "results": [
    {"id": "poly-1", "title": "Dell raises forecast", "description": "Dell raised.", "article_url": "https://example.com/a", "published_utc": "2026-09-16T14:30:00Z", "tickers": ["DELL"]},
    {"id": "poly-2", "title": "Unknown crypto news", "description": "Something.", "article_url": "https://example.com/b", "published_utc": "2026-09-16T15:30:00Z", "tickers": ["XYZ"]}
  ],
  "status": "OK", "request_id": "r", "count": 2
}"""

        const val FINNHUB_NEWS = """[
  {"category": "company", "datetime": 1758028200, "headline": "Dell raises forecast", "id": 9, "image": "", "related": "DELL", "source": "Reuters", "summary": "Dell raised.", "url": "https://example.com/a"}
]"""
    }
}
