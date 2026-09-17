package com.catalystradar.adapters.polygon

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
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Vendor failure translation matrix, shared by every Polygon adapter:
 * auth, rate limits, outages, and malformed payloads.
 */
class PolygonErrorMappingTest {

    private val news = PolygonNewsProvider(
        RestClient.builder(),
        PolygonProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
    )
    private val reference = PolygonCompanyReferenceProvider(
        RestClient.builder(),
        PolygonProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
    )

    @Test
    fun `401 becomes authentication failed`() = runTest {
        wireMock.stubFor(get(urlPathEqualTo("/v2/reference/news")).willReturn(aResponse().withStatus(401)))

        val error = assertThrows<ProviderException.AuthenticationFailed> {
            news.fetch(NewsFetchRequest())
        }

        assertNull(error.retryableAfterSeconds())
    }

    @Test
    fun `429 becomes rate limited with retry guidance`() = runTest {
        wireMock.stubFor(
            get(urlPathEqualTo("/v3/reference/tickers"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "45")),
        )

        val error = assertThrows<ProviderException.RateLimited> {
            reference.listUsEquities()
        }

        assertEquals(45L, error.retryAfterSeconds)
    }

    @Test
    fun `500 becomes temporary unavailable`() = runTest {
        wireMock.stubFor(get(urlPathEqualTo("/v2/reference/news")).willReturn(aResponse().withStatus(503)))

        assertThrows<ProviderException.TemporaryUnavailable> {
            news.fetch(NewsFetchRequest())
        }
    }

    @Test
    fun `malformed payload becomes invalid response`() = runTest {
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news"))
                .willReturn(aResponse().withStatus(200).withBody("not json at all")),
        )

        assertThrows<ProviderException.InvalidResponse> {
            news.fetch(NewsFetchRequest())
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
