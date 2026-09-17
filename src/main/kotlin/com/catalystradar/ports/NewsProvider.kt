package com.catalystradar.ports

import java.time.Instant

/**
 * Provider-neutral news capability. Implementations (Polygon primary,
 * Finnhub fallback) translate their own wire formats into [RawArticle]
 * and map failures into the [ProviderError] hierarchy.
 */
interface NewsProvider {
    suspend fun fetch(request: NewsFetchRequest): NewsFetchResult
}

data class NewsFetchRequest(
    val tickers: List<String> = emptyList(),
    val from: Instant? = null,
    val to: Instant? = null,
    val pageSize: Int = 50,
    val cursor: String? = null,
) {
    init {
        require(pageSize in 1..1000) { "pageSize must be within 1..1000, was $pageSize" }
    }
}

/**
 * One normalized provider article. Tickers are the provider's own symbols;
 * company resolution against the supported universe happens downstream.
 */
data class RawArticle(
    val provider: String,
    val providerArticleId: String?,
    val url: String?,
    val title: String,
    val body: String,
    val publishedAt: Instant?,
    val tickers: List<String> = emptyList(),
)

data class NewsFetchResult(
    val articles: List<RawArticle>,
    val nextCursor: String?,
)
