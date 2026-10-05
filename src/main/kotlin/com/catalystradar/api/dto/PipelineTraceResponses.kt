package com.catalystradar.api.dto

import com.catalystradar.application.operations.*
import java.time.Instant
import java.util.UUID

data class DocumentStepResponse(
    val id: UUID, val operationRunId: UUID, val sourceDocumentId: UUID, val processingAttemptId: UUID?,
    val attemptNumber: Int?, val stage: String, val sequence: Int, val status: String,
    val startedAt: Instant, val finishedAt: Instant?, val updatedAt: Instant, val durationMs: Long?,
    val inputCount: Int?, val outputCount: Int?, val eventsInserted: Int?, val eventsReused: Int?,
    val errorCode: String?, val errorMessage: String?,
)
data class CompanyValuationResponse(
    val id: UUID, val operationRunId: UUID, val companyId: UUID, val ticker: String, val companyName: String,
    val snapshotId: UUID, val previousSnapshotId: UUID?, val asOf: Instant, val createdAt: Instant,
    val scoreVersion: String, val taxonomyVersion: String, val beforeScore: Double?, val beforeState: String?,
    val afterScore: Double, val afterState: String, val velocity1d: Double, val velocity3d: Double, val velocity7d: Double,
    val transitionId: UUID?, val contributionSum: Double, val familyCount: Int, val convergenceMultiplier: Double,
    val rawScore: Double, val normalizationScale: Double, val contributionCutoff: Double, val contributionCount: Long,
)
data class ContributionSourceResponse(
    val sourceDocumentId: UUID, val eventId: UUID, val title: String, val provider: String,
    val canonicalUrl: String?, val publishedAt: Instant?, val discoveredAt: Instant,
    val evidence: List<String>, val evidenceTruncated: Boolean,
)
data class ValuationContributionResponse(
    val id: UUID, val valuationId: UUID, val eventId: UUID, val clusterId: UUID?, val createdAt: Instant,
    val ticker: String, val eventType: String, val family: String, val direction: String,
    val eventTimestamp: Instant?, val discoveredAt: Instant,
    val value: Double, val sign: Double, val baseWeight: Double, val confidence: Double,
    val materialityFactor: Double, val surpriseFactor: Double, val sourceQualityFactor: Double,
    val directnessFactor: Double, val timeDecayFactor: Double,
    val supportingSources: List<ContributionSourceResponse>, val supportingDocumentsTotal: Long,
    val supportingDocumentsTruncated: Boolean,
)

fun DocumentStep.toResponse() = DocumentStepResponse(id, operationRunId, sourceDocumentId, processingAttemptId, attemptNumber,
    stage.name, sequence, status.name, startedAt, finishedAt, updatedAt, durationMs, inputCount, outputCount, eventsInserted, eventsReused, errorCode, errorMessage)
fun CompanyValuation.toResponse() = CompanyValuationResponse(id, operationRunId, companyId, ticker, companyName,
    snapshotId, previousSnapshotId, asOf, createdAt, scoreVersion, taxonomyVersion, beforeScore, beforeState,
    afterScore, afterState, velocity1d, velocity3d, velocity7d, transitionId, contributionSum, familyCount,
    convergenceMultiplier, rawScore, normalizationScale, contributionCutoff, contributionCount)
fun ContributionSource.toResponse() = ContributionSourceResponse(sourceDocumentId, eventId, title, provider, canonicalUrl,
    publishedAt, discoveredAt, evidence, evidenceTruncated)
fun ValuationContribution.toResponse() = ValuationContributionResponse(id, valuationId, eventId, clusterId, createdAt, ticker,
    eventType, family, direction, eventTimestamp, discoveredAt, value, sign, baseWeight, confidence, materialityFactor,
    surpriseFactor, sourceQualityFactor, directnessFactor, timeDecayFactor, supportingSources.map { it.toResponse() },
    supportingDocumentsTotal, supportingDocumentsTruncated)
