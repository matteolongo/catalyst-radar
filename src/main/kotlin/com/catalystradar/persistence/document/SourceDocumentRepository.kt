package com.catalystradar.persistence.document

import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface SourceDocumentRepository : ListCrudRepository<SourceDocumentRow, UUID> {
    fun findByProviderAndProviderDocumentId(provider: String, providerDocumentId: String): SourceDocumentRow?

    fun findByContentHash(contentHash: String): SourceDocumentRow?
}
