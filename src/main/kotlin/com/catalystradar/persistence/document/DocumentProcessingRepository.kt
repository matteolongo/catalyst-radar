package com.catalystradar.persistence.document

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository
import java.time.Instant
import java.util.UUID

interface DocumentProcessingRepository : ListCrudRepository<DocumentProcessingRow, UUID> {

    @Query(
        """
        SELECT *
        FROM document_processing p
        -- PipelineService uses an in-process single-flight guard. Retrying a
        -- PROCESSING row therefore recovers work interrupted by a restart
        -- without allowing local overlap.
        WHERE (p.status IN ('PENDING', 'PROCESSING')
            OR (p.status = 'RETRYABLE_ERROR' AND p.next_attempt_at <= :now))
          -- Pause work linked only to inactive companies. Orphaned rows still need cleanup.
          AND COALESCE((
            SELECT bool_or(c.active)
            FROM source_document_companies dc
            JOIN companies c ON c.id = dc.company_id
            WHERE dc.source_document_id = p.source_document_id
          ), true)
        ORDER BY COALESCE(p.next_attempt_at, p.created_at), p.source_document_id
        LIMIT :limit
        """,
    )
    fun findDue(now: Instant, limit: Int): List<DocumentProcessingRow>
}
