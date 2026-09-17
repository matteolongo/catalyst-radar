package com.catalystradar.persistence.extraction

import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ModelRunStore(
    private val repository: ModelRunRepository,
    private val template: JdbcAggregateTemplate,
) {

    fun record(input: ModelRunInput): UUID {
        val id = UUID.randomUUID()
        template.insert(input.toRow(id))
        return id
    }

    fun findByDocument(sourceDocumentId: UUID): List<ModelRunRecord> =
        repository.findBySourceDocumentId(sourceDocumentId).map { it.toRecord() }
}
