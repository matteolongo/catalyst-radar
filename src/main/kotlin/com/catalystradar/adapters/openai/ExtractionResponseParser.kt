package com.catalystradar.adapters.openai

import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventFamily
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.ports.EvidenceSpan
import com.catalystradar.ports.ExtractedEvent
import com.catalystradar.ports.ExtractionResult
import com.catalystradar.ports.ProviderException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * Pure parsing half of extraction: model content JSON into validated
 * candidate shapes. Shared by the live adapter and the golden
 * regression fixtures, so prompt/model changes measure against the
 * same bar without spending API calls.
 */
internal class ExtractionResponseParser(
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    fun parse(response: OpenAiChatResponse): ExtractionResult {
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
}
