package com.catalystradar.api.internal

import com.catalystradar.persistence.universe.CandidatePage
import com.catalystradar.persistence.universe.CandidateRow
import com.catalystradar.persistence.universe.UnresolvedTickerStore
import com.catalystradar.security.ApiKeyService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Duration
import java.time.Instant

@WebMvcTest(
    InternalUniverseController::class,
    properties = ["catalyst.internal.admin-key=test-admin"],
)
class InternalUniverseControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var apiKeys: ApiKeyService

    @MockitoBean
    private lateinit var unresolved: UnresolvedTickerStore

    @MockitoBean
    private lateinit var promotions: com.catalystradar.application.universe.UniversePromotionService

    @Test
    fun `lists candidates with document counts that are not event counts`() {
        val at = Instant.parse("2026-11-16T10:00:00Z")
        `when`(unresolved.candidates(any())).thenReturn(
            CandidatePage(listOf(CandidateRow("SMCI", documents = 4, neverQueuedDocuments = 1, firstDiscoveredAt = at, lastDiscoveredAt = at)), 1),
        )

        mockMvc.get("/internal/universe/candidates") {
            accept = MediaType.APPLICATION_JSON
            header("X-Admin-Key", "test-admin")
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.candidates[0].ticker") { value("SMCI") }
            jsonPath("$.candidates[0].documentsStored") { value(4) }
            jsonPath("$.candidates[0].documentsNeverQueued") { value(1) }
            jsonPath("$.total") { value(1) }
        }
    }

    @Test
    fun `requires the admin key`() {
        mockMvc.get("/internal/universe/candidates") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `rejects unbounded or invalid reads`() {
        val tooOld = Instant.now().minus(Duration.ofDays(91))
        for (query in listOf("limit=0", "limit=101", "offset=-1", "offset=10001", "minDocuments=0", "since=$tooOld")) {
            mockMvc.get("/internal/universe/candidates?$query") {
                accept = MediaType.APPLICATION_JSON
                header("X-Admin-Key", "test-admin")
            }.andExpect { status { isBadRequest() } }
        }
    }
}
