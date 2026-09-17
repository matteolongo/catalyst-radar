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

class OpenAiEmbeddingProviderTest {

    private val runs: ModelRunStore = mock()

    private val provider = OpenAiEmbeddingProvider(
        RestClient.builder(),
        OpenAiProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
        runs,
    )

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
            },
        )
    }

    @Test
    fun `429 becomes rate limited`() = runTest {
        wireMock.stubFor(
            post(urlPathEqualTo("/v1/embeddings")).willReturn(aResponse().withStatus(429)),
        )

        assertThrows<com.catalystradar.ports.ProviderException.RateLimited> {
            provider.embed("hello")
        }
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
