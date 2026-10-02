package com.catalystradar.persistence.document

import com.catalystradar.application.ingestion.DocumentProcessing
import com.catalystradar.application.ingestion.DocumentProcessingStatus
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("document_processing")
data class DocumentProcessingRow(
    @Id
    @Column("source_document_id")
    val sourceDocumentId: UUID,
    val status: String,
    @Column("attempt_count") val attemptCount: Int,
    @Column("next_attempt_at") val nextAttemptAt: Instant?,
    @Column("last_error_code") val lastErrorCode: String?,
    @Column("last_error_message") val lastErrorMessage: String?,
    @Column("completed_at") val completedAt: Instant?,
    @Column("created_at") val createdAt: Instant,
    @Column("updated_at") val updatedAt: Instant,
)

fun DocumentProcessingRow.toRecord() = DocumentProcessing(
    sourceDocumentId = sourceDocumentId,
    status = DocumentProcessingStatus.valueOf(status),
    attemptCount = attemptCount,
    nextAttemptAt = nextAttemptAt,
    lastErrorCode = lastErrorCode,
    lastErrorMessage = lastErrorMessage,
    completedAt = completedAt,
)
