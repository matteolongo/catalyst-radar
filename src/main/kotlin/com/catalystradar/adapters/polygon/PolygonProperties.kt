package com.catalystradar.adapters.polygon

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Polygon (Massive) Stocks API access. Credentials come from the
 * POLYGON_API_KEY environment variable and never appear in logs.
 */
@Component
@ConfigurationProperties(prefix = "catalyst.polygon")
data class PolygonProperties(
    var baseUrl: String = "https://api.polygon.io",
    var apiKey: String = "",
    var connectTimeout: Duration = Duration.ofSeconds(10),
    var readTimeout: Duration = Duration.ofSeconds(30),
)
