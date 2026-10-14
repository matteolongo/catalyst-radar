package com.catalystradar.observability

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.ports.EmbeddingRequest
import com.catalystradar.ports.ExtractionRequest
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmTracingTest {

    private val exporter = InMemorySpanExporter.create()
    private val tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build()
    private val openTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .build()

    @AfterEach
    fun tearDown() {
        exporter.reset()
        tracerProvider.close()
    }

    private fun tracing(
        enabled: Boolean = true,
        captureContent: Boolean = false,
        otel: OpenTelemetry? = openTelemetry,
    ): LlmTracing {
        val provider: ObjectProvider<OpenTelemetry> = mock()
        whenever(provider.ifAvailable).thenReturn(otel)
        return LlmTracing(provider, LangSmithProperties(enabled = enabled, captureContent = captureContent))
    }

    private fun extractionRequest() = ExtractionRequest(
        document = SourceDocument(
            provider = "polygon",
            title = "Dell raises forecast",
            body = "Dell raised its full-year outlook.",
            discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
        ),
        companies = listOf(Company(ticker = "DELL", name = "Dell")),
    )

    private fun string(data: SpanData, key: String) = data.attributes.get(AttributeKey.stringKey(key))
    private fun long(data: SpanData, key: String) = data.attributes.get(AttributeKey.longKey(key))

    @Test
    fun `extraction span carries langsmith run kind model and token usage`() {
        val trace = tracing().beginExtraction("gpt-4o-mini", extractionRequest())
        trace.prompt("secret article body")
        trace.succeed("gpt-4o-mini", inputTokens = 100, outputTokens = 50)

        val span = exporter.finishedSpanItems.single()
        assertEquals("llm", string(span, "langsmith.span.kind"))
        assertEquals("openai", string(span, "gen_ai.system"))
        assertEquals("chat", string(span, "gen_ai.operation.name"))
        assertEquals("gpt-4o-mini", string(span, "gen_ai.request.model"))
        assertEquals(100L, long(span, "gen_ai.usage.input_tokens"))
        assertEquals(50L, long(span, "gen_ai.usage.output_tokens"))
        assertEquals(150L, long(span, "gen_ai.usage.total_tokens"))
        assertEquals(StatusCode.OK, span.status.statusCode)
    }

    @Test
    fun `extraction span links source document and attempt as metadata`() {
        val request = extractionRequest()
        val attemptId = UUID.randomUUID()
        val trace = tracing().beginExtraction(
            "gpt-4o-mini",
            request.copy(processingAttemptId = attemptId),
        )
        trace.succeed("gpt-4o-mini", null, null)

        val span = exporter.finishedSpanItems.single()
        assertEquals(request.document.id.toString(), string(span, "langsmith.metadata.source_document_id"))
        assertEquals(attemptId.toString(), string(span, "langsmith.metadata.processing_attempt_id"))
        assertEquals("event-extractor-v1", string(span, "langsmith.metadata.prompt_version"))
    }

    @Test
    fun `prompt and completion are omitted unless content capture is enabled`() {
        val off = tracing(captureContent = false)
        off.beginExtraction("gpt-4o-mini", extractionRequest()).apply {
            prompt("secret article body")
            completion("raw model output")
            succeed("gpt-4o-mini", 1, 1)
        }

        val span = exporter.finishedSpanItems.single()
        assertNull(string(span, "gen_ai.prompt"))
        assertNull(string(span, "gen_ai.completion"))
    }

    @Test
    fun `prompt and completion are recorded when content capture is enabled`() {
        val on = tracing(captureContent = true)
        on.beginExtraction("gpt-4o-mini", extractionRequest()).apply {
            prompt("secret article body")
            completion("raw model output")
            succeed("gpt-4o-mini", 1, 1)
        }

        val span = exporter.finishedSpanItems.single()
        assertEquals("secret article body", string(span, "gen_ai.prompt"))
        assertEquals("raw model output", string(span, "gen_ai.completion"))
    }

    @Test
    fun `failure records the exception and marks the span errored`() {
        val trace = tracing().beginExtraction("gpt-4o-mini", extractionRequest())
        trace.fail(IllegalStateException("boom"))

        val span = exporter.finishedSpanItems.single()
        assertEquals(StatusCode.ERROR, span.status.statusCode)
        assertTrue(span.events.any { it.name == "exception" })
    }

    @Test
    fun `span is ended at most once even if success then failure`() {
        val trace = tracing().beginExtraction("gpt-4o-mini", extractionRequest())
        trace.succeed("gpt-4o-mini", 1, 1)
        trace.fail(IllegalStateException("late failure"))

        assertEquals(1, exporter.finishedSpanItems.size)
        assertEquals(StatusCode.OK, exporter.finishedSpanItems.single().status.statusCode)
    }

    @Test
    fun `disabled tracing emits no span`() {
        val trace = tracing(enabled = false).beginExtraction("gpt-4o-mini", extractionRequest())
        trace.succeed("gpt-4o-mini", 1, 1)
        assertTrue(exporter.finishedSpanItems.isEmpty())
    }

    @Test
    fun `missing opentelemetry emits no span`() {
        val trace = tracing(otel = null).beginExtraction("gpt-4o-mini", extractionRequest())
        trace.succeed("gpt-4o-mini", 1, 1)
        assertTrue(exporter.finishedSpanItems.isEmpty())
    }

    @Test
    fun `embedding span uses embedding run kind and operation`() {
        val trace = tracing().beginEmbedding("text-embedding-3-small", EmbeddingRequest("hello"))
        trace.succeed(null, inputTokens = 8, outputTokens = null)

        val span = exporter.finishedSpanItems.single()
        assertEquals("embedding", string(span, "langsmith.span.kind"))
        assertEquals("embeddings", string(span, "gen_ai.operation.name"))
        assertEquals("text-embedding-3-small", string(span, "gen_ai.request.model"))
        assertEquals(8L, long(span, "gen_ai.usage.input_tokens"))
        assertNull(long(span, "gen_ai.usage.output_tokens"))
        assertEquals(StatusCode.OK, span.status.statusCode)
    }
}
