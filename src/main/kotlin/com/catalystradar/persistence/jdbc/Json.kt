package com.catalystradar.persistence.jdbc

import com.catalystradar.domain.event.EventEvidence
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

internal val jsonMapper: ObjectMapper = ObjectMapper()

internal fun Map<String, String>.toJsonB(): JsonB =
    JsonB(jsonMapper.writeValueAsString(this))

internal fun JsonB.toStringMap(): Map<String, String> =
    jsonMapper.readValue(json, object : TypeReference<Map<String, String>>() {})

internal fun List<EventEvidence>.toJsonB(): JsonB =
    JsonB(
        jsonMapper.writeValueAsString(
            map { evidence ->
                mapOf(
                    "quoteOrFact" to evidence.quoteOrFact,
                    "sourceOffsetHint" to evidence.sourceOffsetHint,
                )
            },
        ),
    )

internal fun JsonB.toEventEvidence(): List<EventEvidence> =
    jsonMapper.readValue(json, object : TypeReference<List<Map<String, String?>>>() {})
        .map { evidence ->
            EventEvidence(
                quoteOrFact = requireNotNull(evidence["quoteOrFact"]) { "event evidence must contain quoteOrFact" },
                sourceOffsetHint = evidence["sourceOffsetHint"],
            )
        }
