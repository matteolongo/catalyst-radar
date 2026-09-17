package com.catalystradar.adapters.finnhub

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

class FinnhubCompanyReferenceProviderTest {

    private val provider = FinnhubCompanyReferenceProvider(
        RestClient.builder(),
        FinnhubProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
    )

    @Test
    fun `maps symbol list to common-stock references`() = runTest {
        wireMock.stubFor(
            get(urlPathEqualTo("/api/v1/stock/symbol"))
                .withQueryParam("exchange", equalTo("US"))
                .withQueryParam("token", equalTo("test-key"))
                .willReturn(okJson(SYMBOLS)),
        )

        val page = provider.listUsEquities()

        assertEquals(2, page.values.size)
        assertEquals("DELL", page.values[0].ticker)
        assertEquals("NYSE", page.values[0].exchange)
        assertNull(page.nextCursor)
    }

    @Test
    fun `cursor continues the symbol list`() = runTest {
        wireMock.stubFor(
            get(urlPathEqualTo("/api/v1/stock/symbol"))
                .willReturn(okJson(SYMBOLS)),
        )

        val page = provider.listUsEquities(cursor = "2")

        assertEquals(0, page.values.size)
        assertNull(page.nextCursor)
    }

    companion object {
        @RegisterExtension
        @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        const val SYMBOLS = """[
  {
    "currency": "USD",
    "description": "Dell Inc.",
    "displaySymbol": "DELL",
    "exchange": "NYSE",
    "figi": "BBG000000000",
    "isin": null,
    "mic": "XNYS",
    "shareClassFIGI": "BBG000000000",
    "symbol": "DELL",
    "symbol2": "",
    "type": "Common Stock"
  },
  {
    "currency": "USD",
    "description": "Apple Inc.",
    "displaySymbol": "AAPL",
    "exchange": "NASDAQ NMS - GLOBAL MARKET",
    "figi": "BBG000000001",
    "isin": null,
    "mic": "XNGS",
    "shareClassFIGI": "BBG000000001",
    "symbol": "AAPL",
    "symbol2": "",
    "type": "Common Stock"
  },
  {
    "currency": "USD",
    "description": "Some ETF",
    "displaySymbol": "XYZ",
    "mic": "ARCX",
    "symbol": "XYZ",
    "type": "ETF"
  }
]"""
    }
}
