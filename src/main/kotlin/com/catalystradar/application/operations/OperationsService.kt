package com.catalystradar.application.operations

import com.catalystradar.adapters.finnhub.FinnhubProperties
import com.catalystradar.adapters.openai.OpenAiProperties
import com.catalystradar.adapters.polygon.PolygonProperties
import com.catalystradar.application.catalyst.SnapshotProperties
import com.catalystradar.application.ingestion.IngestionProperties
import com.catalystradar.application.pipeline.PipelineProperties
import com.catalystradar.common.Versions
import com.catalystradar.persistence.event.EventSearch
import com.catalystradar.persistence.event.EventSearchPage
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.persistence.operations.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class OperationsService(
    private val summaryStore: OperationsSummaryStore,
    private val documentStore: DocumentInspectionStore,
    private val modelStore: ModelInspectionStore,
    private val runStore: OperationRunStore,
    private val attemptStore: ProcessingAttemptStore,
    private val eventStore: EventStore,
    properties: OperationsProperties,
    private val ingestion: IngestionProperties,
    private val pipeline: PipelineProperties,
    private val snapshots: SnapshotProperties,
    private val polygon: PolygonProperties,
    private val finnhub: FinnhubProperties,
    private val openai: OpenAiProperties,
    private val environment: Environment,
    @Qualifier("operationsClock") private val clock: Clock,
) {
    private val policy = OperationsStatusPolicy(properties)

    fun config(): OperationsConfig = OperationsConfig(
        generatedAt = clock.instant(), publicApiAuthEnabled = environment.getProperty("catalyst.api.auth-enabled", Boolean::class.java, false),
        ingestion = ScheduleConfig(ingestion.enabled, ingestion.interval.seconds), snapshots = ScheduleConfig(snapshots.enabled, snapshots.interval.seconds),
        primaryNewsProvider = ingestion.provider, fallbackNewsProvider = ingestion.fallbackProvider, pipelineBatchSize = pipeline.batchSize,
        pipelineMaxAttempts = pipeline.maxAttempts, pipelineRetryDelaySeconds = pipeline.retryDelay.seconds, providers = providerConfig(),
        versions = ArtifactVersions(Versions.SCORE_V1, Versions.TAXONOMY_V1, Versions.PROMPT_V1, Versions.EXTRACTOR_V1, openai.extractionModel, openai.embeddingModel),
    )

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun overview(window: ActivityWindow): OperationsOverview {
        val generatedAt = clock.instant()
        val active = activeIds()
        val inputs = summaryStore.read(window, generatedAt, active)
        val ingestionFreshness = policy.freshness(ingestion.enabled, ingestion.interval, inputs.lastIngestionSuccessAt, generatedAt)
        val snapshotFreshness = policy.freshness(snapshots.enabled, snapshots.interval, inputs.lastSnapshotSuccessAt, generatedAt)
        val configured = providerConfig().associate { it.name to it.configured }
        val dependencies = inputs.dependencyObservations.map { policy.dependency(configured.getValue(it.provider), it, generatedAt) }
        return OperationsOverview(generatedAt, window, inputs.activeCompanies, ingestionFreshness, snapshotFreshness, inputs.queue,
            inputs.activity, inputs.models, dependencies, policy.signals(inputs, ingestionFreshness, snapshotFreshness, dependencies, generatedAt),
            inputs.activeRuns.map { it.copy(active = it.id in active) }, inputs.historyStartedAt)
    }

    fun documents(query: DocumentQuery): OperationsPage<DocumentListItem> = documentStore.search(query, clock.instant(), activeIds())

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun document(id: UUID): DocumentDetail = documentStore.detail(id, clock.instant(), pipeline.maxAttempts)
        ?: throw OperationsResourceNotFoundException(OperationsResource.DOCUMENT, id)

    fun documentBody(id: UUID): DocumentBody = documentStore.body(id)
        ?: throw OperationsResourceNotFoundException(OperationsResource.DOCUMENT, id)

    fun documentEvents(id: UUID, page: PageRequest): EventSearchPage {
        requireDocument(id)
        return eventStore.searchEvents(EventSearch(sourceDocumentId = id, limit = page.limit, cursor = page.cursor))
    }

    fun documentAttempts(id: UUID, page: PageRequest): OperationsPage<ProcessingAttempt> {
        val generatedAt = clock.instant()
        requireDocument(id, generatedAt)
        return attemptStore.search(id, page, generatedAt)
    }

    fun documentModelRuns(id: UUID, page: PageRequest): OperationsPage<ModelCall> {
        val generatedAt = clock.instant()
        requireDocument(id, generatedAt)
        return modelStore.search(ModelQuery(null, documentId = id, page = page), generatedAt)
    }

    fun runs(query: RunQuery): OperationsPage<OperationRun> {
        val page = runStore.search(query, clock.instant())
        val active = activeIds()
        return page.copy(items = page.items.map { it.copy(active = it.id in active) })
    }

    fun run(id: UUID): OperationRunDetail {
        val detail = requireRun(id, clock.instant())
        return detail.copy(run = detail.run.copy(active = id in activeIds()))
    }

    fun runIssues(id: UUID, page: PageRequest): OperationsPage<OperationIssue> {
        val generatedAt = clock.instant()
        requireRun(id, generatedAt)
        return runStore.issues(id, page, generatedAt)
    }

    fun ingestionRuns(query: IngestionQuery): OperationsPage<IngestionRunInspection> {
        val generatedAt = clock.instant()
        query.runId?.let { requireRun(it, generatedAt) }
        return runStore.ingestion(query, generatedAt)
    }

    fun modelRuns(query: ModelQuery): OperationsPage<ModelCall> {
        val generatedAt = clock.instant()
        query.documentId?.let { requireDocument(it, generatedAt) }
        query.runId?.let { requireRun(it, generatedAt) }
        return modelStore.search(query, generatedAt)
    }

    fun modelRun(id: UUID): ModelCall = modelStore.findById(id)
        ?: throw OperationsResourceNotFoundException(OperationsResource.MODEL_RUN, id)

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun modelSummary(query: ModelQuery): ModelSummary {
        val generatedAt = clock.instant()
        query.documentId?.let { requireDocument(it, generatedAt) }
        query.runId?.let { requireRun(it, generatedAt) }
        return modelStore.summary(query, generatedAt)
    }

    private fun requireDocument(id: UUID, generatedAt: Instant = clock.instant()) {
        if (documentStore.detail(id, generatedAt, pipeline.maxAttempts) == null) throw OperationsResourceNotFoundException(OperationsResource.DOCUMENT, id)
    }

    private fun requireRun(id: UUID, generatedAt: Instant): OperationRunDetail = runStore.detail(id, generatedAt)
        ?: throw OperationsResourceNotFoundException(OperationsResource.OPERATION_RUN, id)

    private fun providerConfig() = listOf(ProviderConfig("polygon", polygon.apiKey.isNotBlank()),
        ProviderConfig("finnhub", finnhub.apiKey.isNotBlank()), ProviderConfig("openai", openai.apiKey.isNotBlank()))

    private fun activeIds(): Set<UUID> = emptySet()
}
