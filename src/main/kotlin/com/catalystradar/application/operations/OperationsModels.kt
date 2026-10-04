package com.catalystradar.application.operations

import com.catalystradar.application.ingestion.IngestionStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class DocumentState { PENDING, PROCESSING, COMPLETED, SKIPPED, RETRYABLE_ERROR, TERMINAL_ERROR, UNRESOLVED, NOT_TRACKED }
enum class OperationKind { PIPELINE, DAILY_SNAPSHOTS }
enum class OperationTrigger { MANUAL, SCHEDULED }
enum class OperationStatus { RUNNING, SUCCESS, PARTIAL, FAILED, CANCELLED, INTERRUPTED }
enum class OperationPhase { INGESTION, PROCESSING, SCORING, FINISHED }
enum class IssuePhase { INGESTION, PROCESSING, SCORING }
enum class AttemptStatus { RUNNING, COMPLETED, SKIPPED, RETRYABLE_ERROR, TERMINAL_ERROR, INTERRUPTED }
enum class OperationsResource { DOCUMENT, OPERATION_RUN, MODEL_RUN }
enum class FreshnessStatus { OFF, NEVER, CURRENT, OVERDUE }
enum class DependencyStatus { NOT_CONFIGURED, UNOBSERVED, OK, DEGRADED }
enum class SignalSeverity { ERROR, WARNING, INFO }

data class OperationsPage<T>(val generatedAt: Instant, val window: ActivityWindow?, val items: List<T>, val limit: Int, val nextCursor: String?)
data class ScheduleConfig(val enabled: Boolean, val intervalSeconds: Long)
data class ProviderConfig(val name: String, val configured: Boolean)
data class ArtifactVersions(val score: String, val taxonomy: String, val prompt: String, val extractor: String, val extractionModel: String, val embeddingModel: String)
data class OperationsConfig(
    val generatedAt: Instant, val publicApiAuthEnabled: Boolean, val singleInstance: Boolean = true,
    val ingestion: ScheduleConfig, val snapshots: ScheduleConfig, val primaryNewsProvider: String,
    val fallbackNewsProvider: String, val pipelineBatchSize: Int, val pipelineMaxAttempts: Int,
    val pipelineRetryDelaySeconds: Long, val providers: List<ProviderConfig>, val versions: ArtifactVersions,
)
data class Freshness(val status: FreshnessStatus, val lastSuccessAt: Instant?, val ageSeconds: Long?, val expectedIntervalSeconds: Long, val staleAfterSeconds: Long)
data class QueueSummary(
    val pending: Long, val processing: Long, val retrying: Long, val terminal: Long,
    val unresolved: Long, val notTracked: Long, val waiting: Long, val due: Long,
    val oldestDueAt: Instant?, val oldestDueDocumentId: UUID?,
)
data class ActivitySummary(
    val documentsNew: Long, val documentsFetched: Long, val documentsDuplicate: Long,
    val eventsInserted: Long, val clustersCreated: Long, val successfulRecalculations: Long?,
    val recalculationFailures: Long?, val recalculationHistoryAvailable: Boolean,
)
data class ModelUsageSummary(
    val calls: Long, val successfulCalls: Long, val failedCalls: Long, val inputTokens: Long?,
    val inputTokensKnownCalls: Long, val outputTokens: Long?, val outputTokensExpectedCalls: Long,
    val outputTokensKnownCalls: Long, val estimatedCostUsd: BigDecimal?, val costKnownCalls: Long,
    val latencyKnownCalls: Long, val p50LatencyMs: Double?, val p95LatencyMs: Double?,
)
data class DependencyObservation(val provider: String, val configured: Boolean, val status: DependencyStatus, val observedSince: Instant, val lastObservedAt: Instant?, val lastSuccessAt: Instant?, val failedObservations: Long, val lastErrorCode: String?)
data class OperationalSignal(val code: String, val severity: SignalSeverity, val count: Long? = null, val documentId: UUID? = null, val runId: UUID? = null, val provider: String? = null)
data class ActivityWindow(val from: Instant, val to: Instant) {
    init {
        require(from < to) { "from must precede to" }
        require(java.time.Duration.between(from, to) <= java.time.Duration.ofDays(7)) { "window cannot exceed seven days" }
    }
}
data class OperationsOverview(
    val generatedAt: Instant, val window: ActivityWindow, val activeCompanies: Long,
    val ingestionFreshness: Freshness, val snapshotFreshness: Freshness, val queue: QueueSummary,
    val activity: ActivitySummary, val models: ModelUsageSummary, val dependencies: List<DependencyObservation>,
    val signals: List<OperationalSignal>, val activeRuns: List<OperationRun>, val historyStartedAt: Instant?,
)
data class DocumentListItem(
    val id: UUID, val provider: String, val title: String, val publishedAt: Instant?, val discoveredAt: Instant,
    val createdAt: Instant, val state: DocumentState, val attemptCount: Int?, val nextAttemptAt: Instant?,
    val updatedAt: Instant?, val lastErrorCode: String?, val lastErrorMessage: String?, val tickers: List<String>,
    val tickersTruncated: Boolean, val eventReports: Long, val canonicalClusters: Long, val firstIngestionRunId: UUID?,
)
data class DocumentDetail(
    val generatedAt: Instant, val document: DocumentListItem, val canonicalUrl: String?, val providerDocumentId: String?,
    val completedAt: Instant?, val maxAttempts: Int, val capturedAttemptCount: Long, val unrecordedAttemptCount: Long,
    val historyAvailable: Boolean, val companies: List<LinkedCompany>, val companiesTotal: Long,
    val companiesTruncated: Boolean, val modelCallsRecorded: Long, val eventReports: Long, val canonicalClusters: Long,
)
data class LinkedCompany(val id: UUID, val ticker: String, val name: String, val latestSnapshotAsOf: Instant?, val latestSnapshotCreatedAt: Instant?)
data class DocumentBody(val id: UUID, val text: String, val originalCharacters: Int, val truncated: Boolean)
data class ModelUsageGroup(val provider: String, val operation: String, val model: String, val usage: ModelUsageSummary)
data class ModelSummary(val generatedAt: Instant, val window: ActivityWindow, val totals: ModelUsageSummary, val groups: List<ModelUsageGroup>, val groupsTruncated: Boolean)
data class ModelCall(
    val id: UUID, val provider: String, val operation: String, val model: String, val promptVersion: String?,
    val extractorVersion: String?, val sourceDocumentId: UUID?, val attemptId: UUID?, val runId: UUID?,
    val inputTokens: Int?, val outputTokens: Int?, val latencyMs: Long?, val estimatedCost: BigDecimal?,
    val success: Boolean, val errorCode: String?, val errorMessage: String?, val createdAt: Instant,
)
data class OperationCounts(
    val documentsConsidered: Int = 0, val documentsCompleted: Int = 0, val documentsSkipped: Int = 0,
    val documentsRetryScheduled: Int = 0, val documentsTerminalFailures: Int = 0, val eventsInserted: Int = 0,
    val eventsReused: Int = 0, val companiesConsidered: Int = 0, val companiesRescored: Int = 0, val companiesFailed: Int = 0,
)
data class OperationRun(
    val id: UUID, val kind: OperationKind, val trigger: OperationTrigger, val status: OperationStatus,
    val active: Boolean, val phase: OperationPhase, val asOf: Instant, val startedAt: Instant,
    val finishedAt: Instant?, val updatedAt: Instant, val durationMs: Long?, val captureComplete: Boolean,
    val documentsConsidered: Int, val documentsCompleted: Int, val documentsSkipped: Int,
    val documentsRetryScheduled: Int, val documentsTerminalFailures: Int, val eventsInserted: Int,
    val eventsReused: Int, val companiesConsidered: Int, val companiesRescored: Int, val companiesFailed: Int,
    val errorCode: String?, val errorMessage: String?,
)
data class OperationRunDetail(val generatedAt: Instant, val run: OperationRun, val ingestionRuns: Long, val documentAttempts: Long, val issues: Long, val phases: List<OperationPhaseTiming>)
data class OperationPhaseTiming(val phase: String, val startedAt: Instant?, val finishedAt: Instant?, val durationMs: Long?)
data class ProcessingAttempt(
    val id: UUID, val documentId: UUID, val runId: UUID, val number: Int, val status: AttemptStatus,
    val startedAt: Instant, val finishedAt: Instant?, val updatedAt: Instant, val durationMs: Long?,
    val nextAttemptAt: Instant?, val eventsInserted: Int, val eventsReused: Int, val errorCode: String?,
    val errorMessage: String?, val modelCallIds: List<UUID>, val modelCallsTotal: Long, val modelCallsTruncated: Boolean,
)
data class OperationIssue(
    val id: UUID,
    val runId: UUID,
    val phase: IssuePhase,
    val documentId: UUID?,
    val companyId: UUID?,
    val ticker: String?,
    val provider: String?,
    val errorCode: String,
    val errorMessage: String,
    val createdAt: Instant,
)
data class StartedAttempt(val id: UUID, val documentId: UUID, val runId: UUID, val number: Int)
class OperationsResourceNotFoundException(val resource: OperationsResource, val id: UUID) : RuntimeException()
data class IngestionRunInspection(val id: UUID, val provider: String, val status: IngestionStatus, val fetched: Int, val added: Int, val duplicates: Int, val error: String?, val finishedAt: Instant?, val startedAt: Instant, val durationMs: Long?, val runId: UUID?, val errorCode: String?)
data class ProviderObservation(val provider: String, val lastObservedAt: Instant?, val lastSuccessAt: Instant?, val latestSucceeded: Boolean?, val failedObservations: Long, val lastErrorCode: String?)
data class ActiveAttemptObservation(val documentId: UUID, val runId: UUID, val startedAt: Instant)
data class OverviewInputs(
    val activeCompanies: Long, val queue: QueueSummary, val activity: ActivitySummary, val models: ModelUsageSummary,
    val lastIngestionSuccessAt: Instant?, val lastSnapshotSuccessAt: Instant?,
    val dependencyObservations: List<ProviderObservation>, val oldestActiveAttempt: ActiveAttemptObservation?,
    val activeRuns: List<OperationRun>, val historyStartedAt: Instant?,
)
