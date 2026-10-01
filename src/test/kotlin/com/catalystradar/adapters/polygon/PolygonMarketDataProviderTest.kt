package com.catalystradar.adapters.polygon

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.catalystradar.ports.ProviderException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.web.client.RestClient
import java.time.LocalDate
import kotlin.test.assertEquals

class PolygonMarketDataProviderTest {

    private val provider = PolygonMarketDataProvider(
        RestClient.builder(),
        PolygonProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
    )

    @Test
    fun `maps aggregates to daily bars`() = runTest {
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/aggs/ticker/DELL/range/1/day/2026-09-01/2026-09-16"))
                .withQueryParam("apiKey", equalTo("test-key"))
                .willReturn(okJson(AGGS)),
        )

        val bars = provider.dailyBars(DELL, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-16"))

        assertEquals(2, bars.size)
        assertEquals(LocalDate.parse("2026-09-01"), bars[0].date)
        assertEquals(100.0, bars[0].open)
        assertEquals(105.0, bars[0].high)
        assertEquals(99.0, bars[0].low)
        assertEquals(104.0, bars[0].close)
        assertEquals(1_000_000L, bars[0].volume)
    }

    @Test
    fun `empty aggregates yield no bars`() = runTest {
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/aggs/ticker/DELL/range/1/day/2026-09-01/2026-09-16"))
                .willReturn(okJson("""{"ticker":"DELL","queryCount":0,"resultsCount":0,"status":"OK","request_id":"r","count":0}""")),
        )

        assertEquals(
            emptyList(),
            provider.dailyBars(DELL, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-16")),
        )
    }

    @Test
    fun `401 becomes authentication failed`() = runTest {
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/aggs/ticker/DELL/range/1/day/2026-09-01/2026-09-16"))
                .willReturn(aResponse().withStatus(401)),
        )

        assertThrows<ProviderException.AuthenticationFailed> {
            provider.dailyBars(DELL, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-16"))
        }
    }

    companion object {
        const val DELL = "DELL"

        @RegisterExtension
        @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        const val AGGS = """{
  "ticker": "DELL",
  "queryCount": 2,
  "resultsCount": 2,
  "adjusted": true,
  "results": [
    {"v": 1000000.0, "vw": 101.5, "o": 100.0, "c": 104.0, "h": 105.0, "l": 99.0, "t": 1788220800000, "n": 5000},
    {"v": 1200000.0, "vw": 106.0, "o": 104.0, "c": 107.0, "h": 108.0, "l": 103.0, "t": 1788307200000, "n": 6000}
  ],
  "status": "OK",
  "request_id": "req-3",
  "count": 2
}"""
    }
}
