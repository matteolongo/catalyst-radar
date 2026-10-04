package com.catalystradar.persistence.extraction

import org.springframework.data.domain.PageRequest
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Repository
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

@Repository
class ModelRunStore(
    private val repository: ModelRunRepository,
    private val template: JdbcAggregateTemplate,
    private val jdbc: NamedParameterJdbcTemplate,
) {

    fun record(input: ModelRunInput): UUID {
        val id = UUID.randomUUID()
        template.insert(input.toRow(id))
        return id
    }

    fun findByDocument(sourceDocumentId: UUID): List<ModelRunRecord> =
        withRunIds(repository.findBySourceDocumentId(sourceDocumentId).map { it.toRecord() })

    fun listRecent(limit: Int): List<ModelRunRecord> =
        withRunIds(repository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, limit)).map { it.toRecord() })

    private fun withRunIds(records: List<ModelRunRecord>): List<ModelRunRecord> {
        val ids = records.mapNotNull { it.processingAttemptId }.toSet()
        if (ids.isEmpty()) return records
        val runIds = jdbc.query(
            "SELECT id, operation_run_id FROM document_processing_attempts WHERE id IN (:ids)",
            mapOf("ids" to ids),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getObject("operation_run_id", UUID::class.java) }.toMap()
        return records.map { it.copy(runId = runIds[it.processingAttemptId]) }
    }
}
