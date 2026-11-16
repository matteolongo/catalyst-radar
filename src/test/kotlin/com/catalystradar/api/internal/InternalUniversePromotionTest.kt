package com.catalystradar.api.internal

import com.catalystradar.application.universe.NotUsEquityException
import com.catalystradar.application.universe.PromotionResult
import com.catalystradar.application.universe.ReferenceLookupFailedException
import com.catalystradar.application.universe.UniverseCapReachedException
import com.catalystradar.application.universe.UniversePromotionService
import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.universe.UnresolvedTickerStore
import com.catalystradar.ports.ProviderException
import com.catalystradar.security.ApiKeyService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant

@WebMvcTest(
    InternalUniverseController::class,
    properties = ["catalyst.internal.admin-key=test-admin"],
)
class InternalUniversePromotionTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var apiKeys: ApiKeyService

    @MockitoBean
    private lateinit var unresolved: UnresolvedTickerStore

    @MockitoBean
    private lateinit var promotions: UniversePromotionService

    private val company = Company(ticker = "SMCI", name = "Super Micro Computer")

    @Test
    fun `answers 201 for a new company and 200 for an existing one`() {
        runBlocking { whenever(promotions.promote(any(), any())).thenReturn(PromotionResult(company, true, 4, 1, Instant.parse("2026-11-16T12:00:00Z"))) }
        post("SMCI").andExpect {
            status { isCreated() }
            jsonPath("$.ticker") { value("SMCI") }
            jsonPath("$.documentsQueued") { value(1) }
            header { string("Cache-Control", "no-store") }
        }
        runBlocking { whenever(promotions.promote(any(), any())).thenReturn(PromotionResult(company, false, 0, 0, null)) }
        post("SMCI").andExpect { status { isOk() } }
    }

    @Test
    fun `maps refusals to stable error codes`() {
        val cases = listOf(
            NotUsEquityException("SPY") to (422 to "NOT_A_US_EQUITY"),
            UniverseCapReachedException(10) to (409 to "UNIVERSE_CAP_REACHED"),
            ReferenceLookupFailedException("SMCI", ProviderException.TemporaryUnavailable("secret payload")) to (502 to "PROVIDER_UNAVAILABLE"),
        )
        for ((error, expected) in cases) {
            runBlocking { whenever(promotions.promote(any(), any())).thenThrow(error) }
            post("X").andExpect {
                status { isEqualTo(expected.first) }
                jsonPath("$.code") { value(expected.second) }
                content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret payload"))) }
            }
        }
    }

    @Test
    fun `rejects a blank ticker`() {
        post(" ").andExpect { status { isBadRequest() } }
    }

    private fun post(ticker: String) = mockMvc.post("/internal/universe/promotions") {
        contentType = MediaType.APPLICATION_JSON
        accept = MediaType.APPLICATION_JSON
        header("X-Admin-Key", "test-admin")
        content = """{"ticker":"$ticker"}"""
    }
}
