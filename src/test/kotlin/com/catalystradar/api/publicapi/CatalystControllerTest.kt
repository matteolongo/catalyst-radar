package com.catalystradar.api.publicapi

import com.catalystradar.application.catalyst.CatalystNotFoundException
import com.catalystradar.application.catalyst.CatalystView
import com.catalystradar.application.catalyst.CatalystViewService
import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.company.CompanyService
import com.catalystradar.domain.catalyst.CatalystSnapshot
import com.catalystradar.domain.catalyst.CatalystScore
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.catalyst.ScoreVelocity
import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.catalyst.CatalystSnapshotStore
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID

@WebMvcTest(CatalystController::class)
class CatalystControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var views: CatalystViewService

    @MockitoBean
    private lateinit var companies: CompanyService

    @MockitoBean
    private lateinit var snapshots: CatalystSnapshotStore

    private val t0 = Instant.parse("2026-09-16T10:00:00Z")
    private val company = Company(ticker = "DELL", name = "Dell")

    @Test
    fun `returns catalyst view`() {
        `when`(views.view("DELL")).thenReturn(
            CatalystView(
                ticker = "DELL",
                score = 46.6,
                state = CatalystState.BUILDING,
                velocity1d = 4.0,
                velocity3d = 11.0,
                velocity7d = 32.0,
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

        mockMvc.get("/v1/companies/DELL/catalyst") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.ticker") { value("DELL") }
            jsonPath("$.score") { value(46.6) }
            jsonPath("$.state") { value("BUILDING") }
            jsonPath("$.scoreVersion") { value("score-v1") }
        }
    }

    @Test
    fun `missing catalyst returns 404`() {
        `when`(views.view("DELL")).thenThrow(CatalystNotFoundException("DELL"))

        mockMvc.get("/v1/companies/DELL/catalyst") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("CATALYST_NOT_FOUND") }
        }
    }

    @Test
    fun `returns timeline with snapshots and transitions`() {
        `when`(companies.findByTicker("DELL")).thenReturn(company)
        `when`(snapshots.history(company.id)).thenReturn(
            listOf(
                CatalystSnapshot(
                    companyId = company.id,
                    score = CatalystScore(46.6, "score-v1"),
                    state = CatalystState.BUILDING,
                    velocity = ScoreVelocity(4.0, 11.0, 32.0),
                    asOf = t0,
                    taxonomyVersion = "taxonomy-v1",
                ),
            ),
        )
        `when`(snapshots.findTransitions(eq(company.id))).thenReturn(emptyList())

        mockMvc.get("/v1/companies/DELL/timeline") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.ticker") { value("DELL") }
            jsonPath("$.snapshots.length()") { value(1) }
            jsonPath("$.snapshots[0].state") { value("BUILDING") }
            jsonPath("$.transitions.length()") { value(0) }
        }
    }

    @Test
    fun `timeline for unknown company returns 404`() {
        `when`(companies.findByTicker(any())).thenReturn(null)

        mockMvc.get("/v1/companies/NOPE/timeline") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("COMPANY_NOT_FOUND") }
        }
    }

    @Test
    fun `timeline rejects out-of-range limit`() {
        `when`(companies.findByTicker("DELL")).thenReturn(company)

        mockMvc.get("/v1/companies/DELL/timeline?limit=500") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isBadRequest() }
        }
    }
}
