package com.catalystradar.application.ingestion

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "catalyst.ingestion")
data class IngestionProperties(
    var enabled: Boolean = false,
    var interval: Duration = Duration.ofMinutes(30),
    var lookback: Duration = Duration.ofHours(2),
    var provider: String = "polygon",
    var fallbackProvider: String = "finnhub",
    var pageSize: Int = 50,
)
