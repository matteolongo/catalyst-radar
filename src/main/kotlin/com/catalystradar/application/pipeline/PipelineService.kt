package com.catalystradar.application.pipeline

import com.catalystradar.application.catalyst.CatalystService
import com.catalystradar.application.clustering.EventClusteringService
import com.catalystradar.application.event.EventNormalizationService
import com.catalystradar.application.ingestion.DocumentProcessingStatus
import com.catalystradar.application.ingestion.IngestionCycleResult
import com.catalystradar.application.ingestion.IngestionService
import com.catalystradar.application.ingestion.IngestionStatus
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.DocumentProcessingStore
import com.catalystradar.persistence.document.SourceDocumentCompanyStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.ports.ExtractionRequest
import com.catalystradar.ports.ProviderException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class PipelineResult(
    val status: String,
    val documentsProcessed: Int,
    val eventsExtracted: Int,
    val companiesRescored: Int,
    val error: String? = null,
    val documentsSkipped: Int = 0,
    val documentsRetryScheduled: Int = 0,
    val documentsTerminalFailures: Int = 0,
    val alreadyRunning: Boolean = false,
)

/**
 * End-to-end catalyst pipeline. Ingestion only registers source material;
 * this service drains the durable processing queue so a transient extraction
 * failure is retried on a later cycle even when the document is deduplicated.
 */
@Service
class PipelineService(
    private val ingestion: IngestionService,
    private val extraction: EventExtractionProvider,
    private val normalization: EventNormalizationService,
    private val clustering: EventClusteringService,
    private val catalyst: CatalystService,
    private val documents: SourceDocumentStore,
    private val documentCompanies: SourceDocumentCompanyStore,
    private val processing: DocumentProcessingStore,
    private val companies: CompanyStore,
    private val properties: PipelineProperties,
    private val metrics: CatalystMetrics,
) {

    private val log = LoggerFactory.getLogger(PipelineService::class.java)
    private val running = AtomicBoolean(false)

    suspend fun runCycle(now: Instant = Instant.now()): PipelineResult {
        if (!running.compareAndSet(false, true)) {
            metrics.pipelineCycle("skipped")
            return PipelineResult(
                status = "SKIPPED",
                documentsProcessed = 0,
                eventsExtracted = 0,
                companiesRescored = 0,
                alreadyRunning = true,
            )
        }
        return try {
            runCycleOnce(now)
        } catch (e: RuntimeException) {
            val error = sanitize(e)
            log.error("pipeline cycle failed: {}", error)
            metrics.pipelineCycle("failed")
            PipelineResult("FAILED", 0, 0, 0, error)
        } finally {
            running.set(false)
        }
    }

    private suspend fun runCycleOnce(now: Instant): PipelineResult {
        require(properties.batchSize in 1..500) { "pipeline batchSize must be within 1..500" }
        require(properties.maxAttempts >= 1) { "pipeline maxAttempts must be positive" }
        require(!properties.retryDelay.isNegative && !properties.retryDelay.isZero) {
            "pipeline retryDelay must be positive"
        }

        var firstError: String? = null
        val ingestionResult = try {
            ingestion.ingestCycle(now)
        } catch (e: RuntimeException) {
            firstError = sanitize(e)
            log.warn("pipeline ingestion failed: {}", firstError)
            IngestionCycleResult(emptyList())
        }

        var documentsProcessed = 0
        var eventsExtracted = 0
        var documentsSkipped = 0
        var documentsRetryScheduled = 0
        var documentsTerminalFailures = 0
        var documentsCompleted = 0
        val affectedCompanies = linkedSetOf<UUID>()

        for (record in processing.findDue(now, properties.batchSize)) {
            val outcome = processDocument(record.sourceDocumentId, now)
            documentsProcessed++
            eventsExtracted += outcome.eventsExtracted
            affectedCompanies += outcome.affectedCompanies
            when (outcome.status) {
                DocumentProcessingStatus.COMPLETED -> documentsCompleted++
                DocumentProcessingStatus.SKIPPED -> documentsSkipped++
                DocumentProcessingStatus.RETRYABLE_ERROR -> documentsRetryScheduled++
                DocumentProcessingStatus.TERMINAL_ERROR -> documentsTerminalFailures++
                else -> error("processing outcome must be final")
            }
            if (firstError == null && outcome.error != null) firstError = outcome.error
        }

        var companiesRescored = 0
        for (companyId in affectedCompanies) {
            try {
                catalyst.recalculate(companyId, now)
                companiesRescored++
            } catch (e: RuntimeException) {
                val error = sanitize(e)
                if (firstError == null) firstError = error
                log.warn("pipeline recalculation failed for company {}: {}", companyId, error)
            }
        }

        val providerIssue = ingestionResult.runs.any { it.status != IngestionStatus.SUCCESS }
        val noProviderSucceeded = ingestionResult.runs.isNotEmpty() && ingestionResult.runs.all {
            it.status == IngestionStatus.FAILED || (it.status == IngestionStatus.PARTIAL && it.added == 0)
        }
        val status = when {
            noProviderSucceeded && documentsCompleted == 0 -> "FAILED"
            providerIssue || firstError != null || documentsRetryScheduled > 0 || documentsTerminalFailures > 0 -> "PARTIAL"
            else -> "SUCCESS"
        }
        val error = firstError ?: ingestionResult.runs.firstOrNull { it.status != IngestionStatus.SUCCESS }?.error
        metrics.pipelineCycle(status.lowercase())
        return PipelineResult(
            status = status,
            documentsProcessed = documentsProcessed,
            eventsExtracted = eventsExtracted,
            companiesRescored = companiesRescored,
            error = error,
            documentsSkipped = documentsSkipped,
            documentsRetryScheduled = documentsRetryScheduled,
            documentsTerminalFailures = documentsTerminalFailures,
        )
    }

    private suspend fun processDocument(sourceDocumentId: UUID, now: Instant): DocumentOutcome {
        val attempt = processing.markProcessing(sourceDocumentId, now)
        return try {
            val document = documents.findById(sourceDocumentId)
            if (document == null) {
                processing.markTerminal(sourceDocumentId, "MISSING_DOCUMENT", "source document no longer exists", now)
                return DocumentOutcome(DocumentProcessingStatus.TERMINAL_ERROR, error = "source document no longer exists")
            }
            val resolved = documentCompanies.findCompanyIds(sourceDocumentId).mapNotNull(companies::findById)
            if (resolved.isEmpty()) {
                processing.markSkipped(sourceDocumentId, now)
                return DocumentOutcome(DocumentProcessingStatus.SKIPPED)
            }

            val extractionResult = extraction.extract(ExtractionRequest(document, resolved))
            val events = normalization.processDocument(document, extractionResult, resolved)
            events.forEach { clustering.clusterEvent(it.id) }
            if (!extractionResult.documentRelevant) {
                processing.markSkipped(sourceDocumentId, now)
                DocumentOutcome(DocumentProcessingStatus.SKIPPED)
            } else {
                processing.markCompleted(sourceDocumentId, now)
                DocumentOutcome(
                    status = DocumentProcessingStatus.COMPLETED,
                    eventsExtracted = events.size,
                    affectedCompanies = events.mapTo(linkedSetOf()) { it.companyId },
                )
            }
        } catch (e: ProviderException) {
            handleProviderFailure(sourceDocumentId, attempt.attemptCount, e, now)
        } catch (e: RuntimeException) {
            val error = sanitize(e)
            processing.markTerminal(sourceDocumentId, "PROCESSING_FAILURE", error, now)
            DocumentOutcome(DocumentProcessingStatus.TERMINAL_ERROR, error = error)
        }
    }

    private fun handleProviderFailure(
        sourceDocumentId: UUID,
        attemptCount: Int,
        failure: ProviderException,
        now: Instant,
    ): DocumentOutcome {
        val error = sanitize(failure)
        if (failure.isRetryable() && attemptCount < properties.maxAttempts) {
            processing.markRetryable(
                sourceDocumentId = sourceDocumentId,
                errorCode = failure.code(),
                errorMessage = error,
                nextAttemptAt = now.plus(retryDelay(failure, attemptCount)),
                now = now,
            )
            return DocumentOutcome(DocumentProcessingStatus.RETRYABLE_ERROR, error = error)
        }
        processing.markTerminal(sourceDocumentId, failure.code(), error, now)
        return DocumentOutcome(DocumentProcessingStatus.TERMINAL_ERROR, error = error)
    }

    private fun retryDelay(failure: ProviderException, attemptCount: Int): Duration {
        if (failure is ProviderException.RateLimited && failure.retryAfterSeconds != null) {
            return Duration.ofSeconds(failure.retryAfterSeconds)
        }
        val multiplier = 1L shl (attemptCount - 1).coerceAtMost(10)
        return properties.retryDelay.multipliedBy(multiplier)
    }

    private fun ProviderException.isRetryable(): Boolean =
        this is ProviderException.RateLimited || this is ProviderException.TemporaryUnavailable

    private fun ProviderException.code(): String = when (this) {
        is ProviderException.RateLimited -> "RATE_LIMITED"
        is ProviderException.TemporaryUnavailable -> "TEMPORARY_UNAVAILABLE"
        is ProviderException.AuthenticationFailed -> "AUTHENTICATION_FAILED"
        is ProviderException.InvalidResponse -> "INVALID_RESPONSE"
        is ProviderException.PermanentFailure -> "PERMANENT_FAILURE"
    }

    private fun sanitize(error: Throwable): String =
        (error.message ?: error::class.simpleName ?: "pipeline failure")
            .replace(Regex("\\s+"), " ")
            .take(500)

    private data class DocumentOutcome(
        val status: DocumentProcessingStatus,
        val eventsExtracted: Int = 0,
        val affectedCompanies: Set<UUID> = emptySet(),
        val error: String? = null,
    )
}
