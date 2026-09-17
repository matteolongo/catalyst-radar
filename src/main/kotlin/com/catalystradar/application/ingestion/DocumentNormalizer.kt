package com.catalystradar.application.ingestion

import com.catalystradar.ports.RawArticle
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/**
 * Normalized provider article ready for dedup checks and persistence.
 * The content hash covers the stable title/body/url representation so
 * re-fetches and cross-provider copies collapse onto one identity.
 */
data class NormalizedDocument(
    val provider: String,
    val providerDocumentId: String?,
    val url: String?,
    val title: String,
    val body: String,
    val publishedAt: Instant?,
    val discoveredAt: Instant,
    val contentHash: String,
)

fun normalizeArticle(article: RawArticle, discoveredAt: Instant): NormalizedDocument {
    val title = article.title.trim()
    val body = article.body.trim()
    val url = article.url?.trim().orEmpty()
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = HexFormat.of().formatHex(digest.digest("$title\n$body\n$url".toByteArray()))
    return NormalizedDocument(
        provider = article.provider,
        providerDocumentId = article.providerArticleId,
        url = article.url,
        title = title,
        body = body,
        publishedAt = article.publishedAt,
        discoveredAt = discoveredAt,
        contentHash = hash,
    )
}
