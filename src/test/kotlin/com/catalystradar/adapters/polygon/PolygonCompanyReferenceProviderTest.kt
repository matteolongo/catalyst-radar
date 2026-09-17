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
