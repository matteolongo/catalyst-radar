package com.catalystradar.api.internal

import com.catalystradar.application.ingestion.IngestionStatus
import com.catalystradar.persistence.extraction.ModelRunRecord
import com.catalystradar.persistence.extraction.ModelRunStore
import com.catalystradar.persistence.ingestion.IngestionRunRow
import com.catalystradar.persistence.ingestion.IngestionRunStore
import com.catalystradar.persistence.ingestion.toRecord
import com.catalystradar.security.ApiKeyService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID

@WebMvcTest(
    InternalRunsController::class,
    properties = ["catalyst.internal.admin-key=test-admin"],
)
class InternalRunsControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var ingestionRuns: IngestionRunStore

    @MockitoBean
    private lateinit var modelRuns: ModelRunStore

    @MockitoBean
    private lateinit var apiKeys: ApiKeyService

    private val t0 = Instant.parse("2026-09-16T10:00:00Z")

    @Test
    fun `lists recent ingestion runs`() {
        `when`(ingestionRuns.listRecent(20)).thenReturn(
            listOf(
                IngestionRunRow(
                    id = UUID.randomUUID(),
                    provider = "polygon",
                    status = IngestionStatus.SUCCESS.name,
                    cursor = null,
                    fetched = 10,
                    added = 7,
                    duplicates = 3,
                    error = null,
                    startedAt = t0.minusSeconds(60),
                    finishedAt = t0,
                ).toRecord(),
            ),
        )

        mockMvc.get("/internal/ingestion/runs") {
            accept = MediaType.APPLICATION_JSON
            header("X-Admin-Key", "test-admin")
        }.andExpect {
            status { isOk() }
            jsonPath("$.runs.length()") { value(1) }
            jsonPath("$.runs[0].provider") { value("polygon") }
            jsonPath("$.runs[0].status") { value("SUCCESS") }
            jsonPath("$.runs[0].startedAt") { value(t0.minusSeconds(60).toString()) }
            jsonPath("$.runs[0].durationMs") { value(60000) }
            jsonPath("$.items") { doesNotExist() }
        }
    }

    @Test
    fun `legacy ingestion response includes optional operation and error metadata`() {
        val operationRunId = UUID.randomUUID()
        `when`(ingestionRuns.listRecent(20)).thenReturn(
            listOf(
                IngestionRunRow(
                    id = UUID.randomUUID(),
                    provider = "polygon",
                    status = IngestionStatus.PARTIAL.name,
                    cursor = null,
                    fetched = 10,
                    added = 7,
                    duplicates = 3,
                    error = "legacy provider error",
                    startedAt = t0.minusSeconds(5),
                    finishedAt = t0,
                    runId = operationRunId,
                    errorCode = "RATE_LIMITED",
                ).toRecord(),
            ),
        )

        mockMvc.get("/internal/ingestion/runs") { header("X-Admin-Key", "test-admin") }.andExpect {
            status { isOk() }
            jsonPath("$.runs[0].runId") { value(operationRunId.toString()) }
            jsonPath("$.runs[0].errorCode") { value("RATE_LIMITED") }
            jsonPath("$.runs[0].durationMs") { value(5000) }
        }
    }

    @Test
    fun `lists recent model runs with cost metadata`() {
        `when`(modelRuns.listRecent(50)).thenReturn(
            listOf(
                ModelRunRecord(
                    id = UUID.randomUUID(),
                    provider = "openai",
                    operation = "extract",
                    model = "gpt-4o-mini",
                    promptVersion = "event-extractor-v1",
                    extractorVersion = "event-extractor-v1",
                    sourceDocumentId = null,
                    inputTokens = 100,
                    outputTokens = 50,
                    latencyMs = 1200L,
                    estimatedCost = java.math.BigDecimal("0.000045"),
                    success = true,
                    error = null,
                    createdAt = t0,
                    processingAttemptId = UUID.randomUUID(),
                ),
            ),
        )

        mockMvc.get("/internal/model-runs") {
            accept = MediaType.APPLICATION_JSON
            header("X-Admin-Key", "test-admin")
        }.andExpect {
            status { isOk() }
            jsonPath("$.runs.length()") { value(1) }
            jsonPath("$.runs[0].operation") { value("extract") }
            jsonPath("$.runs[0].inputTokens") { value(100) }
            jsonPath("$.runs[0].attemptId") { exists() }
            jsonPath("$.runs[0].runId") { value(org.hamcrest.Matchers.nullValue()) }
            jsonPath("$.runs[0].errorCode") { value(org.hamcrest.Matchers.nullValue()) }
            jsonPath("$.items") { doesNotExist() }
        }
    }

    @Test
    fun `run history without admin key is forbidden`() {
        mockMvc.get("/internal/ingestion/runs") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `out-of-range limit returns 400`() {
        mockMvc.get("/internal/ingestion/runs?limit=500") {
            accept = MediaType.APPLICATION_JSON
            header("X-Admin-Key", "test-admin")
        }.andExpect {
            status { isBadRequest() }
        }
    }
}
