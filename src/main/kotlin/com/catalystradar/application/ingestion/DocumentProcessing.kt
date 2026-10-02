package com.catalystradar.application.ingestion

import java.time.Instant
import java.util.UUID

enum class DocumentProcessingStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    SKIPPED,
    RETRYABLE_ERROR,
    TERMINAL_ERROR,
}

data class DocumentProcessing(
    val sourceDocumentId: UUID,
    val status: DocumentProcessingStatus,
    val attemptCount: Int,
    val nextAttemptAt: Instant? = null,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val completedAt: Instant? = null,
) {
    init {
        require(attemptCount >= 0) { "attemptCount must not be negative" }
    }
}
