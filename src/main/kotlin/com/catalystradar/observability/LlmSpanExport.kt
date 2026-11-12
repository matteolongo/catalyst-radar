package com.catalystradar.observability

import io.micrometer.tracing.exporter.SpanExportingPredicate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * LangSmith receives LLM spans only. With the OpenTelemetry SDK present,
 * every Micrometer observation (HTTP server requests, provider HTTP calls)
 * also becomes a span; those would be noise in LangSmith and the provider
 * client spans carry request URLs. Only spans that [LlmTracing] marks with
 * a LangSmith span kind are exported.
 */
@Configuration(proxyBeanMethods = false)
class LlmSpanExport {

    @Bean
    fun llmSpansOnly(): SpanExportingPredicate =
        SpanExportingPredicate { span -> span.tags.containsKey(LlmTracing.LANGSMITH_SPAN_KIND) }
}
