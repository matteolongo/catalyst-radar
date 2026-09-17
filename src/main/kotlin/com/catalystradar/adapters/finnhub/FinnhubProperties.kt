package com.catalystradar.adapters.finnhub

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Finnhub API access (secondary/fallback v0.1 source). Credentials come
 * from the FINNHUB_API_KEY environment variable and never appear in logs.
 */
@Component
@ConfigurationProperties(prefix = "catalyst.finnhub")
data class FinnhubProperties(
    var baseUrl: String = "https://finnhub.io",
    var apiKey: String = "",
)
