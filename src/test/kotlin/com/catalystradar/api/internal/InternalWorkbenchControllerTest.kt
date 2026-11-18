package com.catalystradar.api.internal

import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.ingestion.DocumentProcessingStatus
import com.catalystradar.application.ingestion.IngestionProperties
import com.catalystradar.application.pipeline.DocumentExtraction
import com.catalystradar.application.pipeline.ExtractionStepResult
import com.catalystradar.application.pipeline.PipelineProperties
import com.catalystradar.application.workbench.MarketScanResult
import com.catalystradar.application.workbench.PipelineBusyException
import com.catalystradar.application.workbench.TickerFetchResult
import com.catalystradar.application.workbench.WorkbenchService
import com.catalystradar.persistence.workbench.NewsSymbolPage
import com.catalystradar.persistence.workbench.NewsSymbolRow
import com.catalystradar.persistence.workbench.NewsSymbolStore
import com.catalystradar.security.ApiKeyService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

@Import(IngestionProperties::class, PipelineProperties::class)
@WebMvcTest(InternalWorkbenchController::class, properties = ["catalyst.internal.admin-key=test-admin"])
class InternalWorkbenchControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @MockitoBean private lateinit var apiKeys: ApiKeyService
    @MockitoBean private lateinit var workbench: WorkbenchService
    @MockitoBean private lateinit var symbols: NewsSymbolStore

    private val at = Instant.parse("2026-11-17T10:00:00Z")

    private fun postJson(path: String, body: String) = mockMvc.post(path) {
        contentType = MediaType.APPLICATION_JSON
        header("X-Admin-Key", "test-admin")
        content = body
    }

    @Test
    fun `scan uses the requested window and reports what was stored`() = runTest {
        whenever(workbench.scan(any(), any())).thenReturn(MarketScanResult(at.minusSeconds(3600), at, emptyList(), 12))

        postJson("/internal/workbench/scans", """{"lookback":"PT6H"}""").andExpect {
            status { isOk() }
            jsonPath("$.newDocuments") { value(12) }
        }
        val from = argumentCaptor<Instant>()
        val now = argumentCaptor<Instant>()
        verify(workbench).scan(from.capture(), now.capture())
        assertEquals(Duration.ofHours(6), Duration.between(from.firstValue, now.firstValue))
    }

    @Test
    fun `scan refuses a window above the market scan maximum`() {
        postJson("/internal/workbench/scans", """{"lookback":"P4D"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
        verifyNoInteractions(workbench)
    }

    @Test
    fun `a step refused because the pipeline is busy is a conflict`() = runTest {
        whenever(workbench.scan(any(), any())).thenThrow(PipelineBusyException())

        postJson("/internal/workbench/scans", "{}").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("PIPELINE_BUSY") }
        }
    }

    @Test
    fun `lists tracked and untracked symbols in the news`() {
        whenever(symbols.symbols(any())).thenReturn(
            NewsSymbolPage(
                listOf(
                    NewsSymbolRow("SLS", null, false, 6, 0, "SELLAS jumps on trial data", at),
                    NewsSymbolRow("TSN", "Tyson Foods", true, 4, 3, "Tyson beats estimates", at),
                ),
                total = 2,
            ),
        )

        mockMvc.get("/internal/workbench/symbols?limit=20") { header("X-Admin-Key", "test-admin") }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.total") { value(2) }
            jsonPath("$.symbols[0].ticker") { value("SLS") }
            jsonPath("$.symbols[0].tracked") { value(false) }
            jsonPath("$.symbols[1].queuedDocuments") { value(3) }
        }
    }

    @Test
    fun `symbols refuse a window older than thirty days`() {
        val since = Instant.now().minus(Duration.ofDays(31))
        mockMvc.get("/internal/workbench/symbols?since=$since") { header("X-Admin-Key", "test-admin") }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `fetch normalizes tickers and uses the per-symbol default window`() = runTest {
        whenever(workbench.fetch(any(), any(), any())).thenReturn(listOf(TickerFetchResult("TSN", emptyList(), 5)))

        postJson("/internal/workbench/fetches", """{"tickers":[" tsn ","TSN"]}""").andExpect {
            status { isOk() }
            jsonPath("$.tickers[0].ticker") { value("TSN") }
            jsonPath("$.tickers[0].newDocuments") { value(5) }
        }
        val from = argumentCaptor<Instant>()
        val now = argumentCaptor<Instant>()
        verify(workbench).fetch(eq(listOf("TSN")), from.capture(), now.capture())
        assertEquals(Duration.ofDays(7), Duration.between(from.firstValue, now.firstValue))
    }

    @Test
    fun `fetch of an untracked symbol is not found`() = runTest {
        whenever(workbench.fetch(any(), any(), any())).thenThrow(CompanyNotFoundException("SLS"))

        postJson("/internal/workbench/fetches", """{"tickers":["SLS"]}""").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("COMPANY_NOT_FOUND") }
        }
    }

    @Test
    fun `fetch refuses more than ten tickers`() {
        val tickers = (1..11).joinToString(",") { "\"T$it\"" }
        postJson("/internal/workbench/fetches", """{"tickers":[$tickers]}""").andExpect { status { isBadRequest() } }
        verifyNoInteractions(workbench)
    }

    @Test
    fun `extraction reports each chosen document`() = runTest {
        val done = UUID.randomUUID()
        val old = UUID.randomUUID()
        whenever(workbench.extract(any(), any())).thenReturn(
            ExtractionStepResult(
                UUID.randomUUID(),
                "SUCCESS",
                listOf(
                    DocumentExtraction(done, DocumentProcessingStatus.COMPLETED, eventsInserted = 2),
                    DocumentExtraction(old, DocumentProcessingStatus.COMPLETED, refused = "ALREADY_PROCESSED"),
                ),
            ),
        )

        postJson("/internal/workbench/extractions", """{"documentIds":["$done","$old"]}""").andExpect {
            status { isOk() }
            jsonPath("$.documents[0].eventsInserted") { value(2) }
            jsonPath("$.documents[1].refused") { value("ALREADY_PROCESSED") }
        }
    }

    @Test
    fun `extraction refuses more documents than one batch`() {
        val ids = (1..51).joinToString(",") { "\"${UUID.randomUUID()}\"" }
        postJson("/internal/workbench/extractions", """{"documentIds":[$ids]}""").andExpect { status { isBadRequest() } }
        verifyNoInteractions(workbench)
    }

    @Test
    fun `workbench steps require the admin key`() {
        mockMvc.post("/internal/workbench/scans") { contentType = MediaType.APPLICATION_JSON; content = "{}" }
            .andExpect { status { isForbidden() } }
    }
}
