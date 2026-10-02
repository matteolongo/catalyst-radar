package com.catalystradar.api.publicapi

import com.catalystradar.application.catalyst.CatalystNotFoundException
import com.catalystradar.application.catalyst.CatalystView
import com.catalystradar.application.catalyst.CatalystViewService
import com.catalystradar.application.catalyst.EventDriver
import com.catalystradar.application.catalyst.ExplanationStatus
import com.catalystradar.application.scoring.ScoreCalculator
import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.company.CompanyService
import com.catalystradar.domain.catalyst.CatalystSnapshot
import com.catalystradar.domain.catalyst.CatalystScore
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.catalyst.ScoreVelocity
import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventEvidence
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.event.SafeSourceMetadata
import com.catalystradar.persistence.catalyst.CatalystSnapshotStore
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import com.catalystradar.security.ApiKeyService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID

@WebMvcTest(CatalystController::class)
class CatalystControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var apiKeys: ApiKeyService // auth is bypassed in slices; covered by ApiKeyAuthFilterTest

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
            jsonPath("$.stateBand.minScore") { value(45.0) }
            jsonPath("$.stateBand.maxScore") { value(65.0) }
            jsonPath("$.scoreVersion") { value("score-v1") }
        }
    }

    @Test
    fun `returns aggregate and top driver explanation with safe source`() {
        val event = CatalystEvent(
            companyId = company.id,
            type = EventType.GUIDANCE_RAISE,
            direction = Direction.POSITIVE,
            confidence = 1.0,
            sourceQuality = SourceQuality.TIER1_NEWS,
            expectedHorizon = EventHorizon.WEEKS,
            directness = Directness.DIRECT,
            eventTimestamp = t0,
            discoveredAt = t0,
            taxonomyVersion = "taxonomy-v1",
            extractorVersion = "event-extractor-v1",
            evidence = listOf(EventEvidence("Dell raised guidance.")),
        )
        val calculated = ScoreCalculator().calculate(listOf(event), t0)
        `when`(views.view("DELL")).thenReturn(CatalystView(
            ticker = "DELL", score = calculated.score.value, state = CatalystState.NORMAL,
            velocity1d = 0.0, velocity3d = 0.0, velocity7d = 0.0,
            positiveScore = calculated.score.value, negativeScore = 0.0,
            directScore = calculated.score.value, inferredScore = 0.0,
            totalEvents = 1, events7d = 1,
            topDrivers = listOf(EventDriver(
                event.id, event.type.name, event.direction.name, calculated.contributions.single().value,
                family = event.family.name, eventTimestamp = t0, discoveredAt = t0,
                factors = calculated.contributions.single(), evidence = event.evidence,
                source = SafeSourceMetadata(UUID.randomUUID(), "Dell outlook", "polygon", t0, "https://example.com"),
            )),
            scoreVersion = "score-v1", taxonomyVersion = "taxonomy-v1", asOf = t0,
            scoreCalculation = calculated,
            explanationStatus = ExplanationStatus.MATCHED,
        ))

        mockMvc.get("/v1/companies/DELL/catalyst").andExpect {
            status { isOk() }
            jsonPath("$.scoreCalculation.contributionSum") { value(9.0) }
            jsonPath("$.explanationStatus") { value("MATCHED") }
            jsonPath("$.scoreCalculation.familyCount") { value(1) }
            jsonPath("$.scoreCalculation.convergenceMultiplier") { value(1.0) }
            jsonPath("$.scoreCalculation.rawScore") { value(9.0) }
            jsonPath("$.scoreCalculation.normalizationScale") { value(30.0) }
            jsonPath("$.scoreCalculation.contributionCutoff") { value(0.01) }
            jsonPath("$.topDrivers[0].family") { value("GUIDANCE") }
            jsonPath("$.topDrivers[0].factors.baseWeight") { value(10.0) }
            jsonPath("$.topDrivers[0].factors.value") { value(9.0) }
            jsonPath("$.topDrivers[0].evidence[0].quoteOrFact") { value("Dell raised guidance.") }
            jsonPath("$.topDrivers[0].source.title") { value("Dell outlook") }
            jsonPath("$.topDrivers[0].source.provider") { value("polygon") }
            jsonPath("$.topDrivers[0].source.canonicalUrl") { value("https://example.com") }
            jsonPath("$.topDrivers[0].source.body") { doesNotExist() }
            jsonPath("$.topDrivers[0].source.rawPayload") { doesNotExist() }
        }
    }

    @Test
    fun `unmatched explanations report status without drivers or calculation`() {
        listOf(ExplanationStatus.SCORE_MISMATCH, ExplanationStatus.VERSION_MISMATCH).forEach { explanationStatus ->
            `when`(views.view("DELL")).thenReturn(CatalystView(
                ticker = "DELL", score = 46.6, state = CatalystState.BUILDING,
                velocity1d = 0.0, velocity3d = 0.0, velocity7d = 0.0,
                positiveScore = 0.0, negativeScore = 0.0,
                directScore = 0.0, inferredScore = 0.0,
                totalEvents = 1, events7d = 1, topDrivers = emptyList(),
                scoreVersion = "score-v1", taxonomyVersion = "taxonomy-v1", asOf = t0,
                scoreCalculation = null, explanationStatus = explanationStatus,
            ))

            mockMvc.get("/v1/companies/DELL/catalyst").andExpect {
                status { isOk() }
                jsonPath("$.explanationStatus") { value(explanationStatus.name) }
                jsonPath("$.topDrivers.length()") { value(0) }
                jsonPath("$.scoreCalculation") { doesNotExist() }
            }
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

