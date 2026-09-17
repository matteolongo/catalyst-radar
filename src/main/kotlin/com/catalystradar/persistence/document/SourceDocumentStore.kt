package com.catalystradar.persistence.document

import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.persistence.jdbc.JsonB
import com.pgvector.PGvector
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class SourceDocumentStore(
    private val repository: SourceDocumentRepository,
    private val template: JdbcAggregateTemplate,
) {

    fun save(
        document: SourceDocument,
        embedding: PGvector? = null,
        rawPayload: JsonB = JsonB("{}"),
    ): SourceDocument =
        template.insert(document.toRow(embedding, rawPayload)).toDomain()

    fun findById(id: UUID): SourceDocument? =
        repository.findById(id).map { it.toDomain() }.orElse(null)

    fun findByProviderAndProviderDocumentId(provider: String, providerDocumentId: String): SourceDocument? =
        repository.findByProviderAndProviderDocumentId(provider, providerDocumentId)?.toDomain()

    fun findByContentHash(contentHash: String): SourceDocument? =
        repository.findByContentHash(contentHash)?.toDomain()
}
