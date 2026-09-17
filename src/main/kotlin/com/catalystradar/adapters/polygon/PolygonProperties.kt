package com.catalystradar.adapters.polygon

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Polygon (Massive) Stocks API access. Credentials come from the
 * POLYGON_API_KEY environment variable and never appear in logs.
 */
@Component
@ConfigurationProperties(prefix = "catalyst.polygon")
data class PolygonProperties(
    var baseUrl: String = "https://api.polygon.io",
    var apiKey: String = "",
)
