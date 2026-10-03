package com.catalystradar.e2e

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventEvidence
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.ports.Embedding
import com.catalystradar.ports.EmbeddingProvider
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.ports.ExtractedEvent
import com.catalystradar.ports.ExtractionRequest
import com.catalystradar.ports.ExtractionResult
import com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v0.1 acceptance scenario: real-shaped provider news for a known
 * company flows through ingestion, extraction, normalization,
 * clustering, and scoring into snapshots served by the public and
 * discovery APIs. External LLMs stay stubbed; HTTP and SQL are real.
 *
 * Uses a ZZ-prefixed fixture ticker and scrubs its rows afterwards so
 * the shared container never leaks into other suites.
 */
@SpringBootTest(
    properties = ["catalyst.internal.admin-key=e2e-admin"],
)
@AutoConfigureMockMvc
class CatalystPipelineE2ETest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var jdbc: JdbcClient

    @MockitoBean
    private lateinit var extraction: EventExtractionProvider

    @MockitoBean
    private lateinit var embeddings: EmbeddingProvider

    @Test
    fun `news becomes discovery intelligence`() = runTest {
        companies.save(Company(ticker = "ZZE", name = "ZZ Engine"))
        val published = Instant.now().minusSeconds(3600).toString()
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(newsPage(published))),
        )
        whenever(extraction.extract(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<ExtractionRequest>(0)
            val type = when {
                "raise" in request.document.title -> EventType.GUIDANCE_RAISE
                "beat" in request.document.title -> EventType.EARNINGS_BEAT
                "takeover" in request.document.title -> EventType.TAKEOVER_TARGET
                else -> EventType.CONTRACT_WIN
            }
            ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    ExtractedEvent(
                        ticker = "ZZE",
                        type = type,
                        direction = Direction.POSITIVE,
                        confidence = 1.0,
                        magnitude = null,
                        surprise = null,
                        materiality = null,
                        expectedHorizon = EventHorizon.WEEKS,
                        directness = Directness.DIRECT,
                        eventTimestamp = null,
                        evidence = listOf(EventEvidence("quote", null)),
                        attributes = emptyMap(),
                    ),
                ),
            )
        }
        whenever(embeddings.embed(any())).thenReturn(
            Embedding(List(1536) { if (it == 0) 1f else 0f }, "fake"),
        )

        // The fixture context pins both schedulers off, so nothing may reach
        // the provider before this test triggers the pipeline itself.
        assertTrue(
            wireMock.findAll(anyRequestedFor(anyUrl())).isEmpty(),
            "fixture startup must not issue a provider request: ${wireMock.findAll(anyRequestedFor(anyUrl()))}",
        )

        mockMvc.post("/internal/ingestion/runs") {
            accept = MediaType.APPLICATION_JSON
            header("X-Admin-Key", "e2e-admin")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("SUCCESS") }
            jsonPath("$.documentsProcessed") { value(4) }
        }

        mockMvc.get("/v1/companies/ZZE/catalyst") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.state") { value("BUILDING") }
            jsonPath("$.scoreVersion") { value("score-v1") }
        }

        mockMvc.get("/v1/companies/ZZE/events?limit=10") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.events.length()") { value(4) }
        }

        val discovery = mockMvc.get("/v1/discovery/catalyzed?minScore=40") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
        }.andReturn()
        val results = ObjectMapper().readTree(discovery.response.contentAsString).path("results")
        var found: String? = null
        for (entry in results) {
            if (entry.path("ticker").asText() == "ZZE") found = entry.path("state").asText()
        }
        assertEquals("BUILDING", found)
    }

    @AfterEach
    fun scrub() {
        val companyId = jdbc.sql("SELECT id FROM companies WHERE ticker = 'ZZE'")
            .query(UUID::class.java).optional()
        companyId.ifPresent { id ->
            val docs = jdbc.sql(
                "SELECT DISTINCT source_document_id FROM events WHERE company_id = :id AND source_document_id IS NOT NULL",
            ).param("id", id).query(UUID::class.java).list()
            jdbc.sql("DELETE FROM events WHERE company_id = :id").param("id", id).update()
            jdbc.sql("DELETE FROM event_clusters WHERE company_id = :id").param("id", id).update()
            jdbc.sql("DELETE FROM catalyst_snapshots WHERE company_id = :id").param("id", id).update()
            jdbc.sql("DELETE FROM state_transitions WHERE company_id = :id").param("id", id).update()
            docs.forEach { doc ->
                jdbc.sql("DELETE FROM source_documents WHERE id = :id").param("id", doc).update()
            }
            jdbc.sql("DELETE FROM companies WHERE id = :id").param("id", id).update()
        }
    }

    private fun newsPage(published: String): String {
        fun article(id: String, marker: String) = """
    {"id": "$id", "title": "ZZ $marker story", "description": "ZZ $marker body.", "article_url": "https://example.com/$id", "published_utc": "$published", "tickers": ["ZZE"]}"""
        return """{
  "results": [
${article("e2e-1", "raise")},
${article("e2e-2", "beat")},
${article("e2e-3", "contract")},
${article("e2e-4", "takeover")}
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

        @JvmStatic
        @DynamicPropertySource
        fun polygon(registry: DynamicPropertyRegistry) {
            registry.add("catalyst.polygon.base-url", wireMock::baseUrl)
        }
    }
}
