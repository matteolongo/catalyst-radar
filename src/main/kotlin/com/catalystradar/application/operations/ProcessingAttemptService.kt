package com.catalystradar.application.operations

import com.catalystradar.application.ingestion.DocumentProcessingStatus
import com.catalystradar.application.pipeline.DocumentOutcome
import com.catalystradar.persistence.document.DocumentProcessingStore
import com.catalystradar.persistence.operations.ProcessingAttemptStore
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class ProcessingAttemptService(
    private val processing: DocumentProcessingStore,
    private val attempts: ProcessingAttemptStore,
    @Qualifier("operationsClock") private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun begin(documentId: UUID, runId: UUID, processingAt: Instant): StartedAttempt =
        requireNotNull(transaction.execute {
            attempts.lockDocument(documentId)
            val now = clock.instant()
            attempts.interruptRunningForDocument(documentId, "UNFINISHED_PREVIOUS_RUN", now)
            val latest = processing.markProcessing(documentId, processingAt)
            val id = UUID.randomUUID()
            attempts.start(id, documentId, runId, latest.attemptCount, now)
            StartedAttempt(id, documentId, runId, latest.attemptCount)
        })

    fun fail(
        attempt: StartedAttempt,
        status: DocumentProcessingStatus,
        code: String,
        nextAttemptAt: Instant?,
        processingAt: Instant,
    ): DocumentOutcome {
        require(status == DocumentProcessingStatus.RETRYABLE_ERROR || status == DocumentProcessingStatus.TERMINAL_ERROR)
        require((status == DocumentProcessingStatus.RETRYABLE_ERROR) == (nextAttemptAt != null))
        val message = OperationalErrors.message(code)
        return requireNotNull(transaction.execute {
            attempts.requireDocument(attempt.id, attempt.documentId)
            if (status == DocumentProcessingStatus.RETRYABLE_ERROR) {
                processing.markRetryable(attempt.documentId, code, message, requireNotNull(nextAttemptAt), processingAt)
            } else {
                processing.markTerminal(attempt.documentId, code, message, processingAt)
            }
            attempts.finish(attempt.id, AttemptStatus.valueOf(status.name), 0, 0, code, nextAttemptAt, clock.instant())
            DocumentOutcome(status, error = message)
        })
    }

    fun interrupt(attempt: StartedAttempt, code: String) {
        transaction.executeWithoutResult {
            attempts.requireDocument(attempt.id, attempt.documentId)
            attempts.interrupt(attempt.id, code, clock.instant())
        }
    }
}
