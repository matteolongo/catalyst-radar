package com.catalystradar.application.pipeline

import com.catalystradar.application.catalyst.CatalystService
import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.extraction.ExtractionResultCache
import com.catalystradar.application.universe.AutoPromotionService
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
import com.catalystradar.persistence.operations.DocumentStepStore
import com.catalystradar.ports.EventExtractionProvider
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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

/** One operator-chosen document in [ExtractionStepResult]; [refused] names why it was not extracted. */
data class DocumentExtraction(
    val sourceDocumentId: UUID,
    val status: DocumentProcessingStatus?,
    val eventsInserted: Int = 0,
    val eventsReused: Int = 0,
    val affectedCompanies: Set<UUID> = emptySet(),
    val refused: String? = null,
)

data class ExtractionStepResult(val runId: UUID, val status: String, val documents: List<DocumentExtraction>)

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
    private val steps: DocumentStepStore,
    extractionCache: ExtractionResultCache? = null,
    private val autoPromotion: AutoPromotionService? = null,
) {
    private val processor = DocumentProcessor(
        extraction, normalization, clustering, persistence, documents, documentCompanies, companies,
        properties, recorder, attempts, steps, extractionCache,
    )
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val log = LoggerFactory.getLogger(PipelineService::class.java)
    private val running = AtomicBoolean(false)

    suspend fun runCycle(now: Instant = Instant.now(), trigger: OperationTrigger = OperationTrigger.MANUAL): PipelineResult {
        if (!running.compareAndSet(false, true)) return skipped()
        return try {
            execute(now, trigger, startedRunId = null) { runId -> ingestion.ingestCycle(now, runId) }
        } finally {
            running.set(false)
        }
    }

    /**
     * Manual per-symbol run: fetches news naming one tracked company from
     * [from], then drains the queue and rescores exactly as a scheduled
     * cycle does. Shares the cycle's guard, so it never overlaps one.
     */
    suspend fun runTickerCycle(ticker: String, from: Instant, now: Instant = Instant.now()): PipelineResult {
        val company = trackedCompany(ticker)
        if (!running.compareAndSet(false, true)) return skipped()
        return try {
            execute(now, OperationTrigger.MANUAL, startedRunId = null) { runId -> ingestion.ingestTicker(company.ticker, from, now, runId) }
        } finally {
            running.set(false)
        }
    }

    /** Background form of [runTickerCycle]; see [start]. */
    fun startTickerCycle(ticker: String, from: Instant, now: Instant = Instant.now()): PipelineResult {
        val company = trackedCompany(ticker)
        return launch(now, OperationTrigger.MANUAL) { runId -> ingestion.ingestTicker(company.ticker, from, now, runId) }
    }

    /**
     * Articles about an untracked symbol are stored but never extracted, so
     * fetching them would cost provider quota for nothing: promote it first.
     */
    private fun trackedCompany(ticker: String) =
        companies.findByTicker(ticker)?.takeIf { it.active } ?: throw CompanyNotFoundException(ticker)

    /**
     * Opens the run record, then drains the queue in the background so a
     * caller is not held for a whole cycle. The result carries the run id;
     * progress is read from the run itself.
     */
    fun start(now: Instant = Instant.now(), trigger: OperationTrigger = OperationTrigger.MANUAL): PipelineResult =
        launch(now, trigger) { runId -> ingestion.ingestCycle(now, runId) }

    private fun launch(now: Instant, trigger: OperationTrigger, ingest: suspend (UUID) -> IngestionCycleResult): PipelineResult {
        if (!running.compareAndSet(false, true)) return skipped()
        val id = try {
            recorder.begin(OperationKind.PIPELINE, trigger, now)
        } catch (e: RuntimeException) {
            running.set(false)
            throw e
        }
        background.launch {
            try {
                execute(now, trigger, startedRunId = id, ingest)
            } finally {
                running.set(false)
            }
        }
        return PipelineResult("RUNNING", 0, 0, 0, runId = id)
    }

    /**
     * Runs one operator step under the cycle's guard, so it never overlaps a
     * cycle or another step; null when one is already running.
     */
    suspend fun <T> exclusively(block: suspend () -> T): T? {
        if (!running.compareAndSet(false, true)) return null
        return try {
            block()
        } finally {
            running.set(false)
        }
    }

    /**
     * Extracts only the queued documents an operator chose, recorded as one
     * manual run without a scoring phase: scoring is the next, separate step.
     * Documents never queued or already finished are reported, not touched.
     * Null when a cycle or another step is running.
     */
    suspend fun extractDocuments(sourceDocumentIds: List<UUID>, now: Instant = Instant.now()): ExtractionStepResult? = exclusively {
        val runId = recorder.begin(OperationKind.PIPELINE, OperationTrigger.MANUAL, now)
        var counts = OperationCounts()
        var firstErrorCode: String? = null
        try {
            recorder.phase(runId, OperationPhase.PROCESSING)
            val results = sourceDocumentIds.distinct().map { id ->
                val record = processing.findBySourceDocumentId(id)
                when {
                    record == null -> DocumentExtraction(id, null, refused = "NOT_QUEUED")
                    record.status !in EXTRACTABLE -> DocumentExtraction(id, record.status, refused = "ALREADY_PROCESSED")
                    else -> {
                        val outcome = processor.process(id, runId, now)
                        counts = counts.copy(
                            documentsConsidered = counts.documentsConsidered + 1,
                            documentsCompleted = counts.documentsCompleted + if (outcome.status == DocumentProcessingStatus.COMPLETED) 1 else 0,
                            documentsSkipped = counts.documentsSkipped + if (outcome.status == DocumentProcessingStatus.SKIPPED) 1 else 0,
                            documentsRetryScheduled = counts.documentsRetryScheduled + if (outcome.status == DocumentProcessingStatus.RETRYABLE_ERROR) 1 else 0,
                            documentsTerminalFailures = counts.documentsTerminalFailures + if (outcome.status == DocumentProcessingStatus.TERMINAL_ERROR) 1 else 0,
                            eventsInserted = counts.eventsInserted + outcome.eventsInserted,
                            eventsReused = counts.eventsReused + outcome.eventsReused,
                        )
                        recorder.progress(runId, counts)
                        metrics.documentProcessing(outcome.status.name.lowercase())
                        if (outcome.status == DocumentProcessingStatus.RETRYABLE_ERROR) metrics.documentRetryScheduled()
                        if (outcome.error != null && firstErrorCode == null) {
                            firstErrorCode = processing.findBySourceDocumentId(id)?.lastErrorCode ?: "PROCESSING_FAILURE"
                        }
                        DocumentExtraction(id, outcome.status, outcome.eventsInserted, outcome.eventsReused, outcome.affectedCompanies)
                    }
                }
            }
            val status = if (firstErrorCode != null || counts.documentsRetryScheduled > 0 || counts.documentsTerminalFailures > 0) "PARTIAL" else "SUCCESS"
            recorder.finish(runId, OperationStatus.valueOf(status), counts, firstErrorCode, captureComplete = true)
            ExtractionStepResult(runId, status, results)
        } catch (e: CancellationException) {
            finishIncomplete(runId, OperationPhase.PROCESSING, OperationStatus.CANCELLED, "CANCELLED")
            throw e
        } catch (e: RuntimeException) {
            finishIncomplete(runId, OperationPhase.PROCESSING, OperationStatus.FAILED, "PROCESSING_FAILURE")
            throw e
        }
    }

    @PreDestroy
    fun stopBackgroundRuns() {
        background.cancel()
    }

    private fun skipped(): PipelineResult {
        metrics.pipelineCycle("skipped")
        return PipelineResult("SKIPPED", 0, 0, 0, alreadyRunning = true)
    }

    private suspend fun execute(
        now: Instant,
        trigger: OperationTrigger,
        startedRunId: UUID?,
        ingest: suspend (UUID) -> IngestionCycleResult,
    ): PipelineResult {
        var runId: UUID? = startedRunId
        var phase = OperationPhase.INGESTION
        var counts = OperationCounts()
        // What the run row holds; an outer failure reports these, not unsaved progress.
        var recordedCounts = OperationCounts()
        fun recordProgress(id: UUID) {
            recorder.progress(id, counts)
            recordedCounts = counts
        }
        return try {
            val id = startedRunId ?: recorder.begin(OperationKind.PIPELINE, trigger, now)
            runId = id
            require(properties.batchSize in 1..500) { "pipeline batchSize must be within 1..500" }
            require(properties.concurrency in 1..16) { "pipeline concurrency must be within 1..16" }
            require(properties.maxAttempts >= 1) { "pipeline maxAttempts must be positive" }
            require(!properties.retryDelay.isNegative && !properties.retryDelay.isZero) { "pipeline retryDelay must be positive" }
            var firstErrorCode: String? = null
            val ingestionResult = try {
                ingest(id)
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
            // Promoted candidates' stored documents are queued here, so this cycle extracts them.
            val promoted = try {
                autoPromotion?.promoteEligible(now).orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                log.warn("pipeline auto-promotion failed for run {}", id)
                emptyList()
            }
            // The market scan only stored what named the symbol inside its own
            // window; fetch the newly tracked symbol's recent history too.
            val deepFetchLookback = autoPromotion?.deepFetchLookback
            if (deepFetchLookback != null && !deepFetchLookback.isZero) {
                for (promotion in promoted) {
                    val runs = try {
                        ingestion.ingestTicker(promotion.company.ticker, now.minus(deepFetchLookback), now, id).runs
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RuntimeException) {
                        log.warn("pipeline deep fetch failed for ticker {} run {}", promotion.company.ticker, id)
                        if (firstErrorCode == null) firstErrorCode = "INGESTION_FAILURE"
                        recorder.issue(id, phase, "INGESTION_FAILURE", companyId = promotion.company.id)
                        continue
                    }
                    runs.filter { it.status != IngestionStatus.SUCCESS }.forEach { providerRun ->
                        val code = providerRun.errorCode ?: "UNKNOWN_FAILURE"
                        if (firstErrorCode == null) firstErrorCode = code
                        recorder.issue(id, phase, code, provider = providerRun.provider, companyId = promotion.company.id)
                    }
                }
            }
            phase = OperationPhase.PROCESSING
            recorder.phase(id, phase)
            val affectedCompanies = linkedSetOf<UUID>()
            val countsLock = Mutex()
            suspend fun handle(sourceDocumentId: UUID) {
                val outcome = processor.process(sourceDocumentId, id, now)
                countsLock.withLock {
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
                    recordProgress(id)
                    metrics.documentProcessing(outcome.status.name.lowercase())
                    if (outcome.status == DocumentProcessingStatus.RETRYABLE_ERROR) metrics.documentRetryScheduled()
                    if (outcome.error != null && firstErrorCode == null) {
                        firstErrorCode = processing.findBySourceDocumentId(sourceDocumentId)?.lastErrorCode ?: "PROCESSING_FAILURE"
                    }
                }
            }
            val due = processing.findDue(now, properties.batchSize).map { it.sourceDocumentId }
            if (properties.concurrency == 1) {
                due.forEach { handle(it) }
            } else {
                val permits = Semaphore(properties.concurrency)
                coroutineScope {
                    due.map { documentId -> async(Dispatchers.IO) { permits.withPermit { handle(documentId) } } }
                        .forEach { it.await() }
                }
            }
            phase = OperationPhase.SCORING
            recorder.phase(id, phase)
            counts = counts.copy(companiesConsidered = affectedCompanies.size)
            recordProgress(id)
            for (companyId in affectedCompanies) {
                try {
                    catalyst.recalculate(companyId, now, id)
                    counts = counts.copy(companiesRescored = counts.companiesRescored + 1)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    if (firstErrorCode == null) firstErrorCode = "SCORING_FAILURE"
                    recorder.issue(id, phase, "SCORING_FAILURE", companyId = companyId)
                    counts = counts.copy(companiesFailed = counts.companiesFailed + 1)
                    log.warn("pipeline recalculation failed for company {} run {}", companyId, id)
                }
                recordProgress(id)
            }
            val providerIssue = ingestionResult.runs.any { it.status != IngestionStatus.SUCCESS }
            val noProviderSucceeded = ingestionResult.runs.isNotEmpty() && ingestionResult.runs.all {
                it.status == IngestionStatus.FAILED ||
                    (it.status == IngestionStatus.PARTIAL && it.added == 0 && it.errorCode != IngestionService.WINDOW_TRUNCATED)
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
            metrics.pipelineCycle("cancelled")
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
            // Report the progress the run row holds, so the response and the ledger agree.
            PipelineResult(
                status = "FAILED", documentsProcessed = recordedCounts.documentsConsidered,
                eventsExtracted = recordedCounts.eventsInserted + recordedCounts.eventsReused,
                companiesRescored = recordedCounts.companiesRescored, error = OperationalErrors.message(code),
                documentsSkipped = recordedCounts.documentsSkipped, documentsRetryScheduled = recordedCounts.documentsRetryScheduled,
                documentsTerminalFailures = recordedCounts.documentsTerminalFailures, documentsCompleted = recordedCounts.documentsCompleted,
                eventsInserted = recordedCounts.eventsInserted, eventsReused = recordedCounts.eventsReused, runId = runId,
            )
        } finally {
            running.set(false)
        }
    }

    private companion object {
        /** States [findDue][DocumentProcessingStore.findDue] would also pick; an operator may skip the retry delay. */
        val EXTRACTABLE = setOf(
            DocumentProcessingStatus.PENDING,
            DocumentProcessingStatus.PROCESSING,
            DocumentProcessingStatus.RETRYABLE_ERROR,
        )
    }

    private fun finishIncomplete(id: UUID, phase: OperationPhase, status: OperationStatus, code: String) {
        runCatching { recorder.issue(id, phase, code) }
            .onFailure { log.warn("pipeline issue recording failed for run {}", id) }
        runCatching { recorder.finish(id, status, null, code, captureComplete = false) }
            .onFailure { log.warn("pipeline final recording failed for run {}", id) }
    }
}
