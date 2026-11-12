package com.catalystradar.api.dto

import com.catalystradar.application.operations.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

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
    val traceVersion: String?,
    val runAttemptStatus: String?,
    val runAttemptNumber: Int?,
    val runLastStage: String?,
    val runLastStepStatus: String?,
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
    traceVersion = traceVersion,
    runAttemptStatus = runAttemptStatus,
    runAttemptNumber = runAttemptNumber,
    runLastStage = runLastStage,
    runLastStepStatus = runLastStepStatus,
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
