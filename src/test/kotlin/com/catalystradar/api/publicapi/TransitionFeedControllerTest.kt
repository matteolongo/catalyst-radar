package com.catalystradar.api.publicapi

import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.catalyst.TransitionDirection
import com.catalystradar.persistence.discovery.TransitionFeedPage
import com.catalystradar.persistence.discovery.TransitionFeedQuery
import com.catalystradar.persistence.discovery.TransitionFeedRow
import com.catalystradar.persistence.discovery.TransitionFeedStore
import com.catalystradar.security.ApiKeyService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

@WebMvcTest(TransitionFeedController::class)
class TransitionFeedControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var apiKeys: ApiKeyService // auth is bypassed in slices; covered by ApiKeyAuthFilterTest

    @MockitoBean
    private lateinit var transitions: TransitionFeedStore

    @Test
    fun `returns transitions with derived direction and defaults to the last day`() {
        `when`(transitions.find(any())).thenReturn(
            TransitionFeedPage(
                transitions = listOf(
                    TransitionFeedRow(
                        id = UUID.randomUUID(),
                        ticker = "NVDA",
                        name = "Nvidia",
                        sector = "Technology",
                        from = CatalystState.CATALYZED,
                        to = CatalystState.HIGH,
                        score = 81.0,
                        scoreVersion = "score-v1",
                        transitionedAt = Instant.parse("2026-11-16T10:00:00Z"),
                    ),
                ),
                nextCursor = "next",
            ),
        )

        mockMvc.get("/v1/discovery/transitions?direction=UPGRADE&state=HIGH") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.transitions[0].ticker") { value("NVDA") }
            jsonPath("$.transitions[0].fromState") { value("CATALYZED") }
            jsonPath("$.transitions[0].toState") { value("HIGH") }
            jsonPath("$.transitions[0].direction") { value("UPGRADE") }
            jsonPath("$.nextCursor") { value("next") }
            jsonPath("$.since") { exists() }
            jsonPath("$.asOf") { exists() }
        }
        val query = argumentCaptor<TransitionFeedQuery>().also { verify(transitions).find(it.capture()) }.firstValue
        assertEquals(TransitionDirection.UPGRADE, query.direction)
        assertEquals(setOf(CatalystState.HIGH), query.toStates)
        assertEquals(Duration.ofHours(24), Duration.between(query.since, query.asOf))
    }

    @Test
    fun `rejects a window older than thirty days or in the future`() {
        val tooOld = Instant.now().minus(Duration.ofDays(31))
        val future = Instant.now().plus(Duration.ofDays(1))
        for (since in listOf(tooOld, future)) {
            mockMvc.get("/v1/discovery/transitions?since=$since") {
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isBadRequest() }
            }
        }
    }

    @Test
    fun `rejects out-of-range limits and unknown directions`() {
        for (query in listOf("limit=0", "limit=101", "direction=SIDEWAYS")) {
            mockMvc.get("/v1/discovery/transitions?$query") {
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isBadRequest() }
            }
        }
    }
}
