package com.catalystradar.api.dto

import com.catalystradar.application.catalyst.CatalystView
import com.catalystradar.application.catalyst.EventDriver
import com.catalystradar.domain.catalyst.CatalystSnapshot
import com.catalystradar.persistence.catalyst.StateTransitionRecord
import java.time.Instant
import java.util.UUID

data class EventDriverResponse(
    val eventId: UUID,
    val type: String,
    val direction: String,
    val contribution: Double,
)

data class CatalystResponse(
    val ticker: String,
    val score: Double,
    val state: String,
    val velocity1d: Double,
    val velocity3d: Double,
    val velocity7d: Double,
    val positiveScore: Double,
    val negativeScore: Double,
    val directScore: Double,
    val inferredScore: Double,
    val totalEvents: Int,
    val events7d: Int,
    val topDrivers: List<EventDriverResponse>,
    val scoreVersion: String,
    val taxonomyVersion: String,
    val asOf: Instant,
)

fun EventDriver.toResponse() = EventDriverResponse(
    eventId = eventId,
    type = type,
    direction = direction,
    contribution = contribution,
)

fun CatalystView.toResponse() = CatalystResponse(
    ticker = ticker,
    score = score,
    state = state.name,
    velocity1d = velocity1d,
    velocity3d = velocity3d,
    velocity7d = velocity7d,
    positiveScore = positiveScore,
    negativeScore = negativeScore,
    directScore = directScore,
    inferredScore = inferredScore,
    totalEvents = totalEvents,
    events7d = events7d,
    topDrivers = topDrivers.map { it.toResponse() },
    scoreVersion = scoreVersion,
    taxonomyVersion = taxonomyVersion,
    asOf = asOf,
)

data class SnapshotResponse(
    val score: Double,
    val state: String,
    val velocity1d: Double,
    val velocity3d: Double,
    val velocity7d: Double,
    val scoreVersion: String,
    val taxonomyVersion: String,
    val asOf: Instant,
)

fun CatalystSnapshot.toResponse() = SnapshotResponse(
    score = score.value,
    state = state.name,
    velocity1d = velocity.velocity1d,
    velocity3d = velocity.velocity3d,
    velocity7d = velocity.velocity7d,
    scoreVersion = score.version,
    taxonomyVersion = taxonomyVersion,
    asOf = asOf,
)

data class TransitionResponse(
    val from: String,
    val to: String,
    val score: Double,
    val scoreVersion: String,
    val at: Instant,
)

fun StateTransitionRecord.toResponse() = TransitionResponse(
    from = from.name,
    to = to.name,
    score = score.value,
    scoreVersion = score.version,
    at = at,
)

data class TimelineResponse(
    val ticker: String,
    val snapshots: List<SnapshotResponse>,
    val transitions: List<TransitionResponse>,
)
