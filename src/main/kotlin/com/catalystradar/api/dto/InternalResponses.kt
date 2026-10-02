package com.catalystradar.api.dto

import com.catalystradar.application.pipeline.PipelineResult
import com.catalystradar.security.CreatedApiKey
import java.util.UUID

data class PipelineResultResponse(
    val status: String,
    val documentsProcessed: Int,
    val eventsExtracted: Int,
    val companiesRescored: Int,
    val error: String?,
    val documentsSkipped: Int,
    val documentsRetryScheduled: Int,
    val documentsTerminalFailures: Int,
    val alreadyRunning: Boolean,
)

fun PipelineResult.toResponse() = PipelineResultResponse(
    status = status,
    documentsProcessed = documentsProcessed,
    eventsExtracted = eventsExtracted,
    companiesRescored = companiesRescored,
    error = error,
    documentsSkipped = documentsSkipped,
    documentsRetryScheduled = documentsRetryScheduled,
    documentsTerminalFailures = documentsTerminalFailures,
    alreadyRunning = alreadyRunning,
)

data class CreateApiClientRequest(val name: String)

data class CreatedApiClientResponse(
    val clientId: UUID,
    val name: String,
    val prefix: String,
    val rawKey: String,
)

fun CreatedApiKey.toResponse() = CreatedApiClientResponse(
    clientId = clientId,
    name = name,
    prefix = prefix,
    rawKey = rawKey,
)
