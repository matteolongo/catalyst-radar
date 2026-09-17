package com.catalystradar.application.clustering

import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.EventType

/**
 * Canonical text behind cluster embeddings: ticker, type, direction,
 * and sorted attributes. Deterministic gates (company, type, time
 * window) do the heavy filtering; the embedding breaks ties between
 * same-typed candidates, so the text favors stable identity signals
 * over article wording.
 */
fun clusterText(
    ticker: String,
    type: EventType,
    direction: String,
    attributes: Map<String, String>,
): String {
    val facts = attributes.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" }
    return listOf(ticker, type.name, direction, facts)
        .filter { it.isNotBlank() }
        .joinToString(" ")
}

fun candidateText(ticker: String, event: CatalystEvent): String =
    clusterText(ticker, event.type, event.direction.name, event.attributes)
