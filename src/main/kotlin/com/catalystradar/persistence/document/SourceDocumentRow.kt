package com.catalystradar.persistence.document

import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.persistence.jdbc.JsonB
import com.pgvector.PGvector
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * source_documents row. Embeddings and raw provider payload are
 * persistence-level provenance: they travel on the row, never in the
 * domain model.
 */
@Table("source_documents")
data class SourceDocumentRow(
    @Id val id: UUID,
    val provider: String,
    @Column("provider_document_id") val providerDocumentId: String?,
    @Column("canonical_url") val canonicalUrl: String?,
    val title: String,
    val body: String,
    @Column("published_at") val publishedAt: Instant?,
    @Column("discovered_at") val discoveredAt: Instant,
    @Column("content_hash") val contentHash: String?,
    val embedding: PGvector?,
    @Column("raw_payload") val rawPayload: JsonB,
)

fun SourceDocumentRow.toDomain() = SourceDocument(
    id = id,
    provider = provider,
    providerDocumentId = providerDocumentId,
    canonicalUrl = canonicalUrl,
    title = title,
    body = body,
    publishedAt = publishedAt,
    discoveredAt = discoveredAt,
    contentHash = contentHash,
)

fun SourceDocument.toRow(
    embedding: PGvector? = null,
    rawPayload: JsonB = JsonB("{}"),
) = SourceDocumentRow(
    id = id,
    provider = provider,
    providerDocumentId = providerDocumentId,
    canonicalUrl = canonicalUrl,
    title = title,
    body = body,
    publishedAt = publishedAt,
    discoveredAt = discoveredAt,
    contentHash = contentHash,
    embedding = embedding,
    rawPayload = rawPayload,
)
