package com.catalystradar.api.dto

import com.catalystradar.persistence.extraction.ModelRunRecord
import com.catalystradar.persistence.ingestion.IngestionRunRecord
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class IngestionRunResponse(
    val id: UUID,
    val provider: String,
    val status: String,
    val fetched: Int,
    val added: Int,
    val duplicates: Int,
    val error: String?,
    val finishedAt: Instant?,
    val startedAt: Instant? = null,
    val durationMs: Long? = null,
    val runId: UUID? = null,
    val errorCode: String? = null,
)

data class IngestionRunsResponse(val runs: List<IngestionRunResponse>)

fun IngestionRunRecord.toResponse() = IngestionRunResponse(
    id = id,
    provider = provider,
    status = status.name,
    fetched = fetched,
    added = added,
    duplicates = duplicates,
    error = error,
    finishedAt = finishedAt,
    startedAt = startedAt,
    durationMs = durationMs,
    runId = runId,
    errorCode = errorCode,
)

fun com.catalystradar.application.operations.IngestionRunInspection.toResponse() = IngestionRunResponse(
    id = id,
    provider = provider,
    status = status.name,
    fetched = fetched,
    added = added,
    duplicates = duplicates,
    error = error,
    finishedAt = finishedAt,
    startedAt = startedAt,
    durationMs = durationMs,
    runId = runId,
    errorCode = errorCode,
)

data class ModelRunResponse(
    val id: UUID,
    val provider: String,
    val operation: String,
    val model: String,
    val promptVersion: String?,
    val extractorVersion: String?,
    val sourceDocumentId: UUID?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMs: Long?,
    val estimatedCost: BigDecimal?,
    val success: Boolean,
    val error: String?,
    val createdAt: Instant,
    val attemptId: UUID? = null,
    val runId: UUID? = null,
    val errorCode: String? = null,
)

data class ModelRunsResponse(val runs: List<ModelRunResponse>)

fun ModelRunRecord.toResponse() = ModelRunResponse(
    id = id,
    provider = provider,
    operation = operation,
    model = model,
    promptVersion = promptVersion,
    extractorVersion = extractorVersion,
    sourceDocumentId = sourceDocumentId,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    latencyMs = latencyMs,
    estimatedCost = estimatedCost,
    success = success,
    error = error,
    createdAt = createdAt,
    attemptId = processingAttemptId,
    runId = null,
    errorCode = errorCode,
)
