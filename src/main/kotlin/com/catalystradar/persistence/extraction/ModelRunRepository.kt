package com.catalystradar.persistence.extraction

import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface ModelRunRepository : ListCrudRepository<ModelRunRow, UUID> {
    fun findBySourceDocumentId(sourceDocumentId: UUID): List<ModelRunRow>
}
