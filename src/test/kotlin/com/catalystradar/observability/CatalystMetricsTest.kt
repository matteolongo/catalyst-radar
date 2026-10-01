package com.catalystradar.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import org.junit.jupiter.api.Test
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.assertEquals

class CatalystMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = CatalystMetrics(registry)

    @Test
    fun `counts ingested documents by provider and status`() {
        metrics.documentIngested("polygon", "new")
        metrics.documentIngested("polygon", "new")
        metrics.documentIngested("finnhub", "duplicate")

        assertEquals(2.0, registry.counter("catalyst_ingestion_documents_total", "provider", "polygon", "status", "new").count())
        assertEquals(1.0, registry.counter("catalyst_ingestion_documents_total", "provider", "finnhub", "status", "duplicate").count())
    }

    @Test
    fun `times ingestion runs`() {
        metrics.ingestionRun("polygon", 1500L)

        val timer = registry.timer("catalyst_ingestion_run_duration_seconds", "provider", "polygon")
        assertEquals(1, timer.count())
        assertEquals(1.5, timer.totalTime(java.util.concurrent.TimeUnit.SECONDS), 1e-9)
    }

    @Test
    fun `tracks llm calls tokens and latency`() {
        metrics.llmCall("extract", "gpt-4o-mini", true, 100, 50, 1200L)

        assertEquals(
            1.0,
            registry.counter("catalyst_llm_calls_total", "operation", "extract", "status", "success").count(),
        )
        assertEquals(
            100.0,
            registry.counter("catalyst_llm_tokens_total", "model", "gpt-4o-mini", "direction", "input").count(),
        )
        assertEquals(
            50.0,
            registry.counter("catalyst_llm_tokens_total", "model", "gpt-4o-mini", "direction", "output").count(),
        )
        val latency = registry.timer("catalyst_llm_latency_seconds", "operation", "extract", "model", "gpt-4o-mini")
        assertEquals(1.2, latency.totalTime(java.util.concurrent.TimeUnit.SECONDS), 1e-9)
    }

    @Test
    fun `counts events and transitions`() {
        metrics.eventExtracted("GUIDANCE", "GUIDANCE_RAISE", "POSITIVE")
        metrics.stateTransition("WATCH", "BUILDING")

        assertEquals(
            1.0,
            registry.counter("catalyst_events_total", "family", "GUIDANCE", "type", "GUIDANCE_RAISE", "direction", "POSITIVE").count(),
        )
        assertEquals(
            1.0,
            registry.counter("catalyst_state_transitions_total", "from", "WATCH", "to", "BUILDING").count(),
        )
    }

    @Test
    fun `tags are stable objects`() {
        // Guard against tag-order churn in dashboards: same call twice, one meter.
        metrics.documentIngested("polygon", "new")
        metrics.documentIngested("polygon", "new")

        assertEquals(
            1,
            registry.find("catalyst_ingestion_documents_total").meters().size,
        )
        assertEquals(Tag.of("provider", "polygon"), Tag.of("provider", "polygon"))
    }
}
