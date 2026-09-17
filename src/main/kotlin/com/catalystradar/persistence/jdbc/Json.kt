package com.catalystradar.persistence.jdbc

import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

internal val jsonMapper: ObjectMapper = ObjectMapper()

internal fun Map<String, String>.toJsonB(): JsonB =
    JsonB(jsonMapper.writeValueAsString(this))

internal fun JsonB.toStringMap(): Map<String, String> =
    jsonMapper.readValue(json, object : TypeReference<Map<String, String>>() {})
