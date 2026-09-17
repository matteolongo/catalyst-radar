package com.catalystradar.adapters.finnhub

import com.catalystradar.ports.NewsFetchRequest
import com.catalystradar.ports.ProviderException
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.web.client.RestClient
import kotlin.test.assertNull

class FinnhubErrorMappingTest {

    private val news = FinnhubNewsProvider(
        RestClient.builder(),
        FinnhubProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
    )

    @Test
    fun `403 becomes authentication failed`() = runTest {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/company-news")).willReturn(aResponse().withStatus(403)))

        val error = assertThrows<ProviderException.AuthenticationFailed> {
            news.fetch(NewsFetchRequest(tickers = listOf("DELL")))
        }

        assertNull(error.retryableAfterSeconds())
    }

    @Test
    fun `429 becomes rate limited`() = runTest {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/company-news")).willReturn(aResponse().withStatus(429)))

        assertThrows<ProviderException.RateLimited> {
            news.fetch(NewsFetchRequest(tickers = listOf("DELL")))
        }
    }

    @Test
    fun `500 becomes temporary unavailable`() = runTest {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/company-news")).willReturn(aResponse().withStatus(500)))

        assertThrows<ProviderException.TemporaryUnavailable> {
            news.fetch(NewsFetchRequest(tickers = listOf("DELL")))
        }
    }

    companion object {
        @RegisterExtension
        @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()
    }
}
