package com.catalystradar.persistence.extraction

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Table("model_runs")
data class ModelRunRow(
    @Id val id: UUID,
    val provider: String,
    val operation: String,
    val model: String,
    @Column("prompt_version") val promptVersion: String?,
    @Column("extractor_version") val extractorVersion: String?,
    @Column("source_document_id") val sourceDocumentId: UUID?,
    @Column("input_tokens") val inputTokens: Int?,
    @Column("output_tokens") val outputTokens: Int?,
    @Column("latency_ms") val latencyMs: Long?,
    @Column("estimated_cost") val estimatedCost: BigDecimal?,
    val success: Boolean,
    val error: String?,
)

data class ModelRunRecord(
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
)

data class ModelRunInput(
    val provider: String,
    val operation: String,
    val model: String,
    val promptVersion: String? = null,
    val extractorVersion: String? = null,
    val sourceDocumentId: UUID? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val latencyMs: Long? = null,
    val estimatedCost: BigDecimal? = null,
    val success: Boolean,
    val error: String? = null,
)

fun ModelRunRow.toRecord() = ModelRunRecord(
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
)

fun ModelRunInput.toRow(id: UUID) = ModelRunRow(
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
)
