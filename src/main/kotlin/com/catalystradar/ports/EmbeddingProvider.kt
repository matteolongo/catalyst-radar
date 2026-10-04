package com.catalystradar.ports

import java.util.UUID

data class EmbeddingRequest(val text: String, val sourceDocumentId: UUID? = null, val processingAttemptId: UUID? = null)

/**
 * Text embedding capability for clustering and deduplication. The
 * embedding choice is an implementation baseline (currently
 * text-embedding-3-small at 1536 dimensions), never a domain concern.
 */
interface EmbeddingProvider {
    val model: String

    suspend fun embed(text: String): Embedding
    suspend fun embed(request: EmbeddingRequest): Embedding = embed(request.text)
}

data class Embedding(
    val values: List<Float>,
    val model: String,
)
