package com.catalystradar.application.pipeline

import com.catalystradar.application.clustering.EventClusteringService
import com.catalystradar.application.event.EventNormalizationService
import com.catalystradar.application.extraction.ExtractionResultCache
import com.catalystradar.application.ingestion.DocumentProcessingStatus
import com.catalystradar.application.operations.*
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.SourceDocumentCompanyStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.operations.DocumentStepStore
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.ports.ExtractionRequest
import com.catalystradar.ports.ProviderException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

/**
 * Runs one source document through extraction, validation, normalization,
 * clustering and persistence, recording each step. Remote calls happen
 * before the narrow persistence transaction. Failures become durable
 * document outcomes; only cancellation propagates.
 */
class DocumentProcessor(
    private val extraction: EventExtractionProvider,
    private val normalization: EventNormalizationService,
    private val clustering: EventClusteringService,
    private val persistence: DocumentOutcomePersistenceService,
    private val documents: SourceDocumentStore,
    private val documentCompanies: SourceDocumentCompanyStore,
    private val companies: CompanyStore,
    private val properties: PipelineProperties,
    private val recorder: OperationRunRecorder,
    private val attempts: ProcessingAttemptService,
    private val steps: DocumentStepStore,
    private val extractionCache: ExtractionResultCache? = null,
) {
    private val log = LoggerFactory.getLogger(DocumentProcessor::class.java)

    suspend fun process(sourceDocumentId: UUID, runId: UUID, now: Instant): DocumentOutcome {
        val attempt = attempts.begin(sourceDocumentId, runId, now)
        var activeStep: UUID? = null
        fun start(stage: DocumentStage, input: Int? = null): UUID =
            steps.start(runId, sourceDocumentId, attempt.id, stage, inputCount = input).also { activeStep = it }
        fun finish(output: Int? = null, status: StepStatus = StepStatus.SUCCEEDED) {
            steps.finish(requireNotNull(activeStep), status, outputCount = output)
            activeStep = null
        }
        fun recordFailure(status: StepStatus, code: String) {
            activeStep?.let { step ->
                runCatching { steps.finish(step, status, code = code) }
                    .onFailure { log.warn("pipeline step final recording failed for document {}", sourceDocumentId) }
            }
        }
        return try {
            val document = documents.findById(sourceDocumentId)
                ?: return attempts.fail(attempt, DocumentProcessingStatus.TERMINAL_ERROR, "PROCESSING_FAILURE", null, now).also {
                    recorder.issue(runId, OperationPhase.PROCESSING, "PROCESSING_FAILURE", documentId = sourceDocumentId)
                }
            val resolved = documentCompanies.findCompanyIds(sourceDocumentId).mapNotNull(companies::findById)
            if (resolved.isEmpty()) {
                start(DocumentStage.EVENT_EXTRACTION)
                finish(status = StepStatus.SKIPPED)
                val persistenceStep = start(DocumentStage.EVENT_PERSISTENCE, 0)
                return persistence.persist(DocumentPersistencePlan(sourceDocumentId, emptyList(), attempt.id, persistenceStep), DocumentProcessingStatus.SKIPPED, now)
            }
            start(DocumentStage.EVENT_EXTRACTION, 1)
            val extractionRequest = ExtractionRequest(document, resolved, attempt.id)
            val extractionResult = extractionCache?.extract(extraction, extractionRequest) ?: extraction.extract(extractionRequest)
            finish(extractionResult.events.size)
            start(DocumentStage.EVENT_VALIDATION, extractionResult.events.size)
            val validated = normalization.validateDocument(extractionResult)
            finish(validated.events.size)
            start(DocumentStage.EVENT_NORMALIZATION, validated.events.size)
            val prepared = normalization.prepareValidatedDocument(document, validated, resolved)
            finish(prepared.size, if (validated.documentRelevant) StepStatus.SUCCEEDED else StepStatus.SKIPPED)
            start(DocumentStage.EVENT_CLUSTERING, prepared.size)
            val plannedEvents = prepared.map { candidate ->
                PlannedEvent(
                    event = candidate.event,
                    fingerprint = candidate.fingerprint,
                    cluster = clustering.prepareClustering(sourceDocumentId, candidate.fingerprint, candidate.event, attempt.id),
                )
            }
            finish(plannedEvents.size, if (prepared.isEmpty()) StepStatus.SKIPPED else StepStatus.SUCCEEDED)
            val persistenceStep = start(DocumentStage.EVENT_PERSISTENCE, plannedEvents.size)
            val plan = DocumentPersistencePlan(sourceDocumentId, plannedEvents, attempt.id, persistenceStep)
            val finalStatus = if (extractionResult.documentRelevant) DocumentProcessingStatus.COMPLETED else DocumentProcessingStatus.SKIPPED
            persistence.persist(plan, finalStatus, now)
        } catch (e: CancellationException) {
            recordFailure(StepStatus.INTERRUPTED, "CANCELLED")
            runCatching { attempts.interrupt(attempt, "CANCELLED") }
                .onFailure { log.warn("pipeline attempt cancellation recording failed for document {}", sourceDocumentId) }
            throw e
        } catch (e: ProviderException) {
            val retryAt = if (e.isRetryable() && attempt.number < properties.maxAttempts) now.plus(retryDelay(e, attempt.number)) else null
            val code = OperationalErrors.code(e)
            recordFailure(StepStatus.FAILED, code)
            attempts.fail(attempt, if (retryAt != null) DocumentProcessingStatus.RETRYABLE_ERROR else DocumentProcessingStatus.TERMINAL_ERROR, code, retryAt, now).also {
                recorder.issue(runId, OperationPhase.PROCESSING, code, documentId = sourceDocumentId, provider = e.provider)
            }
        } catch (e: RuntimeException) {
            recordFailure(StepStatus.FAILED, "PROCESSING_FAILURE")
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
