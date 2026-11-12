package com.catalystradar.adapters.http

import com.catalystradar.adapters.finnhub.FinnhubNewsProvider
import com.catalystradar.adapters.finnhub.FinnhubProperties
import com.catalystradar.adapters.polygon.PolygonMarketDataProvider
import com.catalystradar.adapters.polygon.PolygonNewsProvider
import com.catalystradar.adapters.polygon.PolygonProperties
import com.catalystradar.ports.NewsFetchRequest
import com.catalystradar.ports.ProviderException
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderHttpBehaviourTest {

    private val meters = SimpleMeterRegistry()
    private val observations = ObservationRegistry.create().also {
        it.observationConfig().observationHandler(DefaultMeterObservationHandler(meters))
    }

    private fun builder(): RestClient.Builder = RestClientConfiguration().restClientBuilder(
        object : ObjectProvider<ObservationRegistry> {
            override fun getObject(vararg args: Any?) = observations
            override fun getIfAvailable() = observations
            override fun getIfUnique() = observations
            override fun getObject() = observations
        },
    )

    private val shortRead = Duration.ofMillis(200)

    private fun polygon(readTimeout: Duration = Duration.ofSeconds(5)) = PolygonNewsProvider(
        builder(), PolygonProperties(baseUrl = wireMock.baseUrl(), apiKey = "secret-key", readTimeout = readTimeout),
    )

    @Test
    fun `polygon timeout is a temporary outage, not invalid output`() = runTest {
        wireMock.stubFor(get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(EMPTY_PAGE).withFixedDelay(1500)))

        val failure = assertThrows<ProviderException.TemporaryUnavailable> {
            polygon(shortRead).fetch(NewsFetchRequest(tickers = listOf("DELL")))
        }
        assertEquals("polygon", failure.provider)
        assertFalse(failure.message.orEmpty().contains("secret-key"))
    }

    @Test
    fun `finnhub timeout is a temporary outage`() = runTest {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/company-news")).willReturn(okJson("[]").withFixedDelay(1500)))
        val provider = FinnhubNewsProvider(
            builder(), FinnhubProperties(baseUrl = wireMock.baseUrl(), apiKey = "secret-key", readTimeout = shortRead),
        )

        val failure = assertThrows<ProviderException.TemporaryUnavailable> {
            provider.fetch(NewsFetchRequest(tickers = listOf("DELL")))
        }
        assertEquals("finnhub", failure.provider)
    }

    @Test
    fun `malformed body is still invalid output`() = runTest {
        wireMock.stubFor(get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson("{not json")))

        assertThrows<ProviderException.InvalidResponse> { polygon().fetch(NewsFetchRequest(tickers = listOf("DELL"))) }
    }

    @Test
    fun `polygon next page carries the api key`() = runTest {
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news"))
                .withQueryParam("cursor", equalTo("abc"))
                .withQueryParam("apiKey", equalTo("secret-key"))
                .willReturn(okJson(EMPTY_PAGE)),
        )

        polygon().fetch(NewsFetchRequest(cursor = "${wireMock.baseUrl()}/v2/reference/news?cursor=abc"))

        wireMock.verify(getRequestedFor(urlPathEqualTo("/v2/reference/news")).withQueryParam("apiKey", equalTo("secret-key")))
    }

    @Test
    fun `polygon next page on another host is refused before any request`() = runTest {
        assertThrows<ProviderException.InvalidResponse> {
            polygon().fetch(NewsFetchRequest(cursor = "https://attacker.example/v2/reference/news?cursor=abc"))
        }
        assertEquals(0, wireMock.allServeEvents.size)
    }

    @Test
    fun `provider requests are measured without secrets or per-ticker tags`() = runTest {
        wireMock.stubFor(get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(EMPTY_PAGE)))
        wireMock.stubFor(get(urlPathMatching("/v2/aggs/ticker/.*")).willReturn(okJson("{\"results\": []}")))
        val properties = PolygonProperties(baseUrl = wireMock.baseUrl(), apiKey = "secret-key")

        polygon().fetch(NewsFetchRequest(tickers = listOf("DELL")))
        PolygonMarketDataProvider(builder(), properties).dailyBars("DELL", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-10"))
        PolygonMarketDataProvider(builder(), properties).dailyBars("AAPL", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-10"))

        val timers = meters.find("http.client.requests").timers()
        val uris = timers.map { it.id.getTag("uri") }.toSet()
        assertEquals(setOf("/v2/reference/news", "/v2/aggs/ticker/{ticker}/range/1/day/{from}/{to}"), uris)
        assertTrue(timers.flatMap { it.id.tags }.none { it.value.contains("secret-key") || it.value.contains("DELL") })
    }

    @Test
    fun `failed provider requests are measured as errors`() = runTest {
        wireMock.stubFor(get(urlPathEqualTo("/v2/reference/news")).willReturn(aResponse().withStatus(503)))

        assertThrows<ProviderException.TemporaryUnavailable> { polygon().fetch(NewsFetchRequest(tickers = listOf("DELL"))) }

        val timer = meters.find("http.client.requests").tag("status", "503").timer()
        assertEquals(1L, timer?.count())
        assertEquals("SERVER_ERROR", timer?.id?.getTag("outcome"))
    }

    companion object {
        @RegisterExtension
        @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        const val EMPTY_PAGE = "{\"results\": [], \"status\": \"OK\"}"
    }
}
