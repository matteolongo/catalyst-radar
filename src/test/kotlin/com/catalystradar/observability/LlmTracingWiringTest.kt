package com.catalystradar.observability

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.ports.ExtractionRequest
import io.opentelemetry.api.OpenTelemetry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The application context must provide the OpenTelemetry SDK that
 * [LlmTracing] reads; without it every LLM trace is silently a no-op.
 * Uses the shared default context (export off) rather than a dedicated
 * one, so the test never reaches LangSmith and adds no connection pool.
 */
class LlmTracingWiringTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var openTelemetry: ObjectProvider<OpenTelemetry>

    @Autowired
    private lateinit var environment: Environment

    @Test
    fun `context provides an OpenTelemetry SDK so LLM traces are real spans`() {
        assertNotNull(openTelemetry.ifAvailable, "no OpenTelemetry bean: LLM tracing would be a no-op")
        val tracing = LlmTracing(openTelemetry, LangSmithProperties(enabled = true))
        val trace = tracing.beginExtraction(
            "gpt-4o-mini",
            ExtractionRequest(
                SourceDocument(provider = "polygon", title = "t", body = "b", discoveredAt = Instant.parse("2026-09-16T10:00:00Z")),
                listOf(Company(ticker = "DELL", name = "Dell")),
            ),
        )
        try {
            assertTrue(trace is OtelLlmCallTrace, "expected a real span, got ${trace::class.simpleName}")
        } finally {
            trace.succeed(null, null, null)
        }
    }

    @Test
    fun `LLM spans are roots even inside another active span`() {
        val otel = requireNotNull(openTelemetry.ifAvailable)
        val outer = otel.getTracer("outer").spanBuilder("http").startSpan()
        val exporter = io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create()
        val provider = io.opentelemetry.sdk.trace.SdkTracerProvider.builder()
            .addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(exporter)).build()
        val sdk = io.opentelemetry.sdk.OpenTelemetrySdk.builder().setTracerProvider(provider).build()
        val sdkProvider = object : ObjectProvider<OpenTelemetry> {
            override fun getObject(vararg args: Any?): OpenTelemetry = sdk
            override fun getIfAvailable(): OpenTelemetry = sdk
            override fun getIfUnique(): OpenTelemetry = sdk
            override fun getObject(): OpenTelemetry = sdk
        }
        try {
            outer.makeCurrent().use {
                LlmTracing(sdkProvider, LangSmithProperties(enabled = true)).beginExtraction(
                    "gpt-4o-mini",
                    ExtractionRequest(
                        SourceDocument(provider = "polygon", title = "t", body = "b", discoveredAt = Instant.parse("2026-09-16T10:00:00Z")),
                        listOf(Company(ticker = "DELL", name = "Dell")),
                    ),
                ).succeed(null, null, null)
            }
            assertTrue(exporter.finishedSpanItems.single().parentSpanContext.isValid.not())
        } finally {
            outer.end()
            provider.close()
        }
    }

    @Test
    fun `every LLM span is sampled by default`() {
        assertEquals(1.0, environment.getProperty("management.tracing.sampling.probability", Double::class.java))
        val tracer = requireNotNull(openTelemetry.ifAvailable).getTracer("sampling-check")
        repeat(50) {
            val span = tracer.spanBuilder("probe").startSpan()
            try {
                assertTrue(span.spanContext.isSampled)
            } finally {
                span.end()
            }
        }
    }
}
