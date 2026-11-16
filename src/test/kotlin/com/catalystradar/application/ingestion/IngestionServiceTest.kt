package com.catalystradar.application.ingestion

import kotlin.test.assertNull

import com.catalystradar.adapters.finnhub.FinnhubNewsProvider
import com.catalystradar.adapters.finnhub.FinnhubProperties
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
import com.catalystradar.operations.OperationsFixtures
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.concurrent.CancellationException
import com.catalystradar.ports.*

@Transactional
class IngestionServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Autowired
    private lateinit var registrations: SourceDocumentRegistrationService

    @Autowired
    private lateinit var documentCompanies: SourceDocumentCompanyStore

    @Autowired
    private lateinit var processing: DocumentProcessingStore

    @Autowired
    private lateinit var runs: IngestionRunStore

    @Autowired private lateinit var jdbc: JdbcClient

    private val polygon = PolygonNewsProvider(
        RestClient.builder(),
        PolygonProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
    )
    private val finnhub = FinnhubNewsProvider(
        RestClient.builder(),
        FinnhubProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
    )

    private val meters = SimpleMeterRegistry()

    private fun service(
        provider: String = "polygon",
        fallback: String = "finnhub",
        maxPagesPerChunk: Int = 10,
        providers: List<NewsProvider> = listOf(polygon, finnhub),
        scanMode: ScanMode = ScanMode.TICKERS,
    ) = IngestionService(
        providers = providers,
        properties = IngestionProperties(provider = provider, fallbackProvider = fallback, maxPagesPerChunk = maxPagesPerChunk, scanMode = scanMode),
        companies = companies,
        registrations = registrations,
        runs = runs,
        metrics = CatalystMetrics(meters),
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

    private fun pageOf(id: String, nextCursor: String?): String {
        val next = nextCursor?.let { ", \"next_url\": \"${wireMock.baseUrl()}/v2/reference/news?cursor=$it\"" } ?: ""
        return """{"results": [{"id": "$id", "title": "Dell news $id", "description": "Dell $id.", "article_url": "https://example.com/$id", "published_utc": "2026-09-16T14:30:00Z", "tickers": ["DELL"]}], "status": "OK"$next}"""
    }

    private fun stubPage(cursor: String?, body: String) {
        val request = get(urlPathEqualTo("/v2/reference/news"))
        wireMock.stubFor(
            (if (cursor == null) request.withQueryParam("cursor", absent()) else request.withQueryParam("cursor", equalTo(cursor)))
                .willReturn(okJson(body)),
        )
    }

    @Test
    fun `follows provider pages until the window is exhausted`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        stubPage(null, pageOf("page-1", "p2"))
        stubPage("p2", pageOf("page-2", "p3"))
        stubPage("p3", pageOf("page-3", null))

        val run = service().ingestCycle().runs.single()

        assertEquals(IngestionStatus.SUCCESS, run.status)
        assertEquals(3, run.fetched)
        assertEquals(3, run.added)
        listOf("page-1", "page-2", "page-3").forEach { assertNotNull(documents.findByProviderAndProviderDocumentId("polygon", it)) }
    }

    @Test
    fun `page bound marks the run truncated without using the fallback provider`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        stubPage(null, pageOf("page-1", "p2"))
        stubPage("p2", pageOf("page-2", "p3"))
        stubPage("p3", pageOf("page-3", null))

        val result = service(maxPagesPerChunk = 2).ingestCycle()

        val run = result.runs.single()
        assertEquals(IngestionStatus.PARTIAL, run.status)
        assertEquals(IngestionService.WINDOW_TRUNCATED, run.errorCode)
        assertEquals(2, run.added)
        wireMock.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/company-news")))
    }

    @Test
    fun `a repeated provider cursor stops paging`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        stubPage(null, pageOf("page-1", "loop"))
        stubPage("loop", pageOf("page-2", "loop"))

        val run = service().ingestCycle().runs.single()

        assertEquals(IngestionService.WINDOW_TRUNCATED, run.errorCode)
        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/v2/reference/news")))
    }

    @Test
    fun `an unexpected provider failure still records one failed duration sample`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        val broken = object : NewsProvider {
            override val name = "polygon"
            override suspend fun fetch(request: NewsFetchRequest): NewsFetchResult = throw IllegalStateException("adapter bug")
        }

        assertFailsWith<IllegalStateException> { service(providers = listOf(broken)).ingestCycle() }

        assertEquals(1L, meters.timer("catalyst_ingestion_run_duration_seconds", "provider", "polygon", "status", "failed").count())
        assertEquals(1, meters.find("catalyst_ingestion_run_duration_seconds").timers().sumOf { it.count() }.toInt())
    }

    @Test
    fun `a successful run records its duration under its status`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(NEWS_PAGE)))

        service().ingestCycle()

        assertEquals(1L, meters.timer("catalyst_ingestion_run_duration_seconds", "provider", "polygon", "status", "success").count())
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
    fun `queues only documents linked to resolved active companies`() = runTest {
        val dell = companies.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(NEWS_PAGE)),
        )

        service().ingestCycle()

        val supported = requireNotNull(documents.findByProviderAndProviderDocumentId("polygon", "poly-1"))
        val unsupported = requireNotNull(documents.findByProviderAndProviderDocumentId("polygon", "poly-2"))

        assertEquals(listOf(dell.id), documentCompanies.findCompanyIds(supported.id))
        assertEquals(DocumentProcessingStatus.PENDING, processing.findBySourceDocumentId(supported.id)?.status)
        assertEquals(emptyList(), documentCompanies.findCompanyIds(unsupported.id))
        assertEquals(null, processing.findBySourceDocumentId(unsupported.id))
    }

    @Test
    fun `ticker scan asks polygon for one ticker per request`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        companies.save(Company(ticker = "SMCI", name = "Super Micro"))
        wireMock.stubFor(get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(NEWS_PAGE)))

        service(scanMode = ScanMode.TICKERS).ingestCycle()

        val tickers = wireMock.findAll(getRequestedFor(urlPathEqualTo("/v2/reference/news")))
            .map { it.queryParameter("ticker").values() }
        assertEquals(setOf(listOf("DELL"), listOf("SMCI")), tickers.toSet())
        assertEquals(2, tickers.size)
    }

    @Test
    fun `mixed-case share-class tickers are neither resolved nor kept as candidates`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news")).willReturn(
                okJson("""{"results": [{"id": "share-class", "title": "Bank preferreds", "description": "Rates.", "published_utc": "2026-09-16T14:30:00Z", "tickers": ["DELL", "JPMpC", "smci"]}], "status": "OK"}"""),
            ),
        )

        service(scanMode = ScanMode.MARKET).ingestCycle(Instant.parse("2026-09-16T15:00:00Z"))

        val document = assertNotNull(documents.findByProviderAndProviderDocumentId("polygon", "share-class"))
        val candidates = jdbc.sql("SELECT ticker FROM source_document_unresolved_tickers WHERE source_document_id = :id ORDER BY ticker")
            .param("id", document.id).query { rs, _ -> rs.getString("ticker") }.list()
        assertEquals(listOf("SMCI"), candidates)
    }

    @Test
    fun `market scan asks the primary once without tickers and keeps untracked tickers as candidates`() = runTest {
        repeat(25) { companies.save(Company(ticker = "T$it", name = "Tracked $it")) }
        companies.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(MARKET_PAGE)))

        val run = service(scanMode = ScanMode.MARKET).ingestCycle(Instant.parse("2026-09-16T15:00:00Z")).runs.single()

        val requests = wireMock.findAll(getRequestedFor(urlPathEqualTo("/v2/reference/news")))
        assertEquals(1, requests.size, "one request instead of one per ticker chunk")
        assertTrue(requests.single().queryParams["ticker"] == null || !requests.single().queryParams["ticker"]!!.isPresent)
        assertEquals(IngestionStatus.SUCCESS, run.status)
        assertEquals(2, run.added)
        assertEquals(1, run.unresolved)
        val untracked = assertNotNull(documents.findByProviderAndProviderDocumentId("polygon", "m-2"))
        assertNull(processing.findBySourceDocumentId(untracked.id))
    }

    @Test
    fun `market scan falls back per ticker when the primary fails`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(get(urlPathEqualTo("/v2/reference/news")).willReturn(aResponse().withStatus(500)))
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/company-news")).willReturn(okJson(FINNHUB_NEWS)))

        val result = service(scanMode = ScanMode.MARKET).ingestCycle(Instant.parse("2026-10-04T12:00:00Z"))

        assertEquals(listOf("polygon", "finnhub"), result.runs.map { it.provider })
        assertEquals(IngestionStatus.SUCCESS, result.runs[1].status)
        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/v1/company-news")).withQueryParam("symbol", equalTo("DELL")))
    }

    @Test
    fun `market scan on a provider without support fetches per ticker`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/company-news")).willReturn(okJson(FINNHUB_NEWS)))

        val run = service(provider = "finnhub", fallback = "none", scanMode = ScanMode.MARKET)
            .ingestCycle(Instant.parse("2026-10-04T12:00:00Z")).runs.single()

        assertEquals(IngestionStatus.SUCCESS, run.status)
        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/v1/company-news")).withQueryParam("symbol", equalTo("DELL")))
    }

    @Test
    fun `the same article ingested in both modes is stored once`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(MARKET_PAGE)))
        val now = Instant.parse("2026-09-16T15:00:00Z")

        service(scanMode = ScanMode.TICKERS).ingestCycle(now)
        val second = service(scanMode = ScanMode.MARKET).ingestCycle(now).runs.single()

        assertEquals(0, second.added)
        assertEquals(2, second.duplicates)
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

        val now = Instant.parse("2026-10-04T12:00:00Z")
        val operationId = OperationsFixtures(jdbc).run(now, "RUNNING")
        val started = Instant.now()
        val result = service().ingestCycle(now, operationId)

        assertEquals(2, result.runs.size)
        assertEquals(IngestionStatus.FAILED, result.runs[0].status)
        assertEquals(IngestionStatus.SUCCESS, result.runs[1].status)
        assertEquals("finnhub", result.runs[1].provider)
        assertNotEquals(result.runs[0].id, result.runs[1].id)
        assertEquals(listOf(operationId, operationId), result.runs.map { it.runId })
        assertEquals("TEMPORARY_UNAVAILABLE", result.runs[0].errorCode)
        val finished = Instant.now()
        assertTrue(result.runs.all { assertNotNull(it.startedAt) >= started && assertNotNull(it.startedAt) <= finished },
            "provider-run timestamps use actual time independently of ingestion cutoff")
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
        assertEquals("RATE_LIMITED", result.runs[0].errorCode)
    }

    @Test
    fun `provider failure that ends the run is reported over an earlier registration failure`() = runTest {
        repeat(21) { companies.save(Company(ticker = "ZZ${('A'.code + it).toChar()}", name = "Chunk $it")) }
        var calls = 0
        val provider = object : NewsProvider {
            override val name = "polygon"
            override suspend fun fetch(request: NewsFetchRequest): NewsFetchResult {
                if (calls++ > 0) throw ProviderException.RateLimited(60)
                return NewsFetchResult(listOf(
                    RawArticle(provider = name, providerArticleId = "invalid", url = null, publishedAt = null, title = "", body = "Invalid", tickers = request.tickers),
                    RawArticle(provider = name, providerArticleId = "valid", url = null, publishedAt = null, title = "Raise story", body = "Evidence", tickers = request.tickers),
                ), null)
            }
        }
        val result = IngestionService(listOf(provider), IngestionProperties(fallbackProvider = "none"), companies, registrations, runs,
            CatalystMetrics(SimpleMeterRegistry())).ingestCycle()
        val run = result.runs.single()
        assertEquals(IngestionStatus.PARTIAL, run.status)
        assertEquals("RATE_LIMITED", run.errorCode)
        assertEquals(2, run.fetched)
        assertEquals(1, run.added)
    }

    @Test
    fun `cancelled provider run retains already registered counts`() = runTest {
        repeat(21) { companies.save(Company(ticker = "ZZ${('A'.code + it).toChar()}", name = "Chunk $it")) }
        var calls = 0
        val provider = object : NewsProvider {
            override val name = "polygon"
            override suspend fun fetch(request: NewsFetchRequest): NewsFetchResult {
                if (calls++ > 0) throw CancellationException("cancel")
                return NewsFetchResult(listOf(RawArticle(provider = name, providerArticleId = "cancel-valid", url = null, publishedAt = null, title = "Raise story", body = "Evidence", tickers = request.tickers)), null)
            }
        }
        val before = runs.listRecent(100).map { it.id }.toSet()
        assertFailsWith<CancellationException> {
            IngestionService(listOf(provider), IngestionProperties(fallbackProvider = "none"), companies, registrations, runs,
                CatalystMetrics(SimpleMeterRegistry())).ingestCycle()
        }
        val run = runs.listRecent(100).single { it.id !in before }
        assertEquals(IngestionStatus.FAILED, run.status)
        assertEquals("CANCELLED", run.errorCode)
        assertEquals(1, run.fetched)
        assertEquals(1, run.added)
    }

    companion object {
        const val MARKET_PAGE = """{"results": [{"id": "m-1", "title": "Dell market story", "description": "Dell body.", "article_url": "https://example.com/m-1", "published_utc": "2026-09-16T14:30:00Z", "tickers": ["DELL"]},{"id": "m-2", "title": "Untracked story", "description": "Other body.", "article_url": "https://example.com/m-2", "published_utc": "2026-09-16T14:40:00Z", "tickers": ["ZZQ"]}], "status": "OK"}"""

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
