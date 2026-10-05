package com.catalystradar.application.operations

import java.time.Instant
import java.util.UUID

const val PIPELINE_TRACE_VERSION = "pipeline-trace-v1"
enum class DocumentStage { SOURCE_NORMALIZATION, SOURCE_REGISTRATION, COMPANY_RESOLUTION, EVENT_EXTRACTION, EVENT_VALIDATION, EVENT_NORMALIZATION, EVENT_CLUSTERING, EVENT_PERSISTENCE }
enum class StepStatus { RUNNING, SUCCEEDED, SKIPPED, FAILED, INTERRUPTED }
data class DocumentStep(
    val id: UUID, val operationRunId: UUID, val sourceDocumentId: UUID, val processingAttemptId: UUID?,
    val attemptNumber: Int?, val stage: DocumentStage, val sequence: Int, val status: StepStatus,
    val startedAt: Instant, val finishedAt: Instant?, val updatedAt: Instant, val durationMs: Long?,
    val inputCount: Int?, val outputCount: Int?, val eventsInserted: Int?, val eventsReused: Int?,
    val errorCode: String?, val errorMessage: String?,
)
data class CompanyValuation(
    val id: UUID, val operationRunId: UUID, val companyId: UUID, val ticker: String, val companyName: String,
    val snapshotId: UUID, val previousSnapshotId: UUID?, val asOf: Instant, val createdAt: Instant,
    val scoreVersion: String, val taxonomyVersion: String, val beforeScore: Double?, val beforeState: String?,
    val afterScore: Double, val afterState: String, val velocity1d: Double, val velocity3d: Double, val velocity7d: Double,
    val transitionId: UUID?, val contributionSum: Double, val familyCount: Int, val convergenceMultiplier: Double,
    val rawScore: Double, val normalizationScale: Double, val contributionCutoff: Double, val contributionCount: Long,
)
data class ContributionSource(
    val sourceDocumentId: UUID, val eventId: UUID, val title: String, val provider: String,
    val canonicalUrl: String?, val publishedAt: Instant?, val discoveredAt: Instant,
    val evidence: List<String>, val evidenceTruncated: Boolean,
)
data class ValuationContribution(
    val id: UUID, val valuationId: UUID, val eventId: UUID, val clusterId: UUID?, val createdAt: Instant,
    val ticker: String, val eventType: String, val family: String, val direction: String,
    val eventTimestamp: Instant?, val discoveredAt: Instant,
    val value: Double, val sign: Double, val baseWeight: Double, val confidence: Double,
    val materialityFactor: Double, val surpriseFactor: Double, val sourceQualityFactor: Double,
    val directnessFactor: Double, val timeDecayFactor: Double,
    val supportingSources: List<ContributionSource>, val supportingDocumentsTotal: Long,
    val supportingDocumentsTruncated: Boolean,
)
