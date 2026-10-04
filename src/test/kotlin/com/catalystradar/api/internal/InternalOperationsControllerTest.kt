package com.catalystradar.api.internal

import com.catalystradar.application.operations.*
import com.catalystradar.application.operations.OperationsConfiguration
import com.catalystradar.persistence.event.EventSearchPage
import com.catalystradar.security.ApiKeyService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID

@WebMvcTest(InternalOperationsController::class, properties = ["catalyst.internal.admin-key=test-admin"])
@Import(OperationsConfiguration::class)
class InternalOperationsControllerTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @MockitoBean private lateinit var operations: OperationsService
    @MockitoBean private lateinit var apiKeys: ApiKeyService

    @Test
    fun `config is mapped to a secret safe response and not cached`() {
        `when`(operations.config()).thenReturn(
            OperationsConfig(
                generatedAt = Instant.parse("2026-10-04T12:00:00Z"),
                publicApiAuthEnabled = false,
                ingestion = ScheduleConfig(true, 300),
                snapshots = ScheduleConfig(false, 86400),
                primaryNewsProvider = "polygon",
                fallbackNewsProvider = "finnhub",
                pipelineBatchSize = 20,
                pipelineMaxAttempts = 3,
                pipelineRetryDelaySeconds = 60,
                providers = listOf(ProviderConfig("polygon", true), ProviderConfig("finnhub", false), ProviderConfig("openai", true)),
                versions = ArtifactVersions("score-v1", "taxonomy-v1", "prompt-v1", "extractor-v1", "gpt-test", "embed-test"),
            ),
        )

        mockMvc.get("/internal/operations/config") { header("X-Admin-Key", "test-admin") }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.providers[0].name") { value("polygon") }
            jsonPath("$.providers[0].configured") { value(true) }
            jsonPath("$.providers[1].configured") { value(false) }
            jsonPath("$.versions.score") { value("score-v1") }
            jsonPath("$.providers[0].apiKey") { doesNotExist() }
        }
    }

    @Test
    fun `document reads require admin credentials even when public auth is disabled`() {
        mockMvc.get("/internal/operations/documents").andExpect {
            status { isForbidden() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `invalid page bounds return a stable problem without caching`() {
        mockMvc.get("/internal/operations/documents?limit=101") { header("X-Admin-Key", "test-admin") }.andExpect {
            status { isBadRequest() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `every operations route requires admin credentials and is never cached`() {
        val documentId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val modelRunId = UUID.randomUUID()
        val paths = listOf(
            "/internal/operations/config",
            "/internal/operations/overview",
            "/internal/operations/documents",
            "/internal/operations/documents/$documentId",
            "/internal/operations/documents/$documentId/body",
            "/internal/operations/documents/$documentId/events",
            "/internal/operations/documents/$documentId/attempts",
            "/internal/operations/documents/$documentId/model-runs",
            "/internal/operations/runs",
            "/internal/operations/runs/$runId",
            "/internal/operations/runs/$runId/issues",
            "/internal/operations/ingestion-runs",
            "/internal/operations/model-runs",
            "/internal/operations/model-runs/$modelRunId",
            "/internal/operations/model-summary",
        )

        paths.forEach { path ->
            mockMvc.get(path).andExpect {
                status { isForbidden() }
                header { string("Cache-Control", "no-store") }
                jsonPath("$.code") { value("FORBIDDEN") }
            }
        }
    }

    @Test
    fun `document filters are normalized and response preserves explicit nulls`() {
        val id = UUID.randomUUID()
        val generatedAt = Instant.parse("2026-10-04T12:00:00Z")
        val item = DocumentListItem(
            id = id,
            provider = "polygon",
            title = "Announcement",
            publishedAt = null,
            discoveredAt = generatedAt,
            createdAt = generatedAt,
            state = DocumentState.RETRYABLE_ERROR,
            attemptCount = 2,
            nextAttemptAt = null,
            updatedAt = null,
            lastErrorCode = "TEMPORARY_UNAVAILABLE",
            lastErrorMessage = "Provider temporarily unavailable; a retry may be scheduled.",
            tickers = listOf("ACME"),
            tickersTruncated = false,
            eventReports = 0,
            canonicalClusters = 0,
            firstIngestionRunId = null,
        )
        val page = OperationsPage(generatedAt, null, listOf(item), 25, null)
        `when`(operations.documents(any())).thenReturn(page)

        mockMvc.get("/internal/operations/documents?status=RETRYABLE_ERROR&provider=polygon&ticker=acme&dueOnly=true") {
            param("q", " Announcement ")
            header("X-Admin-Key", "test-admin")
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.window") { value(org.hamcrest.Matchers.nullValue()) }
            jsonPath("$.items[0].state") { value("RETRYABLE_ERROR") }
            jsonPath("$.items[0].publishedAt") { value(org.hamcrest.Matchers.nullValue()) }
            jsonPath("$.items[0].tickersTruncated") { value(false) }
            jsonPath("$.items[0].firstIngestionRunId") { value(org.hamcrest.Matchers.nullValue()) }
        }
        val queryCaptor = argumentCaptor<DocumentQuery>()
        org.mockito.kotlin.verify(operations).documents(queryCaptor.capture())
        kotlin.test.assertEquals(setOf(DocumentState.RETRYABLE_ERROR), queryCaptor.firstValue.states)
        kotlin.test.assertEquals("ACME", queryCaptor.firstValue.normalizedTicker)
        kotlin.test.assertEquals("Announcement", queryCaptor.firstValue.normalizedTitle)
        kotlin.test.assertEquals(true, queryCaptor.firstValue.dueOnly)
    }

    @Test
    fun `invalid enum UUID and mixed exact ingestion filters return stable problems`() {
        listOf(
            "/internal/operations/documents?status=NOT_A_STATE",
            "/internal/operations/documents?runId=not-a-uuid",
            "/internal/operations/ingestion-runs?ingestionRunId=${UUID.randomUUID()}&provider=polygon",
            "/internal/operations/ingestion-runs?ingestionRunId=${UUID.randomUUID()}&status=SUCCESS",
            "/internal/operations/ingestion-runs?ingestionRunId=${UUID.randomUUID()}&runId=${UUID.randomUUID()}",
            "/internal/operations/ingestion-runs?ingestionRunId=${UUID.randomUUID()}&cursor=abc",
            "/internal/operations/ingestion-runs?ingestionRunId=${UUID.randomUUID()}&range=24h",
            "/internal/operations/ingestion-runs?ingestionRunId=${UUID.randomUUID()}&from=2026-10-03T12:00:00Z",
            "/internal/operations/ingestion-runs?ingestionRunId=${UUID.randomUUID()}&to=2026-10-04T12:00:00Z",
            "/internal/operations/overview?from=2026-10-04T12:00:00Z",
        ).forEach { path ->
            mockMvc.get(path) { header("X-Admin-Key", "test-admin") }.andExpect {
                status { isBadRequest() }
                header { string("Cache-Control", "no-store") }
                jsonPath("$.code") { value("INVALID_REQUEST") }
            }
        }
    }

    @Test
    fun `exact ingestion id lookup is independent from the activity window`() {
        val id = UUID.randomUUID()
        val startedAt = Instant.parse("2026-08-01T00:00:00Z")
        val inspection = IngestionRunInspection(
            id = id,
            provider = "finnhub",
            status = com.catalystradar.application.ingestion.IngestionStatus.SUCCESS,
            fetched = 4,
            added = 2,
            duplicates = 2,
            error = null,
            finishedAt = startedAt.plusSeconds(30),
            startedAt = startedAt,
            durationMs = 30000,
            runId = null,
            errorCode = null,
        )
        `when`(operations.ingestionRuns(any())).thenReturn(OperationsPage(Instant.now(), null, listOf(inspection), 25, null))

        mockMvc.get("/internal/operations/ingestion-runs?ingestionRunId=$id") { header("X-Admin-Key", "test-admin") }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.window") { value(org.hamcrest.Matchers.nullValue()) }
            jsonPath("$.items[0].id") { value(id.toString()) }
            jsonPath("$.items[0].startedAt") { value(startedAt.toString()) }
            jsonPath("$.items[0].durationMs") { value(30000) }
            jsonPath("$.items[0].errorCode") { value(org.hamcrest.Matchers.nullValue()) }
        }
        val queryCaptor = argumentCaptor<IngestionQuery>()
        org.mockito.kotlin.verify(operations).ingestionRuns(queryCaptor.capture())
        kotlin.test.assertNull(queryCaptor.firstValue.window)
        kotlin.test.assertEquals(id, queryCaptor.firstValue.ingestionRunId)
    }

    @Test
    fun `missing document maps to safe not found problem without caching`() {
        val id = UUID.randomUUID()
        `when`(operations.document(id)).thenThrow(OperationsResourceNotFoundException(OperationsResource.DOCUMENT, id))

        mockMvc.get("/internal/operations/documents/$id") { header("X-Admin-Key", "test-admin") }.andExpect {
            status { isNotFound() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.code") { value("DOCUMENT_NOT_FOUND") }
            jsonPath("$.detail") { value("Source document was not found") }
        }
    }

    @Test
    fun `missing run and model map to their stable resource codes`() {
        val runId = UUID.randomUUID()
        val modelId = UUID.randomUUID()
        `when`(operations.run(runId)).thenThrow(OperationsResourceNotFoundException(OperationsResource.OPERATION_RUN, runId))
        `when`(operations.modelRun(modelId)).thenThrow(OperationsResourceNotFoundException(OperationsResource.MODEL_RUN, modelId))

        mockMvc.get("/internal/operations/runs/$runId") { header("X-Admin-Key", "test-admin") }.andExpect {
            status { isNotFound() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.code") { value("OPERATION_RUN_NOT_FOUND") }
        }
        mockMvc.get("/internal/operations/model-runs/$modelId") { header("X-Admin-Key", "test-admin") }.andExpect {
            status { isNotFound() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.code") { value("MODEL_RUN_NOT_FOUND") }
        }
    }

    @Test
    fun `document events retain the existing cursor response format`() {
        val id = UUID.randomUUID()
        `when`(operations.documentEvents(eq(id), any())).thenReturn(EventSearchPage(emptyList(), "legacy-event-cursor"))

        mockMvc.get("/internal/operations/documents/$id/events?limit=7&cursor=legacy-event-cursor") {
            header("X-Admin-Key", "test-admin")
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.events.length()") { value(0) }
            jsonPath("$.nextCursor") { value("legacy-event-cursor") }
            jsonPath("$.items") { doesNotExist() }
        }
        val pageCaptor = argumentCaptor<PageRequest>()
        org.mockito.kotlin.verify(operations).documentEvents(eq(id), pageCaptor.capture())
        kotlin.test.assertEquals(PageRequest(limit = 7, cursor = "legacy-event-cursor"), pageCaptor.firstValue)
    }

    @Test
    fun `malformed operations cursor is a stable bad request`() {
        `when`(operations.documents(any())).thenThrow(IllegalArgumentException("invalid cursor"))

        mockMvc.get("/internal/operations/documents?cursor=bad") { header("X-Admin-Key", "test-admin") }.andExpect {
            status { isBadRequest() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }
}
