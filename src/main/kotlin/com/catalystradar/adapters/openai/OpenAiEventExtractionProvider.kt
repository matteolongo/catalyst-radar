package com.catalystradar.adapters.openai

import com.catalystradar.adapters.http.mapHttpClientError
import com.catalystradar.common.Versions
import com.catalystradar.persistence.extraction.ModelRunInput
import com.catalystradar.persistence.extraction.ModelRunStore
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.ports.EvidenceSpan
import com.catalystradar.ports.ExtractedEvent
import com.catalystradar.ports.ExtractionRequest
import com.catalystradar.ports.ExtractionResult
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventFamily
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.ports.ProviderException
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * OpenAI Structured Outputs extraction adapter. Sends the versioned
 * prompt plus strict schema, parses candidates defensively, and records
 * every invocation (tokens, latency, cost, failures) for replay and
 * cost control. Model output is untrusted input: see [parseContent].
 */
@Component
class OpenAiEventExtractionProvider(
    builder: RestClient.Builder,
    private val properties: OpenAiProperties,
    private val modelRuns: ModelRunStore,
    private val resources: ResourceLoader,
) : EventExtractionProvider {

    private val client = builder.baseUrl(properties.baseUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            },
        )
        .build()

    private val systemPrompt: String by lazy {readResource("classpath:prompts/event-extractor-v1.txt")
    }

    private val schemaJson: String by lazy {
        readResource("classpath:prompts/event-extractor-schema-v1.json")
    }

    private val log = LoggerFactory.getLogger(OpenAiEventExtractionProvider::class.java)

    override suspend fun extract(request: ExtractionRequest): ExtractionResult =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            try {
                val response = post(request)
                val result = parseContent(response)
                record(request, response.model, response.usage, elapsedMs(started), success = true, error = null)
                result
            } catch (e: HttpClientErrorException) {
                val mapped = mapHttpClientError("openai", e)
                recordFailure(request, started, mapped.message)
                throw mapped
            } catch (e: HttpServerErrorException) {
                val failure = ProviderException.TemporaryUnavailable("openai: ${e.statusCode}")
                recordFailure(request, started, failure.message)
                throw failure
            } catch (e: ProviderException) {
                recordFailure(request, started, e.message)
                throw e
            } catch (e: JacksonException) {
                val failure = ProviderException.InvalidResponse("openai: unusable JSON (${e.message})")
                recordFailure(request, started, failure.message)
                throw failure
            } catch (e: RestClientException) {
                val failure = ProviderException.InvalidResponse("openai: ${e.message}")
                recordFailure(request, started, failure.message)
                throw failure
            }
        }

    private fun post(request: ExtractionRequest): OpenAiChatResponse {
        val body = mapOf(
            "model" to properties.extractionModel,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userMessage(request)),
            ),
            "response_format" to mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf(
                    "name" to "catalyst_extraction_v1",
                    "strict" to true,
                    "schema" to mapper.readTree(schemaJson),
                ),
            ),
        )
        return client.post().uri("/v1/chat/completions")
            .header("Authorization", "Bearer ${properties.apiKey}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body<OpenAiChatResponse>()
            ?: throw ProviderException.InvalidResponse("openai: empty response")
    }

    private fun userMessage(request: ExtractionRequest): String {
        val document = request.document
        val tickers = request.companies.map { it.ticker }
        val body = document.body.take(DOCUMENT_CHAR_LIMIT)
        return buildString {
            appendLine("Tickers in scope: ${tickers.joinToString(", ")}")
            appendLine("Title: ${document.title}")
            appendLine("Published: ${document.publishedAt ?: "unknown"}")
            appendLine("Body:")
            appendLine(body)
            if (document.body.length > DOCUMENT_CHAR_LIMIT) appendLine("[truncated]")
        }
    }

    private fun parseContent(response: OpenAiChatResponse): ExtractionResult {
        val message = response.choices?.firstOrNull()?.message
            ?: throw ProviderException.InvalidResponse("openai: no choices")
        if (!message.refusal.isNullOrBlank()) {
            throw ProviderException.InvalidResponse("openai: model refused")
        }
        val content = message.content?.takeIf { it.isNotBlank() }
            ?: throw ProviderException.InvalidResponse("openai: empty content")
        val root = try {
            mapper.readTree(content)
        } catch (e: Exception) {
            throw ProviderException.InvalidResponse("openai: content is not JSON")
        }
        val relevant = root.path("document_relevant").takeIf { it.isBoolean }?.booleanValue()
            ?: throw ProviderException.InvalidResponse("openai: missing document_relevant")
        if (!relevant) return ExtractionResult(documentRelevant = false, events = emptyList())
        val events = root.path("events").takeIf { it.isArray }
            ?.mapNotNull { toCandidate(it) }
            ?: throw ProviderException.InvalidResponse("openai: missing events")
        return ExtractionResult(documentRelevant = true, events = events)
    }

    private fun toCandidate(node: JsonNode): ExtractedEvent? =
        try {
            toCandidateOrThrow(node)
        } catch (e: DropCandidateException) {
            null
        }

    private fun toCandidateOrThrow(node: JsonNode): ExtractedEvent? {
        val ticker = node.path("ticker").asText().takeIf { it.isNotBlank() } ?: return null
        val family = node.path("family").asText().let { runCatching { EventFamily.valueOf(it) }.getOrNull() }
            ?: return null
        val type = node.path("type").asText().let { runCatching { EventType.valueOf(it) }.getOrNull() }
            ?: return null
        if (type.family != family) return null
        val direction = node.path("direction").asText().let { runCatching { Direction.valueOf(it) }.getOrNull() }
            ?: return null
        val confidence = node.path("confidence").takeIf { it.isNumber }?.asDouble()
            ?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return null
        val magnitude = optionalDouble(node, "magnitude", range = null)
        val surprise = optionalDouble(node, "surprise", range = 0.0..1.0)
        val materiality = optionalDouble(node, "materiality", range = 0.0..1.0)
        val horizon = node.path("expected_horizon").asText().let { runCatching { EventHorizon.valueOf(it) }.getOrNull() }
            ?: return null
        val directness = node.path("directness").asText().let { runCatching { Directness.valueOf(it) }.getOrNull() }
            ?: return null
        val timestamp = node.path("event_timestamp").takeIf { it.isTextual }
            ?.asText()?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val evidence = node.path("evidence").takeIf { it.isArray }
            ?.mapNotNull { item ->
                val quote = item.path("quote_or_fact").asText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val hint = item.path("source_offset_hint").takeIf { it.isTextual }?.asText()
                EvidenceSpan(quoteOrFact = quote, sourceOffsetHint = hint)
            }.orEmpty()
        val attributes = node.path("attributes").takeIf { it.isArray }
            ?.mapNotNull { item ->
                val key = item.path("key").asText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                key to item.path("value").asText()
            }?.toMap().orEmpty()
        return ExtractedEvent(
            ticker = ticker,
            type = type,
            direction = direction,
            confidence = confidence,
            magnitude = magnitude,
            surprise = surprise,
            materiality = materiality,
            expectedHorizon = horizon,
            directness = directness,
            eventTimestamp = timestamp,
            evidence = evidence,
            attributes = attributes,
        )
    }

    /**
     * Absent/JSON-null yields null. Present numbers must be finite (and in
     * range when given); anything else drops the candidate via
     * [DropCandidateException] so one bad event never kills the page.
     */
    private fun optionalDouble(node: JsonNode, field: String, range: ClosedRange<Double>?): Double? {
        val child = node.path(field)
        if (child.isMissingNode || child.isNull) return null
        val value = if (child.isNumber) child.asDouble() else Double.NaN
        if (!value.isFinite() || (range != null && value !in range)) throw DropCandidateException()
        return value
    }

    private class DropCandidateException : RuntimeException()

    private fun record(
        request: ExtractionRequest,
        model: String?,
        usage: OpenAiUsage?,
        latencyMs: Long,
        success: Boolean,
        error: String?,
    ) {
        val resolvedModel = model ?: properties.extractionModel
        runCatching {
            modelRuns.record(
                ModelRunInput(
                    provider = "openai",
                    operation = "extract",
                    model = resolvedModel,
                    promptVersion = Versions.PROMPT_V1,
                    extractorVersion = Versions.EXTRACTOR_V1,
                    sourceDocumentId = request.document.id,
                    inputTokens = usage?.promptTokens,
                    outputTokens = usage?.completionTokens,
                    latencyMs = latencyMs,
                    estimatedCost = OpenAiPricing.estimateUsd(
                        resolvedModel,
                        usage?.promptTokens ?: 0,
                        usage?.completionTokens ?: 0,
                    ),
                    success = success,
                    error = error,
                ),
            )
        }.onFailure {
            log.warn("openai model run recording failed for document {}: {}", request.document.id, it.message)
        }
    }

    private fun recordFailure(request: ExtractionRequest, started: Long, error: String?) {
        record(request, null, null, elapsedMs(started), success = false, error = error)
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private fun readResource(location: String): String =
        resources.getResource(location).inputStream.use {
            it.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")
        }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 120_000
        const val DOCUMENT_CHAR_LIMIT = 8_000
        private val mapper = ObjectMapper()
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class OpenAiChatResponse(
    @JsonProperty("id") val id: String?,
    @JsonProperty("model") val model: String?,
    @JsonProperty("choices") val choices: List<OpenAiChoice>?,
    @JsonProperty("usage") val usage: OpenAiUsage?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class OpenAiChoice(
    @JsonProperty("message") val message: OpenAiMessage?,
    @JsonProperty("finish_reason") val finishReason: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class OpenAiMessage(
    @JsonProperty("content") val content: String?,
    @JsonProperty("refusal") val refusal: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class OpenAiUsage(
    @JsonProperty("prompt_tokens") val promptTokens: Int?,
    @JsonProperty("completion_tokens") val completionTokens: Int?,
)
