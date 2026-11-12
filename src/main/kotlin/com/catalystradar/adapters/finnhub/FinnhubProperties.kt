package com.catalystradar.adapters.finnhub

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Finnhub API access (secondary/fallback v0.1 source). Credentials come
 * from the FINNHUB_API_KEY environment variable and never appear in logs.
 */
@Component
@ConfigurationProperties(prefix = "catalyst.finnhub")
data class FinnhubProperties(
    var baseUrl: String = "https://finnhub.io",
    var apiKey: String = "",
    var connectTimeout: Duration = Duration.ofSeconds(10),
    var readTimeout: Duration = Duration.ofSeconds(30),
)
