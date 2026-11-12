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
