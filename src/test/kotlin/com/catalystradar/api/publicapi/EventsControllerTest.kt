package com.catalystradar.api.publicapi

import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.event.EventSearch
import com.catalystradar.persistence.event.EventSearchPage
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.persistence.event.EventWithSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID

@WebMvcTest(EventsController::class)
class EventsControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var events: EventStore

    @Test
    fun `searches with filters and cursor`() {
        val event = event()
        `when`(
            events.searchEvents(
                argThat { ticker == "DELL" && type == EventType.GUIDANCE_RAISE && limit == 10 },
            ),
        ).thenReturn(EventSearchPage(listOf(EventWithSource(event, null)), "next"))

        mockMvc.get("/v1/events?ticker=DELL&type=GUIDANCE_RAISE&limit=10") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.events.length()") { value(1) }
            jsonPath("$.events[0].type") { value("GUIDANCE_RAISE") }
            jsonPath("$.nextCursor") { value("next") }
        }
    }

    @Test
    fun `empty result is still 200`() {
        `when`(events.searchEvents(any())).thenReturn(EventSearchPage(emptyList(), null))

        mockMvc.get("/v1/events?ticker=NOPE") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.events.length()") { value(0) }
        }
    }

    @Test
    fun `bad cursor returns 400`() {
        `when`(events.searchEvents(argThat { cursor == "junk" }))
            .thenThrow(IllegalArgumentException("invalid cursor: junk"))

        mockMvc.get("/v1/events?cursor=junk") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isBadRequest() }
        }
    }

    private fun event(): CatalystEvent {
        val at = Instant.parse("2026-09-16T10:00:00Z")
        return CatalystEvent(
            companyId = UUID.randomUUID(),
            type = EventType.GUIDANCE_RAISE,
            direction = Direction.POSITIVE,
            confidence = 0.9,
            sourceQuality = SourceQuality.TIER1_NEWS,
            expectedHorizon = EventHorizon.WEEKS,
            directness = Directness.DIRECT,
            eventTimestamp = at,
            discoveredAt = at,
            taxonomyVersion = "taxonomy-v1",
            extractorVersion = "event-extractor-v1",
        )
    }
}
