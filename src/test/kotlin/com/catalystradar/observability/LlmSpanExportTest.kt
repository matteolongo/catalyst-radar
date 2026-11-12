package com.catalystradar.observability

import io.micrometer.tracing.exporter.FinishedSpan
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmSpanExportTest {

    private val predicate = LlmSpanExport().llmSpansOnly()

    private fun span(tags: Map<String, String>): FinishedSpan = mock<FinishedSpan>().also {
        whenever(it.tags).thenReturn(tags)
    }

    @Test
    fun `LLM spans are exported`() {
        assertTrue(predicate.isExportable(span(mapOf(LlmTracing.LANGSMITH_SPAN_KIND to "llm"))))
    }

    @Test
    fun `HTTP client spans carrying provider URLs are not exported`() {
        assertFalse(predicate.isExportable(span(mapOf("http.url" to "https://api.polygon.io/v2/reference/news?apiKey=secret"))))
    }
}
