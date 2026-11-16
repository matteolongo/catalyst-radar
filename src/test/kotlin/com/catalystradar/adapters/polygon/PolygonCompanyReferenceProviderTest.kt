package com.catalystradar.adapters.polygon

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.web.client.RestClient
import com.catalystradar.ports.ProviderException
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PolygonCompanyReferenceProviderTest {

    private val provider = PolygonCompanyReferenceProvider(
        RestClient.builder(),
        PolygonProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
    )

    @Test
    fun `maps tickers page to company references`() = runTest {
        wireMock.stubFor(
            get(urlPathEqualTo("/v3/reference/tickers"))
                .withQueryParam("market", equalTo("stocks"))
                .withQueryParam("apiKey", equalTo("test-key"))
                .willReturn(okJson(TICKERS_PAGE)),
        )

        val page = provider.listUsEquities()

        assertEquals(2, page.values.size)
        assertEquals("DELL", page.values[0].ticker)
        assertEquals("NYSE", page.values[0].exchange)
        assertEquals("Prepackaged Software", page.values[0].industry)
        assertEquals("US", page.values[0].country)
        assertEquals("Nasdaq", page.values[1].exchange)
        assertNull(page.values[1].industry)
        assertNull(page.nextCursor)
    }

    @Test
    fun `finds one active US common stock by exact ticker`() = runTest {
        wireMock.stubFor(
            get(urlPathEqualTo("/v3/reference/tickers"))
                .withQueryParam("ticker", equalTo("SMCI"))
                .withQueryParam("type", equalTo("CS"))
                .withQueryParam("active", equalTo("true"))
                .withQueryParam("limit", equalTo("1"))
                .willReturn(okJson(single("SMCI", type = "CS", locale = "us"))),
        )

        val reference = provider.findUsEquity("SMCI")

        assertEquals("SMCI", reference?.ticker)
        assertEquals("Super Micro Computer", reference?.name)
        assertEquals("Nasdaq", reference?.exchange)
    }

    @Test
    fun `rejects other types, other locales, other symbols and empty results`() = runTest {
        for ((ticker, body) in listOf(
            "SPY" to single("SPY", type = "ETF", locale = "us"),
            "BABA" to single("BABA", type = "CS", locale = "global"),
            "ABC" to single("ABCD", type = "CS", locale = "us"),
            "NONE" to "{\"results\": []}",
        )) {
            wireMock.stubFor(
                get(urlPathEqualTo("/v3/reference/tickers")).withQueryParam("ticker", equalTo(ticker)).willReturn(okJson(body)),
            )
            assertNull(provider.findUsEquity(ticker), ticker)
        }
    }

    @Test
    fun `maps lookup failures to provider errors`() = runTest {
        wireMock.stubFor(
            get(urlPathEqualTo("/v3/reference/tickers")).withQueryParam("ticker", equalTo("SMCI"))
                .willReturn(aResponse().withStatus(401)),
        )

        assertThrows<ProviderException.AuthenticationFailed> { provider.findUsEquity("SMCI") }
    }

    private fun single(ticker: String, type: String, locale: String) = """{
  "results": [{"ticker": "$ticker", "name": "Super Micro Computer", "market": "stocks", "locale": "$locale",
    "primary_exchange": "XNAS", "type": "$type", "active": true}]
}"""

    companion object {
        @RegisterExtension
        @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        const val TICKERS_PAGE = """{
  "results": [
    {
      "ticker": "DELL",
      "name": "Dell Inc.",
      "market": "stocks",
      "locale": "us",
      "primary_exchange": "XNYS",
      "type": "CS",
      "active": true,
      "currency_name": "usd",
      "cik": "0001571996",
      "sic_code": "3571",
      "sic_description": "Prepackaged Software",
      "composite_figi": "BBG000000000",
      "share_class_figi": "BBG000000000",
      "last_updated_utc": "2026-09-01T00:00:00Z"
    },
    {
      "ticker": "AAPL",
      "name": "Apple Inc.",
      "market": "stocks",
      "locale": "us",
      "primary_exchange": "XNAS",
      "type": "CS",
      "active": true,
      "currency_name": "usd",
      "cik": "0000320193",
      "last_updated_utc": "2026-09-01T00:00:00Z"
    }
  ],
  "status": "OK",
  "request_id": "req-2",
  "count": 2
}"""
    }
}
