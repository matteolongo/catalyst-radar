package com.catalystradar.adapters.http

import com.catalystradar.adapters.polygon.PolygonNewsProvider
import com.catalystradar.adapters.polygon.PolygonProperties
import com.catalystradar.ports.NewsFetchRequest
import com.catalystradar.ports.ProviderException
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpResponse
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.web.client.RestClient
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProviderRateLimitsTest {

    private class FakeTime : Sleeper {
        var nanos = 0L
        val sleeps = mutableListOf<Duration>()
        override fun sleep(duration: Duration) {
            sleeps += duration
            nanos += duration.toNanos()
        }
    }

    @Test
    fun `limiter lets a minute's budget through and then waits for the oldest request to leave the window`() {
        val time = FakeTime()
        val limiter = ProviderRateLimiter(5, { time.nanos }, time)

        repeat(5) { assertEquals(Duration.ZERO, limiter.acquire()) }
        time.nanos += Duration.ofSeconds(10).toNanos()
        val waited = limiter.acquire()

        assertEquals(Duration.ofSeconds(50), waited)
        assertEquals(listOf(Duration.ofSeconds(50)), time.sleeps)
    }

    @Test
    fun `limiter with no budget never waits`() {
        val time = FakeTime()
        val limiter = ProviderRateLimiter(0, { time.nanos }, time)

        repeat(1000) { limiter.acquire() }

        assertEquals(emptyList(), time.sleeps)
    }

    private fun responses(vararg statuses: Pair<HttpStatus, String?>): Pair<ClientHttpRequestExecution, () -> Int> {
        var calls = 0
        val execution = ClientHttpRequestExecution { _, _ ->
            val (status, retryAfter) = statuses[calls++]
            MockClientHttpResponse(ByteArray(0), status).apply { retryAfter?.let { headers.set("Retry-After", it) } } as ClientHttpResponse
        }
        return execution to { calls }
    }

    private fun interceptor(limit: ProviderRateLimit, time: FakeTime) =
        ProviderRateLimitInterceptor("polygon", limit, ProviderRateLimiter(limit.requestsPerMinute, { time.nanos }, time), time)

    @Test
    fun `429 is retried after Retry-After and the success is returned`() {
        val time = FakeTime()
        val (execution, calls) = responses(HttpStatus.TOO_MANY_REQUESTS to "7", HttpStatus.OK to null)

        val response = interceptor(ProviderRateLimit(maxRetries = 2), time)
            .intercept(MockClientHttpRequest(), ByteArray(0), execution)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(2, calls())
        assertEquals(listOf(Duration.ofSeconds(7)), time.sleeps)
    }

    @Test
    fun `429 without Retry-After waits one full minute`() {
        val time = FakeTime()
        val (execution, _) = responses(HttpStatus.TOO_MANY_REQUESTS to null, HttpStatus.OK to null)

        interceptor(ProviderRateLimit(maxRetries = 1), time).intercept(MockClientHttpRequest(), ByteArray(0), execution)

        assertEquals(listOf(Duration.ofMinutes(1)), time.sleeps)
    }

    @Test
    fun `the last 429 is returned once retries are spent`() {
        val time = FakeTime()
        val (execution, calls) = responses(
            HttpStatus.TOO_MANY_REQUESTS to "1",
            HttpStatus.TOO_MANY_REQUESTS to "1",
            HttpStatus.TOO_MANY_REQUESTS to "1",
        )

        val response = interceptor(ProviderRateLimit(maxRetries = 2), time)
            .intercept(MockClientHttpRequest(), ByteArray(0), execution)

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode)
        assertEquals(3, calls())
    }

    @Test
    fun `a Retry-After longer than the maximum wait is not retried`() {
        val time = FakeTime()
        val (execution, calls) = responses(HttpStatus.TOO_MANY_REQUESTS to "3600")

        val response = interceptor(ProviderRateLimit(maxRetries = 2, maxRetryWait = Duration.ofSeconds(65)), time)
            .intercept(MockClientHttpRequest(), ByteArray(0), execution)

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode)
        assertEquals(1, calls())
        assertEquals(emptyList(), time.sleeps)
    }

    @Test
    fun `a provider without configuration gets no interceptor`() {
        val limits = ProviderRateLimits(RateLimitProperties(mutableMapOf("polygon" to ProviderRateLimit(5))), meters(SimpleMeterRegistry()))

        assertNull(limits.interceptor("finnhub"))
        assertNull(ProviderRateLimits.none().interceptor("polygon"))
    }

    @Test
    fun `invalid configuration fails at startup`() {
        assertFailsWith<IllegalArgumentException> {
            ProviderRateLimits(RateLimitProperties(mutableMapOf("polygon" to ProviderRateLimit(-1))), meters(SimpleMeterRegistry()))
        }
    }

    @Test
    fun `a real client retries a 429 and the adapter sees the next response`() = runTest {
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news")).inScenario("limit").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("open"),
        )
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news")).inScenario("limit").whenScenarioStateIs("open")
                .willReturn(okJson("""{"results": [{"id": "a", "title": "Dell news", "tickers": ["DELL"]}], "status": "OK"}""")),
        )
        val registry = SimpleMeterRegistry()
        val provider = PolygonNewsProvider(
            RestClient.builder(),
            PolygonProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
            ProviderRateLimits(RateLimitProperties(mutableMapOf("polygon" to ProviderRateLimit(requestsPerMinute = 100, maxRetries = 1))), meters(registry)),
        )

        val result = provider.fetch(NewsFetchRequest(tickers = listOf("DELL")))

        assertEquals(1, result.articles.size)
        assertEquals(2, wireMock.findAll(getRequestedFor(urlPathEqualTo("/v2/reference/news"))).size)
        assertEquals(1.0, registry.counter(ProviderRateLimits.RETRIED_METRIC, "provider", "polygon").count())
    }

    @Test
    fun `a 429 that outlasts the retries still reaches the adapter as rate limited`() = runTest {
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news")).willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")),
        )
        val provider = PolygonNewsProvider(
            RestClient.builder(),
            PolygonProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
            ProviderRateLimits(RateLimitProperties(mutableMapOf("polygon" to ProviderRateLimit(maxRetries = 1))), meters(SimpleMeterRegistry())),
        )

        assertFailsWith<ProviderException.RateLimited> { provider.fetch(NewsFetchRequest(tickers = listOf("DELL"))) }
        assertEquals(2, wireMock.findAll(getRequestedFor(urlPathEqualTo("/v2/reference/news"))).size)
    }

    private fun meters(registry: MeterRegistry): ObjectProvider<MeterRegistry> = object : ObjectProvider<MeterRegistry> {
        override fun getObject(): MeterRegistry = registry
        override fun getObject(vararg args: Any?): MeterRegistry = registry
        override fun getIfAvailable(): MeterRegistry = registry
        override fun getIfUnique(): MeterRegistry = registry
    }

    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()
    }
}
