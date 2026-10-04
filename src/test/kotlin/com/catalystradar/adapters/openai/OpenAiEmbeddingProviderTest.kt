package com.catalystradar.adapters.openai

import com.catalystradar.persistence.extraction.ModelRunInput
import com.catalystradar.persistence.extraction.ModelRunStore
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.mockito.kotlin.check
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.math.BigDecimal
import java.util.UUID
import com.catalystradar.ports.EmbeddingRequest

class OpenAiEmbeddingProviderTest {

    private val runs: ModelRunStore = mock()

    private val meterRegistry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()

    private val provider = OpenAiEmbeddingProvider(
        RestClient.builder(),
        OpenAiProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
        runs,
        com.catalystradar.observability.CatalystMetrics(meterRegistry),
    )

    @Test
    fun `missing embedding usage remains unknown rather than free`() = runTest {
        wireMock.stubFor(post(urlPathEqualTo("/v1/embeddings")).willReturn(okJson(
            EMBEDDING_RESPONSE.replace("\"usage\": {\"prompt_tokens\": 8, \"total_tokens\": 8}", "\"usage\": null"),
        )))
        provider.embed("hello")
        verify(runs).record(check<ModelRunInput> {
            assertNull(it.inputTokens)
            assertNull(it.outputTokens)
            assertNull(it.estimatedCost)
        })
    }

    @Test
    fun `known zero embedding usage records zero cost with unknown output tokens`() = runTest {
        wireMock.stubFor(post(urlPathEqualTo("/v1/embeddings")).willReturn(okJson(
            EMBEDDING_RESPONSE.replace("\"prompt_tokens\": 8", "\"prompt_tokens\": 0"),
        )))
        provider.embed("hello")
        verify(runs).record(check<ModelRunInput> {
            assertEquals(0, it.inputTokens)
            assertNull(it.outputTokens)
            assertEquals(BigDecimal("0.000000"), it.estimatedCost)
        })
    }

    @Test
    fun `unknown embedding pricing preserves known usage`() = runTest {
        val unknownProvider = OpenAiEmbeddingProvider(
            RestClient.builder(),
            OpenAiProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key", embeddingModel = "unknown"),
            runs,
            com.catalystradar.observability.CatalystMetrics(meterRegistry),
        )
        wireMock.stubFor(post(urlPathEqualTo("/v1/embeddings")).willReturn(okJson(EMBEDDING_RESPONSE)))
        unknownProvider.embed("hello")
        verify(runs).record(check<ModelRunInput> {
            assertEquals(8, it.inputTokens)
            assertNull(it.outputTokens)
            assertNull(it.estimatedCost)
        })
    }

    @Test
    fun `invalid embedding data retains known usage and cost in one failed run`() = runTest {
        wireMock.stubFor(post(urlPathEqualTo("/v1/embeddings")).willReturn(okJson(
            """{"data": [], "usage": {"prompt_tokens": 100}}""",
        )))
        assertThrows<com.catalystradar.ports.ProviderException.InvalidResponse> { provider.embed("hello") }
        verify(runs).record(check<ModelRunInput> {
            assertEquals(false, it.success)
            assertEquals(100, it.inputTokens)
            assertNull(it.outputTokens)
            assertEquals(BigDecimal("0.000002"), it.estimatedCost)
            assertEquals("INVALID_RESPONSE", it.errorCode)
            assertEquals("Provider output did not pass extraction/response validation.", it.error)
        })
    }

    @Test
    fun `embeds text and records the run`() = runTest {
        wireMock.stubFor(
            post(urlPathEqualTo("/v1/embeddings")).willReturn(okJson(EMBEDDING_RESPONSE)),
        )

        val embedding = provider.embed("Dell GUIDANCE_RAISE")

        assertEquals(listOf(0.1f, 0.2f, 0.3f), embedding.values)
        assertEquals("text-embedding-3-small", embedding.model)
        verify(runs).record(
            check<ModelRunInput> {
                assertEquals("embed", it.operation)
                assertEquals(8, it.inputTokens)
                assertEquals(true, it.success)
                assertNull(it.sourceDocumentId)
                assertNull(it.processingAttemptId)
            },
        )
    }

    @Test
    fun `contextual embedding success records the explicit source and attempt`() = runTest {
        wireMock.stubFor(post(urlPathEqualTo("/v1/embeddings")).willReturn(okJson(EMBEDDING_RESPONSE)))
        val request = EmbeddingRequest("hello", UUID.randomUUID(), UUID.randomUUID())
        provider.embed(request)
        verify(runs).record(check<ModelRunInput> {
            assertEquals(request.sourceDocumentId, it.sourceDocumentId)
            assertEquals(request.processingAttemptId, it.processingAttemptId)
            assertEquals(true, it.success)
        })
    }

    @Test
    fun `contextual embedding failure retains the explicit source and attempt`() = runTest {
        wireMock.stubFor(post(urlPathEqualTo("/v1/embeddings")).willReturn(aResponse().withStatus(429)))
        val request = EmbeddingRequest("hello", UUID.randomUUID(), UUID.randomUUID())
        assertThrows<com.catalystradar.ports.ProviderException.RateLimited> { provider.embed(request) }
        verify(runs).record(check<ModelRunInput> {
            assertEquals(request.sourceDocumentId, it.sourceDocumentId)
            assertEquals(request.processingAttemptId, it.processingAttemptId)
            assertEquals("RATE_LIMITED", it.errorCode)
        })
    }

    @Test
    fun `429 becomes rate limited`() = runTest {
        wireMock.stubFor(
            post(urlPathEqualTo("/v1/embeddings")).willReturn(aResponse().withStatus(429)),
        )

        assertThrows<com.catalystradar.ports.ProviderException.RateLimited> {
            provider.embed("hello")
        }
        verify(runs).record(check<ModelRunInput> {
            assertEquals("RATE_LIMITED", it.errorCode)
            assertEquals("Provider rate limit; wait for the scheduled retry.", it.error)
            assertNull(it.estimatedCost)
        })
    }

    companion object {
        @RegisterExtension
        @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        const val EMBEDDING_RESPONSE = """{
  "object": "list",
  "data": [{"object": "embedding", "embedding": [0.1, 0.2, 0.3], "index": 0}],
  "model": "text-embedding-3-small",
  "usage": {"prompt_tokens": 8, "total_tokens": 8}
}"""
    }
}
