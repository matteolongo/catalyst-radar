package com.catalystradar.persistence.document

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository
import java.time.Instant
import java.util.UUID

interface DocumentProcessingRepository : ListCrudRepository<DocumentProcessingRow, UUID> {

    @Query(
        """
        SELECT *
        FROM document_processing
        WHERE status = 'PENDING'
           OR (status = 'RETRYABLE_ERROR' AND next_attempt_at <= :now)
        ORDER BY COALESCE(next_attempt_at, created_at), source_document_id
        LIMIT :limit
        """,
    )
    fun findDue(now: Instant, limit: Int): List<DocumentProcessingRow>
}
