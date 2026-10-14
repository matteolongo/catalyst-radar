package com.catalystradar.observability

import com.catalystradar.common.Versions
import com.catalystradar.ports.EmbeddingRequest
import com.catalystradar.ports.ExtractionRequest
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Emits one OpenTelemetry span per LLM/embedding call using the GenAI
 * semantic conventions and LangSmith span attributes, so LangSmith
 * renders them as `llm`/`embedding` runs alongside the model/latency/token
 * data. Best-effort: tracing never throws into the ingestion pipeline and
 * every span ends at most once. Disabled unless [LangSmithProperties.enabled].
 */
@Component
class LlmTracing(
    private val openTelemetry: ObjectProvider<OpenTelemetry>,
    private val properties: LangSmithProperties,
) {

    fun beginExtraction(model: String, request: ExtractionRequest): LlmCallTrace =
        startSpan("extract") { span ->
            span.setAttribute(LANGSMITH_SPAN_KIND, KIND_LLM)
            span.setAttribute(GEN_AI_SYSTEM, SYSTEM_OPENAI)
            span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_CHAT)
            span.setAttribute(GEN_AI_REQUEST_MODEL, model)
            span.setAttribute(META_SOURCE_DOCUMENT_ID, request.document.id.toString())
            request.processingAttemptId?.let { span.setAttribute(META_PROCESSING_ATTEMPT_ID, it.toString()) }
            span.setAttribute(META_PROMPT_VERSION, Versions.PROMPT_V1)
            span.setAttribute(META_EXTRACTOR_VERSION, Versions.EXTRACTOR_V1)
        }

    fun beginEmbedding(model: String, request: EmbeddingRequest): LlmCallTrace =
        startSpan("embed") { span ->
            span.setAttribute(LANGSMITH_SPAN_KIND, KIND_EMBEDDING)
            span.setAttribute(GEN_AI_SYSTEM, SYSTEM_OPENAI)
            span.setAttribute(GEN_AI_OPERATION_NAME, OPERATION_EMBEDDINGS)
            span.setAttribute(GEN_AI_REQUEST_MODEL, model)
            request.sourceDocumentId?.let { span.setAttribute(META_SOURCE_DOCUMENT_ID, it.toString()) }
            request.processingAttemptId?.let { span.setAttribute(META_PROCESSING_ATTEMPT_ID, it.toString()) }
        }

    private fun startSpan(operation: String, decorate: (Span) -> Unit): LlmCallTrace {
        if (!properties.enabled) return NoopLlmCallTrace
        val otel = openTelemetry.ifAvailable ?: return NoopLlmCallTrace
        return runCatching {
            val span = otel.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION)
                .spanBuilder("openai.$operation")
                .startSpan()
            decorate(span)
            OtelLlmCallTrace(span, properties.captureContent)
        }.getOrElse { NoopLlmCallTrace }
    }

    companion object {
        const val INSTRUMENTATION_NAME = "com.catalystradar.llm"
        const val INSTRUMENTATION_VERSION = "0.1.0"

        const val LANGSMITH_SPAN_KIND = "langsmith.span.kind"
        const val GEN_AI_SYSTEM = "gen_ai.system"
        const val GEN_AI_OPERATION_NAME = "gen_ai.operation.name"
        const val GEN_AI_REQUEST_MODEL = "gen_ai.request.model"
        const val GEN_AI_RESPONSE_MODEL = "gen_ai.response.model"
        const val GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens"
        const val GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens"
        const val GEN_AI_USAGE_TOTAL_TOKENS = "gen_ai.usage.total_tokens"
        const val GEN_AI_PROMPT = "gen_ai.prompt"
        const val GEN_AI_COMPLETION = "gen_ai.completion"
        const val META_SOURCE_DOCUMENT_ID = "langsmith.metadata.source_document_id"
        const val META_PROCESSING_ATTEMPT_ID = "langsmith.metadata.processing_attempt_id"
        const val META_PROMPT_VERSION = "langsmith.metadata.prompt_version"
        const val META_EXTRACTOR_VERSION = "langsmith.metadata.extractor_version"

        private const val KIND_LLM = "llm"
        private const val KIND_EMBEDDING = "embedding"
        private const val SYSTEM_OPENAI = "openai"
        private const val OPERATION_CHAT = "chat"
        private const val OPERATION_EMBEDDINGS = "embeddings"
    }
}

/** One instrumented LLM/embedding call. Exactly one of [succeed]/[fail] ends it. */
interface LlmCallTrace {
    fun prompt(text: String)
    fun completion(text: String)
    fun succeed(responseModel: String?, inputTokens: Int?, outputTokens: Int?)
    fun fail(cause: Throwable)
}

internal object NoopLlmCallTrace : LlmCallTrace {
    override fun prompt(text: String) {}
    override fun completion(text: String) {}
    override fun succeed(responseModel: String?, inputTokens: Int?, outputTokens: Int?) {}
    override fun fail(cause: Throwable) {}
}

internal class OtelLlmCallTrace(
    private val span: Span,
    private val captureContent: Boolean,
) : LlmCallTrace {

    private val ended = AtomicBoolean(false)

    override fun prompt(text: String) {
        if (captureContent) runCatching { span.setAttribute(LlmTracing.GEN_AI_PROMPT, text) }
    }

    override fun completion(text: String) {
        if (captureContent) runCatching { span.setAttribute(LlmTracing.GEN_AI_COMPLETION, text) }
    }

    override fun succeed(responseModel: String?, inputTokens: Int?, outputTokens: Int?) {
        if (!ended.compareAndSet(false, true)) return
        runCatching {
            responseModel?.let { span.setAttribute(LlmTracing.GEN_AI_RESPONSE_MODEL, it) }
            inputTokens?.let { span.setAttribute(LlmTracing.GEN_AI_USAGE_INPUT_TOKENS, it.toLong()) }
            outputTokens?.let { span.setAttribute(LlmTracing.GEN_AI_USAGE_OUTPUT_TOKENS, it.toLong()) }
            if (inputTokens != null && outputTokens != null) {
                span.setAttribute(LlmTracing.GEN_AI_USAGE_TOTAL_TOKENS, (inputTokens + outputTokens).toLong())
            }
            span.setStatus(StatusCode.OK)
        }
        runCatching { span.end() }
    }

    override fun fail(cause: Throwable) {
        if (!ended.compareAndSet(false, true)) return
        runCatching {
            span.recordException(cause)
            span.setStatus(StatusCode.ERROR, cause.message ?: "LLM call failed")
        }
        runCatching { span.end() }
    }
}
