package com.catalystradar.api.dto

import com.catalystradar.application.operations.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class OperationsPageResponse<T>(
    val generatedAt: Instant,
    val window: ActivityWindowResponse?,
    val items: List<T>,
    val limit: Int,
    val nextCursor: String?,
)

data class ActivityWindowResponse(val from: Instant, val to: Instant)
fun ActivityWindow.toResponse() = ActivityWindowResponse(from = from, to = to)
fun <T, R> OperationsPage<T>.toResponse(mapItem: (T) -> R) = OperationsPageResponse(
    generatedAt = generatedAt,
    window = window?.toResponse(),
    items = items.map(mapItem),
    limit = limit,
    nextCursor = nextCursor,
)

data class ScheduleConfigResponse(val enabled: Boolean, val intervalSeconds: Long)
data class ProviderConfigResponse(val name: String, val configured: Boolean)
data class ArtifactVersionsResponse(
    val score: String,
    val taxonomy: String,
    val prompt: String,
    val extractor: String,
    val extractionModel: String,
    val embeddingModel: String,
)
data class OperationsConfigResponse(
    val generatedAt: Instant,
    val publicApiAuthEnabled: Boolean,
    val singleInstance: Boolean,
    val ingestion: ScheduleConfigResponse,
    val snapshots: ScheduleConfigResponse,
    val primaryNewsProvider: String,
    val fallbackNewsProvider: String,
    val pipelineBatchSize: Int,
    val pipelineMaxAttempts: Int,
    val pipelineRetryDelaySeconds: Long,
    val providers: List<ProviderConfigResponse>,
    val versions: ArtifactVersionsResponse,
)
fun OperationsConfig.toResponse() = OperationsConfigResponse(
    generatedAt = generatedAt,
    publicApiAuthEnabled = publicApiAuthEnabled,
    singleInstance = singleInstance,
    ingestion = ScheduleConfigResponse(enabled = ingestion.enabled, intervalSeconds = ingestion.intervalSeconds),
    snapshots = ScheduleConfigResponse(enabled = snapshots.enabled, intervalSeconds = snapshots.intervalSeconds),
    primaryNewsProvider = primaryNewsProvider,
    fallbackNewsProvider = fallbackNewsProvider,
    pipelineBatchSize = pipelineBatchSize,
    pipelineMaxAttempts = pipelineMaxAttempts,
    pipelineRetryDelaySeconds = pipelineRetryDelaySeconds,
    providers = providers.map { ProviderConfigResponse(name = it.name, configured = it.configured) },
    versions = ArtifactVersionsResponse(
        score = versions.score,
        taxonomy = versions.taxonomy,
        prompt = versions.prompt,
        extractor = versions.extractor,
        extractionModel = versions.extractionModel,
        embeddingModel = versions.embeddingModel,
    ),
)

data class FreshnessResponse(
    val status: String,
    val lastSuccessAt: Instant?,
    val ageSeconds: Long?,
    val expectedIntervalSeconds: Long,
    val staleAfterSeconds: Long,
)
data class QueueSummaryResponse(
    val pending: Long,
    val processing: Long,
    val retrying: Long,
    val terminal: Long,
    val unresolved: Long,
    val notTracked: Long,
    val waiting: Long,
    val due: Long,
    val oldestDueAt: Instant?,
    val oldestDueDocumentId: UUID?,
)
data class ActivitySummaryResponse(
    val documentsNew: Long,
    val documentsFetched: Long,
    val documentsDuplicate: Long,
    val eventsInserted: Long,
    val clustersCreated: Long,
    val successfulRecalculations: Long?,
    val recalculationFailures: Long?,
    val recalculationHistoryAvailable: Boolean,
)
data class ModelUsageSummaryResponse(
    val calls: Long,
    val successfulCalls: Long,
    val failedCalls: Long,
    val inputTokens: Long?,
    val inputTokensKnownCalls: Long,
    val outputTokens: Long?,
    val outputTokensExpectedCalls: Long,
    val outputTokensKnownCalls: Long,
    val estimatedCostUsd: BigDecimal?,
    val costKnownCalls: Long,
    val latencyKnownCalls: Long,
    val p50LatencyMs: Double?,
    val p95LatencyMs: Double?,
)
data class DependencyObservationResponse(
    val provider: String,
    val configured: Boolean,
    val status: String,
    val observedSince: Instant,
    val lastObservedAt: Instant?,
    val lastSuccessAt: Instant?,
    val failedObservations: Long,
    val lastErrorCode: String?,
)
data class OperationalSignalResponse(
    val code: String,
    val severity: String,
    val count: Long?,
    val documentId: UUID?,
    val runId: UUID?,
    val provider: String?,
)
data class OperationsOverviewResponse(
    val generatedAt: Instant,
    val window: ActivityWindowResponse,
    val activeCompanies: Long,
    val ingestionFreshness: FreshnessResponse,
    val snapshotFreshness: FreshnessResponse,
    val queue: QueueSummaryResponse,
    val activity: ActivitySummaryResponse,
    val models: ModelUsageSummaryResponse,
    val dependencies: List<DependencyObservationResponse>,
    val signals: List<OperationalSignalResponse>,
    val activeRuns: List<OperationRunResponse>,
    val historyStartedAt: Instant?,
)
fun Freshness.toResponse() = FreshnessResponse(
    status = status.name,
    lastSuccessAt = lastSuccessAt,
    ageSeconds = ageSeconds,
    expectedIntervalSeconds = expectedIntervalSeconds,
    staleAfterSeconds = staleAfterSeconds,
)
fun QueueSummary.toResponse() = QueueSummaryResponse(
    pending = pending,
    processing = processing,
    retrying = retrying,
    terminal = terminal,
    unresolved = unresolved,
    notTracked = notTracked,
    waiting = waiting,
    due = due,
    oldestDueAt = oldestDueAt,
    oldestDueDocumentId = oldestDueDocumentId,
)
fun ActivitySummary.toResponse() = ActivitySummaryResponse(
    documentsNew = documentsNew,
    documentsFetched = documentsFetched,
    documentsDuplicate = documentsDuplicate,
    eventsInserted = eventsInserted,
    clustersCreated = clustersCreated,
    successfulRecalculations = successfulRecalculations,
    recalculationFailures = recalculationFailures,
    recalculationHistoryAvailable = recalculationHistoryAvailable,
)
fun ModelUsageSummary.toResponse() = ModelUsageSummaryResponse(
    calls = calls,
    successfulCalls = successfulCalls,
    failedCalls = failedCalls,
    inputTokens = inputTokens,
    inputTokensKnownCalls = inputTokensKnownCalls,
    outputTokens = outputTokens,
    outputTokensExpectedCalls = outputTokensExpectedCalls,
    outputTokensKnownCalls = outputTokensKnownCalls,
    estimatedCostUsd = estimatedCostUsd,
    costKnownCalls = costKnownCalls,
    latencyKnownCalls = latencyKnownCalls,
    p50LatencyMs = p50LatencyMs,
    p95LatencyMs = p95LatencyMs,
)
fun DependencyObservation.toResponse() = DependencyObservationResponse(
    provider = provider,
    configured = configured,
    status = status.name,
    observedSince = observedSince,
    lastObservedAt = lastObservedAt,
    lastSuccessAt = lastSuccessAt,
    failedObservations = failedObservations,
    lastErrorCode = lastErrorCode,
)
fun OperationalSignal.toResponse() = OperationalSignalResponse(
    code = code,
    severity = severity.name,
    count = count,
    documentId = documentId,
    runId = runId,
    provider = provider,
)
fun OperationsOverview.toResponse() = OperationsOverviewResponse(
    generatedAt = generatedAt,
    window = window.toResponse(),
    activeCompanies = activeCompanies,
    ingestionFreshness = ingestionFreshness.toResponse(),
    snapshotFreshness = snapshotFreshness.toResponse(),
    queue = queue.toResponse(),
    activity = activity.toResponse(),
    models = models.toResponse(),
    dependencies = dependencies.map { it.toResponse() },
    signals = signals.map { it.toResponse() },
    activeRuns = activeRuns.map { it.toResponse() },
    historyStartedAt = historyStartedAt,
)

data class DocumentListItemResponse(
    val id: UUID,
    val provider: String,
    val title: String,
    val publishedAt: Instant?,
    val discoveredAt: Instant,
    val createdAt: Instant,
    val state: String,
    val attemptCount: Int?,
    val nextAttemptAt: Instant?,
    val updatedAt: Instant?,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
    val tickers: List<String>,
    val tickersTruncated: Boolean,
    val eventReports: Long,
    val canonicalClusters: Long,
    val firstIngestionRunId: UUID?,
)
data class LinkedCompanyResponse(
    val id: UUID,
    val ticker: String,
    val name: String,
    val latestSnapshotAsOf: Instant?,
    val latestSnapshotCreatedAt: Instant?,
)
data class DocumentDetailResponse(
    val generatedAt: Instant,
    val document: DocumentListItemResponse,
    val canonicalUrl: String?,
    val providerDocumentId: String?,
    val completedAt: Instant?,
    val maxAttempts: Int,
    val capturedAttemptCount: Long,
    val unrecordedAttemptCount: Long,
    val historyAvailable: Boolean,
    val companies: List<LinkedCompanyResponse>,
    val companiesTotal: Long,
    val companiesTruncated: Boolean,
    val modelCallsRecorded: Long,
    val eventReports: Long,
    val canonicalClusters: Long,
)
data class DocumentBodyResponse(val id: UUID, val text: String, val originalCharacters: Int, val truncated: Boolean)
fun DocumentListItem.toResponse() = DocumentListItemResponse(
    id = id,
    provider = provider,
    title = title,
    publishedAt = publishedAt,
    discoveredAt = discoveredAt,
    createdAt = createdAt,
    state = state.name,
    attemptCount = attemptCount,
    nextAttemptAt = nextAttemptAt,
    updatedAt = updatedAt,
    lastErrorCode = lastErrorCode,
    lastErrorMessage = lastErrorMessage,
    tickers = tickers,
    tickersTruncated = tickersTruncated,
    eventReports = eventReports,
    canonicalClusters = canonicalClusters,
    firstIngestionRunId = firstIngestionRunId,
)
fun DocumentDetail.toResponse() = DocumentDetailResponse(
    generatedAt = generatedAt,
    document = document.toResponse(),
    canonicalUrl = canonicalUrl,
    providerDocumentId = providerDocumentId,
    completedAt = completedAt,
    maxAttempts = maxAttempts,
    capturedAttemptCount = capturedAttemptCount,
    unrecordedAttemptCount = unrecordedAttemptCount,
    historyAvailable = historyAvailable,
    companies = companies.map {
        LinkedCompanyResponse(
            id = it.id,
            ticker = it.ticker,
            name = it.name,
            latestSnapshotAsOf = it.latestSnapshotAsOf,
            latestSnapshotCreatedAt = it.latestSnapshotCreatedAt,
        )
    },
    companiesTotal = companiesTotal,
    companiesTruncated = companiesTruncated,
    modelCallsRecorded = modelCallsRecorded,
    eventReports = eventReports,
    canonicalClusters = canonicalClusters,
)
fun DocumentBody.toResponse() = DocumentBodyResponse(
    id = id,
    text = text,
    originalCharacters = originalCharacters,
    truncated = truncated,
)

data class ModelCallResponse(
    val id: UUID,
    val provider: String,
    val operation: String,
    val model: String,
    val promptVersion: String?,
    val extractorVersion: String?,
    val sourceDocumentId: UUID?,
    val attemptId: UUID?,
    val runId: UUID?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMs: Long?,
    val estimatedCost: BigDecimal?,
    val success: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: Instant,
)
data class ModelUsageGroupResponse(val provider: String, val operation: String, val model: String, val usage: ModelUsageSummaryResponse)
data class ModelSummaryResponse(
    val generatedAt: Instant,
    val window: ActivityWindowResponse,
    val totals: ModelUsageSummaryResponse,
    val groups: List<ModelUsageGroupResponse>,
    val groupsTruncated: Boolean,
)
fun ModelCall.toResponse() = ModelCallResponse(
    id = id,
    provider = provider,
    operation = operation,
    model = model,
    promptVersion = promptVersion,
    extractorVersion = extractorVersion,
    sourceDocumentId = sourceDocumentId,
    attemptId = attemptId,
    runId = runId,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    latencyMs = latencyMs,
    estimatedCost = estimatedCost,
    success = success,
    errorCode = errorCode,
    errorMessage = errorMessage,
    createdAt = createdAt,
)
fun ModelSummary.toResponse() = ModelSummaryResponse(
    generatedAt = generatedAt,
    window = window.toResponse(),
    totals = totals.toResponse(),
    groups = groups.map {
        ModelUsageGroupResponse(provider = it.provider, operation = it.operation, model = it.model, usage = it.usage.toResponse())
    },
    groupsTruncated = groupsTruncated,
)

data class OperationRunResponse(
    val id: UUID,
    val kind: String,
    val trigger: String,
    val status: String,
    val active: Boolean,
    val phase: String,
    val asOf: Instant,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val updatedAt: Instant,
    val durationMs: Long?,
    val captureComplete: Boolean,
    val documentsConsidered: Int,
    val documentsCompleted: Int,
    val documentsSkipped: Int,
    val documentsRetryScheduled: Int,
    val documentsTerminalFailures: Int,
    val eventsInserted: Int,
    val eventsReused: Int,
    val companiesConsidered: Int,
    val companiesRescored: Int,
    val companiesFailed: Int,
    val errorCode: String?,
    val errorMessage: String?,
)
data class OperationPhaseTimingResponse(val phase: String, val startedAt: Instant?, val finishedAt: Instant?, val durationMs: Long?)
data class OperationRunDetailResponse(
    val generatedAt: Instant,
    val run: OperationRunResponse,
    val ingestionRuns: Long,
    val documentAttempts: Long,
    val issues: Long,
    val phases: List<OperationPhaseTimingResponse>,
)
data class ProcessingAttemptResponse(
    val id: UUID,
    val documentId: UUID,
    val runId: UUID,
    val number: Int,
    val status: String,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val updatedAt: Instant,
    val durationMs: Long?,
    val nextAttemptAt: Instant?,
    val eventsInserted: Int,
    val eventsReused: Int,
    val errorCode: String?,
    val errorMessage: String?,
    val modelCallIds: List<UUID>,
    val modelCallsTotal: Long,
    val modelCallsTruncated: Boolean,
)
data class OperationIssueResponse(
    val id: UUID,
    val runId: UUID,
    val phase: String,
    val documentId: UUID?,
    val companyId: UUID?,
    val ticker: String?,
    val errorCode: String,
    val errorMessage: String,
    val createdAt: Instant,
)
fun OperationRun.toResponse() = OperationRunResponse(
    id = id,
    kind = kind.name,
    trigger = trigger.name,
    status = status.name,
    active = active,
    phase = phase.name,
    asOf = asOf,
    startedAt = startedAt,
    finishedAt = finishedAt,
    updatedAt = updatedAt,
    durationMs = durationMs,
    captureComplete = captureComplete,
    documentsConsidered = documentsConsidered,
    documentsCompleted = documentsCompleted,
    documentsSkipped = documentsSkipped,
    documentsRetryScheduled = documentsRetryScheduled,
    documentsTerminalFailures = documentsTerminalFailures,
    eventsInserted = eventsInserted,
    eventsReused = eventsReused,
    companiesConsidered = companiesConsidered,
    companiesRescored = companiesRescored,
    companiesFailed = companiesFailed,
    errorCode = errorCode,
    errorMessage = errorMessage,
)
fun OperationRunDetail.toResponse() = OperationRunDetailResponse(
    generatedAt = generatedAt,
    run = run.toResponse(),
    ingestionRuns = ingestionRuns,
    documentAttempts = documentAttempts,
    issues = issues,
    phases = phases.map { OperationPhaseTimingResponse(phase = it.phase, startedAt = it.startedAt, finishedAt = it.finishedAt, durationMs = it.durationMs) },
)
fun ProcessingAttempt.toResponse() = ProcessingAttemptResponse(
    id = id,
    documentId = documentId,
    runId = runId,
    number = number,
    status = status.name,
    startedAt = startedAt,
    finishedAt = finishedAt,
    updatedAt = updatedAt,
    durationMs = durationMs,
    nextAttemptAt = nextAttemptAt,
    eventsInserted = eventsInserted,
    eventsReused = eventsReused,
    errorCode = errorCode,
    errorMessage = errorMessage,
    modelCallIds = modelCallIds,
    modelCallsTotal = modelCallsTotal,
    modelCallsTruncated = modelCallsTruncated,
)
fun OperationIssue.toResponse() = OperationIssueResponse(
    id = id,
    runId = runId,
    phase = phase.name,
    documentId = documentId,
    companyId = companyId,
    ticker = ticker,
    errorCode = errorCode,
    errorMessage = errorMessage,
    createdAt = createdAt,
)
