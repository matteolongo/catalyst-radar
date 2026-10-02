package com.catalystradar.application.clustering

import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.EventEvidence
import com.catalystradar.domain.event.EventType
import java.util.Locale

/**
 * Canonical text behind cluster embeddings: ticker, type, direction,
 * sorted attributes, and normalized evidence. Deterministic gates (company, type, time
 * window) do the heavy filtering; the embedding breaks ties between
 * same-typed candidates, so the text favors stable identity signals
 * over article wording.
 */
fun clusterText(
    ticker: String,
    type: EventType,
    direction: String,
    attributes: Map<String, String>,
    evidence: List<EventEvidence> = emptyList(),
): String {
    val attributesText = attributes.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" }
    val evidenceText = evidence
        .map { it.quoteOrFact.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .joinToString(" ")
    return listOf(ticker, type.name, direction, attributesText, evidenceText)
        .filter { it.isNotBlank() }
        .joinToString(" ")
}

fun candidateText(ticker: String, event: CatalystEvent): String =
    clusterText(ticker, event.type, event.direction.name, event.attributes, event.evidence)
