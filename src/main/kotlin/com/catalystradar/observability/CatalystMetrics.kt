package com.catalystradar.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * CatalystRadar application metrics (Micrometer). Cardinality stays
 * bounded: tags are providers, operations, models, families, types,
 * directions, and states â€” never tickers, companies, or documents.
 * Per-company scores are queryable via the API, never meters.
 */
@Component
class CatalystMetrics(private val registry: MeterRegistry) {

    fun documentIngested(provider: String, status: String) {
        registry.counter(
            "catalyst_ingestion_documents_total",
            listOf(Tag.of("provider", provider), Tag.of("status", status)),
        ).increment()
    }

    fun ingestionRun(provider: String, durationMs: Long) {
        registry.timer(
            "catalyst_ingestion_run_duration_seconds",
            listOf(Tag.of("provider", provider)),
        ).record(durationMs, TimeUnit.MILLISECONDS)
    }

    fun llmCall(
        operation: String,
        model: String,
        success: Boolean,
        inputTokens: Int,
        outputTokens: Int,
        latencyMs: Long,
    ) {
        val outcome = if (success) "success" else "failure"
        registry.counter(
            "catalyst_llm_calls_total",
            listOf(Tag.of("operation", operation), Tag.of("status", outcome)),
        ).increment()
        registry.counter(
            "catalyst_llm_tokens_total",
            listOf(Tag.of("model", model), Tag.of("direction", "input")),
        ).increment(inputTokens.toDouble())
        registry.counter(
            "catalyst_llm_tokens_total",
            listOf(Tag.of("model", model), Tag.of("direction", "output")),
        ).increment(outputTokens.toDouble())
        registry.timer(
            "catalyst_llm_latency_seconds",
            listOf(Tag.of("operation", operation), Tag.of("model", model)),
        ).record(latencyMs, TimeUnit.MILLISECONDS)
    }

    fun eventExtracted(family: String, type: String, direction: String) {
        registry.counter(
            "catalyst_events_total",
            listOf(Tag.of("family", family), Tag.of("type", type), Tag.of("direction", direction)),
        ).increment()
    }

    fun stateTransition(from: String, to: String) {
        registry.counter(
            "catalyst_state_transitions_total",
            listOf(Tag.of("from", from), Tag.of("to", to)),
        ).increment()
    }

    fun pipelineCycle(status: String) {
        registry.counter(
            "catalyst_pipeline_cycles_total",
            listOf(Tag.of("status", status)),
        ).increment()
    }
}
