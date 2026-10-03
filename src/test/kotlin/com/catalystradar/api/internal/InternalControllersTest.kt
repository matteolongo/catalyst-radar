package com.catalystradar.api.internal

import com.catalystradar.application.catalyst.CatalystService
import com.catalystradar.application.catalyst.CatalystView
import com.catalystradar.application.catalyst.CatalystViewService
import com.catalystradar.application.company.CompanyService
import com.catalystradar.application.pipeline.PipelineResult
import com.catalystradar.application.pipeline.PipelineService
import com.catalystradar.application.replay.ReplayResult
import com.catalystradar.application.replay.ReplayService
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.company.Company
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.security.ApiKeyService
import com.catalystradar.security.CreatedApiKey
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID

@WebMvcTest(
    InternalPipelineController::class,
    InternalCompanyController::class,
    InternalApiClientController::class,
    InternalReplayController::class,
    properties = ["catalyst.internal.admin-key=test-admin"],
)
class InternalControllersTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var pipeline: PipelineService

    @MockitoBean
    private lateinit var replay: ReplayService

    @MockitoBean
    private lateinit var extraction: EventExtractionProvider

    @MockitoBean
    private lateinit var catalyst: CatalystService

    @MockitoBean
    private lateinit var views: CatalystViewService

    @MockitoBean
    private lateinit var companies: CompanyService

    @MockitoBean
    private lateinit var keys: ApiKeyService

    private val t0 = Instant.parse("2026-09-16T10:00:00Z")

    @Test
    fun `triggers pipeline with admin key`() = runTest {
        whenever(pipeline.runCycle(any())).thenReturn(PipelineResult("SUCCESS", 2, 2, 1))

        mockMvc.post("/internal/ingestion/runs") {
            accept = MediaType.APPLICATION_JSON
            header("X-Admin-Key", "test-admin")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("SUCCESS") }
            jsonPath("$.documentsProcessed") { value(2) }
        }
    }

    @Test
    fun `reports inserts and reuses separately from the original counters`() = runTest {
        whenever(pipeline.runCycle(any())).thenReturn(
            PipelineResult(
                status = "SUCCESS",
                documentsProcessed = 5,
                eventsExtracted = 7,
                companiesRescored = 1,
                documentsSkipped = 1,
                documentsCompleted = 4,
                eventsInserted = 5,
                eventsReused = 2,
            ),
        )

        mockMvc.post("/internal/ingestion/runs") {
            accept = MediaType.APPLICATION_JSON
            header("X-Admin-Key", "test-admin")
        }.andExpect {
            status { isOk() }
            jsonPath("$.documentsConsidered") { value(5) }
            jsonPath("$.documentsProcessed") { value(5) }
            jsonPath("$.documentsCompleted") { value(4) }
            jsonPath("$.documentsSkipped") { value(1) }
            jsonPath("$.eventsExtracted") { value(7) }
            jsonPath("$.eventsInserted") { value(5) }
            jsonPath("$.eventsReused") { value(2) }
        }
    }

    @Test
    fun `pipeline trigger without admin key is forbidden`() = runTest {
        mockMvc.post("/internal/ingestion/runs") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `replays a company at a cutoff with the admin key`() = runTest {
        whenever(replay.replay(any(), eq(extraction))).thenReturn(
            ReplayResult(
                ticker = "DELL",
                asOf = t0,
                score = 46.6,
                state = CatalystState.BUILDING,
                velocity1d = 1.2,
                velocity3d = 3.4,
                velocity7d = 5.6,
                eventCount = 2,
                scoreVersion = "score-v1",
                taxonomyVersion = "taxonomy-v1",
                extractorVersion = "event-extractor-v1",
                documentsConsidered = 3,
                documentsSkipped = 1,
                candidatesAccepted = 2,
            ),
        )

        mockMvc.post("/internal/replays") {
            accept = MediaType.APPLICATION_JSON
            contentType = MediaType.APPLICATION_JSON
            header("X-Admin-Key", "test-admin")
            content = """{"ticker":"DELL","cutoff":"2026-09-16T10:00:00Z"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.ticker") { value("DELL") }
            jsonPath("$.eventCount") { value(2) }
            jsonPath("$.documentsConsidered") { value(3) }
            jsonPath("$.documentsSkipped") { value(1) }
        }
    }

    @Test
    fun `rejects a replay cutoff in the future`() = runTest {
        mockMvc.post("/internal/replays") {
            accept = MediaType.APPLICATION_JSON
            contentType = MediaType.APPLICATION_JSON
            header("X-Admin-Key", "test-admin")
            content = """{"ticker":"DELL","cutoff":"2999-01-01T00:00:00Z"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `recalculates company on demand`() {
        val company = Company(ticker = "DELL", name = "Dell")
        `when`(companies.findByTicker("DELL")).thenReturn(company)
        `when`(views.view("DELL")).thenReturn(
            CatalystView(
                ticker = "DELL",
                score = 46.6,
                state = CatalystState.BUILDING,
                velocity1d = 0.0,
                velocity3d = 0.0,
                velocity7d = 0.0,
                positiveScore = 46.6,
                negativeScore = 0.0,
                directScore = 46.6,
                inferredScore = 0.0,
                totalEvents = 3,
                events7d = 3,
                topDrivers = emptyList(),
                scoreVersion = "score-v1",
                taxonomyVersion = "taxonomy-v1",
                asOf = t0,
            ),
        )

        mockMvc.post("/internal/companies/DELL/recalculate") {
            accept = MediaType.APPLICATION_JSON
            header("X-Admin-Key", "test-admin")
        }.andExpect {
            status { isOk() }
            jsonPath("$.ticker") { value("DELL") }
            jsonPath("$.state") { value("BUILDING") }
        }

        org.mockito.kotlin.verify(catalyst).recalculate(eq(company.id), any())
    }

    @Test
    fun `recalculate unknown company returns 404`() {
        `when`(companies.findByTicker(any())).thenReturn(null)

        mockMvc.post("/internal/companies/NOPE/recalculate") {
            accept = MediaType.APPLICATION_JSON
            header("X-Admin-Key", "test-admin")
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `mints api keys for operators`() {
        `when`(keys.create("backtester")).thenReturn(
            CreatedApiKey(UUID.randomUUID(), "backtester", "a1b2c3d4", "cr_live_a1b2c3d4_secret"),
        )

        mockMvc.post("/internal/api-clients") {
            accept = MediaType.APPLICATION_JSON
            contentType = MediaType.APPLICATION_JSON
            header("X-Admin-Key", "test-admin")
            content = """{"name": "backtester"}"""
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.prefix") { value("a1b2c3d4") }
            jsonPath("$.rawKey") { value("cr_live_a1b2c3d4_secret") }
        }
    }
}
