package com.catalystradar.api.dto

import com.catalystradar.application.operations.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

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
