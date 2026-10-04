package com.catalystradar.adapters.openai

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.extraction.ModelRunStore
import com.catalystradar.ports.ExtractionRequest
import com.catalystradar.ports.ProviderException
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.web.client.RestClient
import java.time.Instant
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiEventExtractionProviderTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var modelRuns: ModelRunStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    private val meterRegistry = io.micrometer.core.instrument.simple.SimpleMeterRegistry()

    private fun provider() = OpenAiEventExtractionProvider(
        RestClient.builder(),
        OpenAiProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
        modelRuns,
        DefaultResourceLoader(),
        com.catalystradar.observability.CatalystMetrics(meterRegistry),
    )

    private fun request() = ExtractionRequest(
        document = documents.save(
            SourceDocument(
                provider = "polygon",
                title = "Dell raises forecast",
                body = "Dell raised its full-year outlook.",
                discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
            ),
        ),
        companies = listOf(Company(ticker = "DELL", name = "Dell")),
    )

    @Test
    fun `missing extraction usage remains unknown rather than free`() = runTest {
        val req = request()
        val responseWithoutUsage = CHAT_RESPONSE.replace(
            "\"usage\": {\"prompt_tokens\": 100, \"completion_tokens\": 50, \"total_tokens\": 150}",
            "\"usage\": null",
        )
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(okJson(responseWithoutUsage)))
        provider().extract(req)
        val recorded = modelRuns.findByDocument(req.document.id).single()
        assertNull(recorded.inputTokens)
        assertNull(recorded.outputTokens)
        assertNull(recorded.estimatedCost)
    }

    @Test
    fun `known zero extraction usage records zero cost`() = runTest {
        val req = request()
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions")).willReturn(okJson(
            CHAT_RESPONSE.replace("\"prompt_tokens\": 100", "\"prompt_tokens\": 0")
                .replace("\"completion_tokens\": 50", "\"completion_tokens\": 0"),
        )))
        provider().extract(req)
        val recorded = modelRuns.findByDocument(req.document.id).single()
        assertEquals(0, recorded.inputTokens)
        assertEquals(0, recorded.outputTokens)
        assertEquals(BigDecimal("0.000000"), recorded.estimatedCost)
    }

    @Test
    fun `unknown extraction pricing preserves known tokens`() = runTest {
        val req = request()
        wireMock.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
            .willReturn(okJson(CHAT_RESPONSE.replace("gpt-4o-mini", "gpt-99"))))
        provider().extract(req)
        val recorded = modelRuns.findByDocument(req.document.id).single()
        assertEquals("gpt-99", recorded.model)
        assertEquals(100, recorded.inputTokens)
        assertEquals(50, recorded.outputTokens)
        assertNull(recorded.estimatedCost)
    }

    @Test
    fun `extracts structured candidates and records the run`() = runTest {
        wireMock.stubFor(
            post(urlPathEqualTo("/v1/chat/completions")).willReturn(okJson(CHAT_RESPONSE)),
        )
        val req = request()

        val result = provider().extract(req)

        assertTrue(result.documentRelevant)
        assertEquals(1, result.events.size)
        assertEquals(EventType.GUIDANCE_RAISE, result.events[0].type)
        assertEquals("DELL", result.events[0].ticker)

        val runs = modelRuns.findByDocument(req.document.id)
        assertEquals(1, runs.size)
        assertTrue(runs[0].success)
        assertEquals("gpt-4o-mini", runs[0].model)
        assertEquals(100, runs[0].inputTokens)
        assertEquals(50, runs[0].outputTokens)
        assertEquals(
            1.0,
            meterRegistry.counter(
                "catalyst_llm_calls_total",
                "operation", "extract",
                "status", "success",
            ).count(),
        )
    }

    @Test
    fun `sends bearer auth and strict schema request`() = runTest {
        wireMock.stubFor(
            post(urlPathEqualTo("/v1/chat/completions")).willReturn(okJson(CHAT_RESPONSE)),
        )

        provider().extract(request())

        wireMock.verify(
            postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .withRequestBody(containing("\"strict\"")),
        )
    }

    @Test
    fun `401 records a failed run and raises authentication failed`() = runTest {
        wireMock.stubFor(
            post(urlPathEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(401)),
        )
        val req = request()

        assertThrows<ProviderException.AuthenticationFailed> {
            provider().extract(req)
        }

        val runs = modelRuns.findByDocument(req.document.id)
        assertEquals(1, runs.size)
        assertEquals(false, runs[0].success)
        assertEquals("AUTHENTICATION_FAILED", runs[0].errorCode)
        assertEquals("Provider authentication failed; check server credentials.", runs[0].error)
        assertNull(runs[0].estimatedCost)
        assertNull(runs[0].processingAttemptId)
    }

    @Test
    fun `malformed model content raises invalid response`() = runTest {
        wireMock.stubFor(
            post(urlPathEqualTo("/v1/chat/completions")).willReturn(okJson(MALFORMED_RESPONSE)),
        )

        val req = request()
        assertThrows<ProviderException.InvalidResponse> {
            provider().extract(req)
        }
        val recorded = modelRuns.findByDocument(req.document.id).single()
        assertEquals(false, recorded.success)
        assertEquals("gpt-4o-mini", recorded.model)
        assertEquals(10, recorded.inputTokens)
        assertEquals(5, recorded.outputTokens)
        assertEquals(BigDecimal("0.000005"), recorded.estimatedCost)
        assertEquals("INVALID_RESPONSE", recorded.errorCode)
        assertEquals("Provider output did not pass extraction/response validation.", recorded.error)
    }

    companion object {
        @RegisterExtension
        @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        const val CONTENT = "{\"document_relevant\": true, \"events\": [{\"ticker\": \"DELL\", \"family\": \"GUIDANCE\", \"type\": \"GUIDANCE_RAISE\", \"direction\": \"POSITIVE\", \"confidence\": 0.9, \"magnitude\": null, \"surprise\": 0.6, \"materiality\": 0.8, \"expected_horizon\": \"WEEKS\", \"directness\": \"DIRECT\", \"event_timestamp\": null, \"evidence\": [{\"quote_or_fact\": \"raised its full-year outlook\", \"source_offset_hint\": null}], \"attributes\": [{\"key\": \"period\", \"value\": \"FY2026\"}]}]}"

        val CHAT_RESPONSE = """{
  "id": "chatcmpl-1",
  "object": "chat.completion",
  "created": 1758028200,
  "model": "gpt-4o-mini",
  "choices": [
    {
      "index": 0,
      "message": {"role": "assistant", "content": "${CONTENT.replace("\"", "\\\"")}"},
      "finish_reason": "stop"
    }
  ],
  "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}
}"""

        const val MALFORMED_RESPONSE = """{
  "id": "chatcmpl-2",
  "object": "chat.completion",
  "created": 1758028200,
  "model": "gpt-4o-mini",
  "choices": [{"index": 0, "message": {"role": "assistant", "content": "definitely not json"}, "finish_reason": "stop"}],
  "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
}"""
    }
}
