package com.catalystradar.application.pipeline

import com.catalystradar.application.catalyst.CatalystService
import com.catalystradar.application.clustering.EventClusteringService
import com.catalystradar.application.event.EventNormalizationService
import com.catalystradar.application.ingestion.DocumentProcessingStatus
import com.catalystradar.application.ingestion.IngestionCycleResult
import com.catalystradar.application.ingestion.IngestionService
import com.catalystradar.application.ingestion.IngestionStatus
import com.catalystradar.application.operations.*
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
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Legacy counters retain their meaning; inserted and reused events are counted separately. */
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
    val documentsCompleted: Int = 0,
    val eventsInserted: Int = 0,
    val eventsReused: Int = 0,
    val runId: UUID? = null,
) {
    val documentsConsidered: Int get() = documentsProcessed
}

/** Drains the durable queue after ingestion; remote work precedes narrow outcome transactions. */
@Service
class PipelineService(
    private val ingestion: IngestionService,
    private val extraction: EventExtractionProvider,
    private val normalization: EventNormalizationService,
    private val clustering: EventClusteringService,
    private val persistence: DocumentOutcomePersistenceService,
    private val catalyst: CatalystService,
    private val documents: SourceDocumentStore,
    private val documentCompanies: SourceDocumentCompanyStore,
    private val processing: DocumentProcessingStore,
    private val companies: CompanyStore,
    private val properties: PipelineProperties,
    private val metrics: CatalystMetrics,
    private val recorder: OperationRunRecorder,
    private val attempts: ProcessingAttemptService,
) {
    private val log = LoggerFactory.getLogger(PipelineService::class.java)
    private val running = AtomicBoolean(false)

    suspend fun runCycle(now: Instant = Instant.now(), trigger: OperationTrigger = OperationTrigger.MANUAL): PipelineResult {
        if (!running.compareAndSet(false, true)) {
            metrics.pipelineCycle("skipped")
            return PipelineResult("SKIPPED", 0, 0, 0, alreadyRunning = true)
        }
        var runId: UUID? = null
        var phase = OperationPhase.INGESTION
        var counts = OperationCounts()
        return try {
            val id = recorder.begin(OperationKind.PIPELINE, trigger, now)
            runId = id
            require(properties.batchSize in 1..500) { "pipeline batchSize must be within 1..500" }
            require(properties.maxAttempts >= 1) { "pipeline maxAttempts must be positive" }
            require(!properties.retryDelay.isNegative && !properties.retryDelay.isZero) { "pipeline retryDelay must be positive" }
            var firstErrorCode: String? = null
            val ingestionResult = try {
                ingestion.ingestCycle(now, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                firstErrorCode = "INGESTION_FAILURE"
                recorder.issue(id, phase, firstErrorCode)
                log.warn("pipeline ingestion failed for run {}", id)
                IngestionCycleResult(emptyList())
            }
            ingestionResult.runs.filter { it.status != IngestionStatus.SUCCESS }.forEach { providerRun ->
                val code = providerRun.errorCode ?: "UNKNOWN_FAILURE"
                if (firstErrorCode == null) firstErrorCode = code
                recorder.issue(id, phase, code, provider = providerRun.provider)
            }
            phase = OperationPhase.PROCESSING
            recorder.phase(id, phase)
            val affectedCompanies = linkedSetOf<UUID>()
            for (record in processing.findDue(now, properties.batchSize)) {
                val outcome = processDocument(record.sourceDocumentId, id, now)
                affectedCompanies += outcome.affectedCompanies
                counts = counts.copy(
                    documentsConsidered = counts.documentsConsidered + 1,
                    documentsCompleted = counts.documentsCompleted + if (outcome.status == DocumentProcessingStatus.COMPLETED) 1 else 0,
                    documentsSkipped = counts.documentsSkipped + if (outcome.status == DocumentProcessingStatus.SKIPPED) 1 else 0,
                    documentsRetryScheduled = counts.documentsRetryScheduled + if (outcome.status == DocumentProcessingStatus.RETRYABLE_ERROR) 1 else 0,
                    documentsTerminalFailures = counts.documentsTerminalFailures + if (outcome.status == DocumentProcessingStatus.TERMINAL_ERROR) 1 else 0,
                    eventsInserted = counts.eventsInserted + outcome.eventsInserted,
                    eventsReused = counts.eventsReused + outcome.eventsReused,
                )
                recorder.progress(id, counts)
                metrics.documentProcessing(outcome.status.name.lowercase())
                if (outcome.status == DocumentProcessingStatus.RETRYABLE_ERROR) metrics.documentRetryAttempt()
                if (outcome.error != null && firstErrorCode == null) {
                    firstErrorCode = processing.findBySourceDocumentId(record.sourceDocumentId)?.lastErrorCode ?: "PROCESSING_FAILURE"
                }
            }
            phase = OperationPhase.SCORING
            recorder.phase(id, phase)
            counts = counts.copy(companiesConsidered = affectedCompanies.size)
            recorder.progress(id, counts)
            for (companyId in affectedCompanies) {
                try {
                    catalyst.recalculate(companyId, now)
                    counts = counts.copy(companiesRescored = counts.companiesRescored + 1)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    if (firstErrorCode == null) firstErrorCode = "SCORING_FAILURE"
                    recorder.issue(id, phase, "SCORING_FAILURE", companyId = companyId)
                    counts = counts.copy(companiesFailed = counts.companiesFailed + 1)
                    log.warn("pipeline recalculation failed for company {} run {}", companyId, id)
                }
                recorder.progress(id, counts)
            }
            val providerIssue = ingestionResult.runs.any { it.status != IngestionStatus.SUCCESS }
            val noProviderSucceeded = ingestionResult.runs.isNotEmpty() && ingestionResult.runs.all {
                it.status == IngestionStatus.FAILED || (it.status == IngestionStatus.PARTIAL && it.added == 0)
            }
            val status = when {
                noProviderSucceeded && counts.documentsCompleted == 0 -> "FAILED"
                providerIssue || firstErrorCode != null || counts.documentsRetryScheduled > 0 || counts.documentsTerminalFailures > 0 -> "PARTIAL"
                else -> "SUCCESS"
            }
            recorder.finish(id, OperationStatus.valueOf(status), counts, firstErrorCode, captureComplete = true)
            metrics.pipelineCycle(status.lowercase())
            PipelineResult(
                status = status, documentsProcessed = counts.documentsConsidered,
                eventsExtracted = counts.eventsInserted + counts.eventsReused, companiesRescored = counts.companiesRescored,
                error = firstErrorCode?.let(OperationalErrors::message), documentsSkipped = counts.documentsSkipped,
                documentsRetryScheduled = counts.documentsRetryScheduled, documentsTerminalFailures = counts.documentsTerminalFailures,
                documentsCompleted = counts.documentsCompleted, eventsInserted = counts.eventsInserted, eventsReused = counts.eventsReused,
                runId = id,
            )
        } catch (e: CancellationException) {
            runId?.let { finishIncomplete(it, phase, OperationStatus.CANCELLED, "CANCELLED") }
            throw e
        } catch (e: RuntimeException) {
            val code = when (phase) {
                OperationPhase.INGESTION -> "INGESTION_FAILURE"
                OperationPhase.PROCESSING -> "PROCESSING_FAILURE"
                else -> "SCORING_FAILURE"
            }
            runId?.let { finishIncomplete(it, phase, OperationStatus.FAILED, code) }
            log.error("pipeline cycle failed run={} phase={}", runId, phase)
            metrics.pipelineCycle("failed")
            // The durable ledger preserves committed progress even if an outer stage failed.
            PipelineResult("FAILED", 0, 0, 0, OperationalErrors.message(code), runId = runId)
        } finally {
            running.set(false)
        }
    }

    private fun finishIncomplete(id: UUID, phase: OperationPhase, status: OperationStatus, code: String) {
        runCatching { recorder.issue(id, phase, code) }
            .onFailure { log.warn("pipeline issue recording failed for run {}", id) }
        runCatching { recorder.finish(id, status, null, code, captureComplete = false) }
            .onFailure { log.warn("pipeline final recording failed for run {}", id) }
    }

    private suspend fun processDocument(sourceDocumentId: UUID, runId: UUID, now: Instant): DocumentOutcome {
        val attempt = attempts.begin(sourceDocumentId, runId, now)
        return try {
            val document = documents.findById(sourceDocumentId)
                ?: return attempts.fail(attempt, DocumentProcessingStatus.TERMINAL_ERROR, "PROCESSING_FAILURE", null, now).also {
                    recorder.issue(runId, OperationPhase.PROCESSING, "PROCESSING_FAILURE", documentId = sourceDocumentId)
                }
            val resolved = documentCompanies.findCompanyIds(sourceDocumentId).mapNotNull(companies::findById)
            if (resolved.isEmpty()) {
                return persistence.persist(DocumentPersistencePlan(sourceDocumentId, emptyList(), attempt.id), DocumentProcessingStatus.SKIPPED, now)
            }
            val extractionResult = extraction.extract(ExtractionRequest(document, resolved, attempt.id))
            val prepared = normalization.prepareDocument(document, extractionResult, resolved)
            val plan = DocumentPersistencePlan(
                sourceDocumentId = sourceDocumentId,
                events = prepared.map { candidate ->
                    PlannedEvent(
                        event = candidate.event,
                        fingerprint = candidate.fingerprint,
                        cluster = clustering.prepareClustering(sourceDocumentId, candidate.fingerprint, candidate.event, attempt.id),
                    )
                },
                processingAttemptId = attempt.id,
            )
            val finalStatus = if (extractionResult.documentRelevant) DocumentProcessingStatus.COMPLETED else DocumentProcessingStatus.SKIPPED
            persistence.persist(plan, finalStatus, now)
        } catch (e: CancellationException) {
            runCatching { attempts.interrupt(attempt, "CANCELLED") }
                .onFailure { log.warn("pipeline attempt cancellation recording failed for document {}", sourceDocumentId) }
            throw e
        } catch (e: ProviderException) {
            val retryAt = if (e.isRetryable() && attempt.number < properties.maxAttempts) now.plus(retryDelay(e, attempt.number)) else null
            val code = OperationalErrors.code(e)
            attempts.fail(attempt, if (retryAt != null) DocumentProcessingStatus.RETRYABLE_ERROR else DocumentProcessingStatus.TERMINAL_ERROR, code, retryAt, now).also {
                recorder.issue(runId, OperationPhase.PROCESSING, code, documentId = sourceDocumentId, provider = e.provider)
            }
        } catch (e: RuntimeException) {
            attempts.fail(attempt, DocumentProcessingStatus.TERMINAL_ERROR, "PROCESSING_FAILURE", null, now).also {
                recorder.issue(runId, OperationPhase.PROCESSING, "PROCESSING_FAILURE", documentId = sourceDocumentId)
            }
        }
    }

    private fun retryDelay(failure: ProviderException, attemptCount: Int): Duration {
        if (failure is ProviderException.RateLimited && failure.retryAfterSeconds != null) return Duration.ofSeconds(failure.retryAfterSeconds)
        return properties.retryDelay.multipliedBy(1L shl (attemptCount - 1).coerceAtMost(10))
    }

    private fun ProviderException.isRetryable(): Boolean =
        this is ProviderException.RateLimited || this is ProviderException.TemporaryUnavailable
}
