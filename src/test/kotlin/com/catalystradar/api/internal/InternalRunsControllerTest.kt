package com.catalystradar.api.internal

import com.catalystradar.application.ingestion.IngestionStatus
import com.catalystradar.persistence.extraction.ModelRunRecord
import com.catalystradar.persistence.extraction.ModelRunStore
import com.catalystradar.persistence.ingestion.IngestionRunRecord
import com.catalystradar.persistence.ingestion.IngestionRunStore
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
                IngestionRunRecord(
                    id = UUID.randomUUID(),
                    provider = "polygon",
                    status = IngestionStatus.SUCCESS,
                    fetched = 10,
                    added = 7,
                    duplicates = 3,
                    error = null,
                    finishedAt = t0,
                ),
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
