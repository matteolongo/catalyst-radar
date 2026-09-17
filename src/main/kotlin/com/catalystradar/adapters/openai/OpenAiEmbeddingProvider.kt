package com.catalystradar.adapters.openai

import com.catalystradar.adapters.http.mapHttpClientError
import com.catalystradar.persistence.extraction.ModelRunInput
import com.catalystradar.persistence.extraction.ModelRunStore
import com.catalystradar.ports.Embedding
import com.catalystradar.ports.EmbeddingProvider
import com.catalystradar.ports.ProviderException
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import tools.jackson.core.JacksonException

/**
 * OpenAI embeddings adapter. Every call records tokens, latency, and
 * cost like extraction runs; embeddings carry no document link because
 * one vector can serve clustering across many events.
 */
@Component
class OpenAiEmbeddingProvider(
    builder: RestClient.Builder,
    private val properties: OpenAiProperties,
    private val modelRuns: ModelRunStore,
) : EmbeddingProvider {

    override val model: String get() = properties.embeddingModel

    private val log = LoggerFactory.getLogger(OpenAiEmbeddingProvider::class.java)

    private val client = builder.baseUrl(properties.baseUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            },
        )
        .build()

    override suspend fun embed(text: String): Embedding =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            try {
                val response = post(text)
                val values = response.data?.firstOrNull()?.embedding
                    ?: throw ProviderException.InvalidResponse("openai: no embedding data")
                record(response, elapsedMs(started), success = true, error = null)
                Embedding(values = values, model = properties.embeddingModel)
            } catch (e: HttpClientErrorException) {
                val mapped = mapHttpClientError("openai", e)
                recordFailure(started, mapped.message)
                throw mapped
            } catch (e: HttpServerErrorException) {
                val failure = ProviderException.TemporaryUnavailable("openai: ${e.statusCode}")
                recordFailure(started, failure.message)
                throw failure
            } catch (e: ProviderException) {
                recordFailure(started, e.message)
                throw e
            } catch (e: JacksonException) {
                val failure = ProviderException.InvalidResponse("openai: unusable JSON (${e.message})")
                recordFailure(started, failure.message)
                throw failure
            } catch (e: RestClientException) {
                val failure = ProviderException.InvalidResponse("openai: ${e.message}")
                recordFailure(started, failure.message)
                throw failure
            }
        }

    private fun post(text: String): OpenAiEmbeddingResponse {
        val body = mapOf(
            "model" to properties.embeddingModel,
            "input" to text.take(EMBEDDING_CHAR_LIMIT),
        )
        return client.post().uri("/v1/embeddings")
            .header("Authorization", "Bearer ${properties.apiKey}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body<OpenAiEmbeddingResponse>()
            ?: throw ProviderException.InvalidResponse("openai: empty response")
    }

    private fun record(
        response: OpenAiEmbeddingResponse,
        latencyMs: Long,
        success: Boolean,
        error: String?,
    ) {
        runCatching {
            modelRuns.record(
                ModelRunInput(
                    provider = "openai",
                    operation = "embed",
                    model = properties.embeddingModel,
                    inputTokens = response.usage?.promptTokens,
                    outputTokens = null,
                    latencyMs = latencyMs,
                    estimatedCost = OpenAiPricing.estimateUsd(
                        properties.embeddingModel,
                        response.usage?.promptTokens ?: 0,
                        0,
                    ),
                    success = success,
                    error = error,
                ),
            )
        }.onFailure {
            log.warn("openai embedding run recording failed: {}", it.message)
        }
    }

    private fun recordFailure(started: Long, error: String?) {
        record(
            OpenAiEmbeddingResponse(data = null, usage = null),
            elapsedMs(started),
            success = false,
            error = error,
        )
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000
        const val EMBEDDING_CHAR_LIMIT = 8_000
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class OpenAiEmbeddingResponse(
    @JsonProperty("data") val data: List<OpenAiEmbeddingData>?,
    @JsonProperty("usage") val usage: OpenAiEmbeddingUsage?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class OpenAiEmbeddingData(
    @JsonProperty("embedding") val embedding: List<Float>?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class OpenAiEmbeddingUsage(
    @JsonProperty("prompt_tokens") val promptTokens: Int?,
)
