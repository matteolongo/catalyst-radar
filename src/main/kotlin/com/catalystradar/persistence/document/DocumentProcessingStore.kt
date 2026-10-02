package com.catalystradar.persistence.document

import com.catalystradar.application.ingestion.DocumentProcessing
import com.catalystradar.application.ingestion.DocumentProcessingStatus
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class DocumentProcessingStore(
    private val repository: DocumentProcessingRepository,
    private val template: JdbcAggregateTemplate,
) {

    fun ensurePending(sourceDocumentId: UUID, now: Instant = Instant.now()): DocumentProcessing {
        findBySourceDocumentId(sourceDocumentId)?.let { return it }
        val row = DocumentProcessingRow(
            sourceDocumentId = sourceDocumentId,
            status = DocumentProcessingStatus.PENDING.name,
            attemptCount = 0,
            nextAttemptAt = null,
            lastErrorCode = null,
            lastErrorMessage = null,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        return try {
            template.insert(row).toRecord()
        } catch (e: DataIntegrityViolationException) {
            findBySourceDocumentId(sourceDocumentId) ?: throw e
        }
    }

    fun findBySourceDocumentId(sourceDocumentId: UUID): DocumentProcessing? =
        repository.findById(sourceDocumentId).map { it.toRecord() }.orElse(null)

    fun findDue(now: Instant, limit: Int): List<DocumentProcessing> {
        require(limit in 1..500) { "limit must be within 1..500" }
        return repository.findDue(now, limit).map { it.toRecord() }
    }

    fun markProcessing(sourceDocumentId: UUID, now: Instant = Instant.now()): DocumentProcessing =
        update(sourceDocumentId) {
            it.copy(
                status = DocumentProcessingStatus.PROCESSING.name,
                attemptCount = it.attemptCount + 1,
                nextAttemptAt = null,
                updatedAt = now,
            )
        }

    fun markCompleted(sourceDocumentId: UUID, now: Instant = Instant.now()): DocumentProcessing =
        update(sourceDocumentId) {
            it.copy(
                status = DocumentProcessingStatus.COMPLETED.name,
                nextAttemptAt = null,
                lastErrorCode = null,
                lastErrorMessage = null,
                completedAt = now,
                updatedAt = now,
            )
        }

    fun markSkipped(sourceDocumentId: UUID, now: Instant = Instant.now()): DocumentProcessing =
        update(sourceDocumentId) {
            it.copy(
                status = DocumentProcessingStatus.SKIPPED.name,
                nextAttemptAt = null,
                lastErrorCode = null,
                lastErrorMessage = null,
                completedAt = now,
                updatedAt = now,
            )
        }

    fun markRetryable(
        sourceDocumentId: UUID,
        errorCode: String,
        errorMessage: String,
        nextAttemptAt: Instant,
        now: Instant = Instant.now(),
    ): DocumentProcessing =
        update(sourceDocumentId) {
            it.copy(
                status = DocumentProcessingStatus.RETRYABLE_ERROR.name,
                nextAttemptAt = nextAttemptAt,
                lastErrorCode = errorCode,
                lastErrorMessage = errorMessage,
                completedAt = null,
                updatedAt = now,
            )
        }

    fun markTerminal(
        sourceDocumentId: UUID,
        errorCode: String,
        errorMessage: String,
        now: Instant = Instant.now(),
    ): DocumentProcessing =
        update(sourceDocumentId) {
            it.copy(
                status = DocumentProcessingStatus.TERMINAL_ERROR.name,
                nextAttemptAt = null,
                lastErrorCode = errorCode,
                lastErrorMessage = errorMessage,
                completedAt = now,
                updatedAt = now,
            )
        }

    private fun update(
        sourceDocumentId: UUID,
        transform: (DocumentProcessingRow) -> DocumentProcessingRow,
    ): DocumentProcessing {
        val current = repository.findById(sourceDocumentId).orElseThrow {
            NoSuchElementException("processing record not found for source document $sourceDocumentId")
        }
        return repository.save(transform(current)).toRecord()
    }
}
