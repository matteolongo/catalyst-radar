package com.catalystradar.api.dto

import com.catalystradar.application.pipeline.PipelineResult
import com.catalystradar.application.replay.ReplayResult
import com.catalystradar.security.CreatedApiKey
import java.time.Instant
import java.util.UUID

/**
 * Internal pipeline outcome. `documentsProcessed`/`eventsExtracted` keep
 * their original meaning; the split counters were added so operators can
 * read inserts, reuses, and completions without re-deriving them.
 */
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
    val documentsConsidered: Int,
    val documentsCompleted: Int,
    val eventsInserted: Int,
    val eventsReused: Int,
    val runId: UUID? = null,
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
    documentsConsidered = documentsConsidered,
    documentsCompleted = documentsCompleted,
    eventsInserted = eventsInserted,
    eventsReused = eventsReused,
    runId = runId,
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

data class InternalReplayRequest(
    val ticker: String,
    val cutoff: Instant,
)

data class ReplayResponse(
    val ticker: String,
    val asOf: Instant,
    val score: Double,
    val state: String,
    val velocity1d: Double,
    val velocity3d: Double,
    val velocity7d: Double,
    val eventCount: Int,
    val scoreVersion: String,
    val taxonomyVersion: String,
    val extractorVersion: String,
    val documentsConsidered: Int,
    val documentsSkipped: Int,
    val candidatesAccepted: Int,
)

fun ReplayResult.toResponse() = ReplayResponse(
    ticker = ticker,
    asOf = asOf,
    score = score,
    state = state.name,
    velocity1d = velocity1d,
    velocity3d = velocity3d,
    velocity7d = velocity7d,
    eventCount = eventCount,
    scoreVersion = scoreVersion,
    taxonomyVersion = taxonomyVersion,
    extractorVersion = extractorVersion,
    documentsConsidered = documentsConsidered,
    documentsSkipped = documentsSkipped,
    candidatesAccepted = candidatesAccepted,
)
