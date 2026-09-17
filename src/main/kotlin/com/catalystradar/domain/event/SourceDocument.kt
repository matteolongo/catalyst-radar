package com.catalystradar.domain.event

import java.time.Instant
import java.util.UUID

/**
 * Normalized source material (for example a news article) that serves as
 * evidence for catalyst events. A source document is not itself an event:
 * one document may describe multiple events, and multiple documents may
 * describe the same underlying event.
 *
 * The provider is a plain name so provider SDK types never leak into the domain.
 */
data class SourceDocument(
    val id: UUID = UUID.randomUUID(),
    val provider: String,
    val providerDocumentId: String? = null,
    val canonicalUrl: String? = null,
    val title: String,
    val body: String,
    val publishedAt: Instant? = null,
    val discoveredAt: Instant,
    val contentHash: String? = null,
) {
    init {
        require(provider.isNotBlank()) { "provider must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(body.isNotBlank()) { "body must not be blank" }
    }
}
