package com.catalystradar.ports

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceDocument
import java.time.Instant

/**
 * Structured event-extraction capability. The provider interprets
 * unstructured text and returns candidates; Kotlin validates them and
 * the deterministic engine scores. The model never sees scoring rules.
 */
interface EventExtractionProvider {
    suspend fun extract(request: ExtractionRequest): ExtractionResult
}

data class ExtractionRequest(
    val document: SourceDocument,
    val companies: List<Company>,
)

data class ExtractionResult(
    val documentRelevant: Boolean,
    val events: List<ExtractedEvent>,
)

/**
 * One extraction candidate. Deliberately narrower than [CatalystEvent]:
 * source quality comes from provider tier at validation time, and
 * scheduled defaults to false until stated otherwise.
 */
data class ExtractedEvent(
    val ticker: String,
    val type: EventType,
    val direction: Direction,
    val confidence: Double,
    val magnitude: Double?,
    val surprise: Double?,
    val materiality: Double?,
    val expectedHorizon: EventHorizon,
    val directness: Directness,
    val eventTimestamp: Instant?,
    val evidence: List<EvidenceSpan>,
    val attributes: Map<String, String>,
)

data class EvidenceSpan(
    val quoteOrFact: String,
    val sourceOffsetHint: String?,
)
