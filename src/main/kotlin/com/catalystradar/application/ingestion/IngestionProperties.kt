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
    var pageSize: Int = 1000,
    /** Provider pages followed per ticker chunk before the run is marked truncated. */
    var maxPagesPerChunk: Int = 10,
    /** How the primary provider is asked for news (ADR 0002). */
    var scanMode: ScanMode = ScanMode.MARKET,
)

enum class ScanMode {
    /** News per active ticker, in chunks: only tracked companies' news. */
    TICKERS,

    /** All news in the window, one paginated request; untracked tickers become candidates. */
    MARKET,
}
