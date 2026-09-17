package com.catalystradar.api.publicapi

import com.catalystradar.application.company.CompanyService
import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.persistence.event.EventWithSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@WebMvcTest(CompanyEventsController::class)
class CompanyEventsControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var companies: CompanyService

    @MockitoBean
    private lateinit var events: EventStore

    private val company = Company(ticker = "DELL", name = "Dell")

    @Test
    fun `returns bounded event page with cursor`() {
        `when`(companies.findByTicker("DELL")).thenReturn(company)
        `when`(events.findDetailedByCompanyId(company.id)).thenReturn(
            listOf(
                detailed("2026-09-16T10:00:00Z"),
                detailed("2026-09-15T10:00:00Z"),
            ),
        )

        var cursor: String? = null
        val firstPage = mockMvc.get("/v1/companies/DELL/events?limit=1") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.events.length()") { value(1) }
            jsonPath("$.events[0].type") { value("GUIDANCE_RAISE") }
            jsonPath("$.events[0].sourceDocumentId") { exists() }
            jsonPath("$.nextCursor") { exists() }
        }.andReturn()
        cursor = ObjectMapper().readTree(firstPage.response.contentAsString).path("nextCursor").asText()

        mockMvc.get("/v1/companies/DELL/events?limit=1&cursor=$cursor") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.events.length()") { value(1) }
            jsonPath("$.nextCursor") { doesNotExist() }
        }
    }

    @Test
    fun `unknown company returns 404`() {
        `when`(companies.findByTicker(any())).thenReturn(null)

        mockMvc.get("/v1/companies/NOPE/events") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("COMPANY_NOT_FOUND") }
        }
    }

    @Test
    fun `out-of-range limit returns 400`() {
        `when`(companies.findByTicker("DELL")).thenReturn(company)

        mockMvc.get("/v1/companies/DELL/events?limit=500") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isBadRequest() }
        }
    }

    private fun detailed(at: String): EventWithSource {
        val instant = Instant.parse(at)
        return EventWithSource(
            event = CatalystEvent(
                companyId = company.id,
                type = EventType.GUIDANCE_RAISE,
                direction = Direction.POSITIVE,
                confidence = 0.9,
                sourceQuality = SourceQuality.TIER1_NEWS,
                expectedHorizon = EventHorizon.WEEKS,
                directness = Directness.DIRECT,
                eventTimestamp = instant,
                discoveredAt = instant,
                taxonomyVersion = "taxonomy-v1",
                extractorVersion = "event-extractor-v1",
            ),
            sourceDocumentId = UUID.randomUUID(),
        )
    }
}
