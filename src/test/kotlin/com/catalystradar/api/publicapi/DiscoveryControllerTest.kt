package com.catalystradar.api.publicapi

import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.persistence.discovery.DiscoveryPage
import com.catalystradar.persistence.discovery.DiscoveryQuery
import com.catalystradar.persistence.discovery.DiscoveryRow
import com.catalystradar.persistence.discovery.DiscoveryStore
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID

@WebMvcTest(DiscoveryController::class)
class DiscoveryControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var discovery: DiscoveryStore

    private val t0 = Instant.parse("2026-09-16T10:00:00Z")

    @Test
    fun `returns ranked discovery page`() {
        `when`(discovery.discover(any())).thenReturn(
            DiscoveryPage(
                results = listOf(
                    DiscoveryRow(
                        companyId = UUID.randomUUID(),
                        ticker = "NVDA",
                        name = "Nvidia",
                        sector = "Technology",
                        score = 71.4,
                        scoreVersion = "score-v1",
                        state = CatalystState.CATALYZED,
                        velocity7d = 18.7,
                        events7d = 6,
                        taxonomyVersion = "taxonomy-v1",
                        asOf = t0,
                    ),
                ),
                total = 1,
            ),
        )

        mockMvc.get("/v1/discovery/catalyzed?state=CATALYZED&minScore=55&limit=50") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(1) }
            jsonPath("$.results.length()") { value(1) }
            jsonPath("$.results[0].ticker") { value("NVDA") }
            jsonPath("$.results[0].state") { value("CATALYZED") }
            jsonPath("$.results[0].velocity7d") { value(18.7) }
            jsonPath("$.asOf") { exists() }
        }
    }

    @Test
    fun `rejects out-of-range paging`() {
        mockMvc.get("/v1/discovery/catalyzed?limit=500") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `rejects unknown state`() {
        mockMvc.get("/v1/discovery/catalyzed?state=HYPE") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isBadRequest() }
        }
    }
}
