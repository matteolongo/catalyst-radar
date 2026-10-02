package com.catalystradar.api.dto

import com.catalystradar.application.catalyst.CatalystView
import com.catalystradar.application.catalyst.EventDriver
import com.catalystradar.application.scoring.CalculatedScore
import com.catalystradar.application.scoring.EventContribution
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.catalyst.CatalystSnapshot
import com.catalystradar.persistence.catalyst.StateTransitionRecord
import java.time.Instant
import java.util.UUID

/** Reconstructed canonical drivers; snapshot rows do not store their original event IDs. */
data class EventDriverResponse(
    val eventId: UUID,
    val type: String,
    val direction: String,
    val contribution: Double,
    val family: String?,
    val eventTimestamp: Instant?,
    val discoveredAt: Instant?,
    val clusterId: UUID?,
    val factors: EventContributionResponse?,
    val evidence: List<EventEvidenceResponse>,
    val source: SafeSourceResponse?,
)

data class EventContributionResponse(
    val sign: Double,
    val baseWeight: Double,
    val confidence: Double,
    val materialityFactor: Double,
    val surpriseFactor: Double,
    val sourceQualityFactor: Double,
    val directnessFactor: Double,
    val timeDecayFactor: Double,
    val value: Double,
)

private fun EventContribution.toResponse() = EventContributionResponse(
    sign, baseWeight, confidence, materialityFactor, surpriseFactor,
    sourceQualityFactor, directnessFactor, timeDecayFactor, value,
)

data class ScoreCalculationResponse(
    val contributionSum: Double,
    val familyCount: Int,
    val convergenceMultiplier: Double,
    val rawScore: Double,
    val normalizationScale: Double,
    val contributionCutoff: Double,
)

private fun CalculatedScore.toResponse() = ScoreCalculationResponse(
    contributionSum, familyCount, convergenceMultiplier, rawScore, normalizationScale, contributionCutoff,
)

data class StateBandResponse(
    val state: String,
    val minScore: Double,
    val maxScore: Double,
    val maxInclusive: Boolean,
)

/** `RECONSTRUCTED_SCORE_MATCH` confirms score/version agreement, not original driver membership. */
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
    val stateBand: StateBandResponse,
    val scoreCalculation: ScoreCalculationResponse?,
    val explanationStatus: String,
)

fun EventDriver.toResponse() = EventDriverResponse(
    eventId = eventId,
    type = type,
    direction = direction,
    contribution = contribution,
    family = family,
    eventTimestamp = eventTimestamp,
    discoveredAt = discoveredAt,
    clusterId = clusterId,
    factors = factors?.toResponse(),
    evidence = evidence.map { it.toResponse() },
    source = source?.toResponse(),
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
    stateBand = StateBandResponse(stateBand.state.name, stateBand.minScore, stateBand.maxScore,
        stateBand.state == CatalystState.HIGH),
    scoreCalculation = scoreCalculation?.toResponse(),
    explanationStatus = explanationStatus.name,
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
