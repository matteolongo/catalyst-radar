package com.catalystradar.application.scoring

import com.catalystradar.domain.catalyst.CatalystScore
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Direction
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow

data class EventContribution(
    val eventId: UUID,
    val value: Double,
    val sign: Double,
    val baseWeight: Double,
    val confidence: Double,
    val materialityFactor: Double,
    val surpriseFactor: Double,
    val sourceQualityFactor: Double,
    val directnessFactor: Double,
    val timeDecayFactor: Double,
)

data class CalculatedScore(
    val score: CatalystScore,
    val contributions: List<EventContribution>,
    val rawScore: Double,
    val familyCount: Int,
    val contributionSum: Double,
    val convergenceMultiplier: Double,
    val normalizationScale: Double,
    val contributionCutoff: Double,
)

/**
 * Deterministic score-v1 engine. Pure function of canonical events,
 * configuration, and the as-of instant: no LLM judgment, no clocks,
 * no randomness. Novelty is structural — only canonical (deduplicated)
 * events reach this engine, so repeats never multiply impact.
 */
class ScoreCalculator(private val config: ScoreConfig = ScoreV1.config) {

    fun calculate(events: List<CatalystEvent>, asOf: Instant): CalculatedScore {
        val contributions = events.mapNotNull { event ->
            val contribution = contribution(event, asOf)
            val value = contribution.value.takeIf { abs(it) >= config.contributionCutoff }
                ?: return@mapNotNull null
            contribution
        }
        val families = events.filter { event -> contributions.any { it.eventId == event.id } }
            .map { it.family }
            .toSet()
        val contributionSum = contributions.sumOf { it.value }
        val convergenceMultiplier = config.convergenceMultiplier(families.size)
        val raw = contributionSum * convergenceMultiplier
        val score = scoreForRaw(raw)
        return CalculatedScore(
            score = CatalystScore(value = score, version = config.version),
            contributions = contributions,
            rawScore = raw,
            familyCount = families.size,
            contributionSum = contributionSum,
            convergenceMultiplier = convergenceMultiplier,
            normalizationScale = config.normalizationScale,
            contributionCutoff = config.contributionCutoff,
        )
    }

    /** Bounded 0..100 mapping shared by totals and read-only breakdowns. */
    fun scoreForRaw(raw: Double): Double =
        if (raw <= 0.0) {
            0.0
        } else {
            (100.0 * raw / (raw + config.normalizationScale)).coerceIn(0.0, 100.0)
        }

    private fun contribution(event: CatalystEvent, asOf: Instant): EventContribution {
        val sign = when (event.direction) {
            Direction.POSITIVE -> 1.0
            Direction.NEGATIVE -> -1.0
            else -> 0.0
        }
        val ageDays = ageDays(event, asOf)
        val decay = 0.5.pow(ageDays / config.halfLifeDays(event.type))
        val baseWeight = config.baseWeights.getValue(event.type)
        val materialityFactor = event.materiality ?: 1.0
        val surpriseFactor = event.surprise ?: 1.0
        val sourceQualityFactor = config.sourceQualityFactors.getValue(event.sourceQuality)
        val directnessFactor = config.directnessFactors.getValue(event.directness)
        val value = baseWeight *
            sign *
            event.confidence *
            materialityFactor *
            surpriseFactor *
            sourceQualityFactor *
            directnessFactor *
            decay
        return EventContribution(event.id, value, sign, baseWeight, event.confidence, materialityFactor,
            surpriseFactor, sourceQualityFactor, directnessFactor, decay)
    }

    private fun ageDays(event: CatalystEvent, asOf: Instant): Double {
        val at = event.eventTimestamp ?: asOf
        val millis = Duration.between(at, asOf).toMillis().coerceAtLeast(0)
        return millis / 86_400_000.0
    }
}
