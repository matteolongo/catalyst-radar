package com.catalystradar.application.scoring

import com.catalystradar.common.Versions
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.EventFamily
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceQuality

/**
 * Versioned score-v1 configuration. All scoring constants live here and
 * nowhere else; any change that alters historical scores ships as a new
 * version (score_versions persistence follows in CR-17 replay work).
 *
 * Weights are initial design defaults awaiting benchmark calibration,
 * not measured truths.
 */
data class ScoreConfig(
    val version: String,
    val baseWeights: Map<EventType, Double>,
    val sourceQualityFactors: Map<SourceQuality, Double>,
    val directnessFactors: Map<Directness, Double>,
    val familyHalfLivesDays: Map<EventFamily, Double>,
    val halfLifeOverridesDays: Map<EventType, Double>,
    val convergenceMultipliers: Map<Int, Double>,
    val normalizationScale: Double,
    val contributionCutoff: Double,
) {
    fun halfLifeDays(type: EventType): Double =
        halfLifeOverridesDays[type] ?: requireNotNull(familyHalfLivesDays[type.family]) {
            "no half-life for family ${type.family}"
        }

    fun convergenceMultiplier(familyCount: Int): Double {
        val eligible = convergenceMultipliers.keys.filter { it <= familyCount }
        return convergenceMultipliers[eligible.maxOrNull()] ?: 1.0
    }
}

object ScoreV1 {

    const val version: String = Versions.SCORE_V1

    private val familyBaseWeights = mapOf(
        EventFamily.GUIDANCE to 9.0,
        EventFamily.EARNINGS to 8.0,
        EventFamily.COMMERCIAL to 8.0,
        EventFamily.REGULATORY to 8.0,
        EventFamily.CORPORATE_ACTION to 8.0,
        EventFamily.PRODUCT_TECH to 7.0,
        EventFamily.LEGAL to 6.0,
        EventFamily.CAPITAL_ALLOCATION to 6.0,
        EventFamily.MANAGEMENT_OWNERSHIP to 5.0,
        EventFamily.INDUSTRY_EXTERNAL to 4.0,
        EventFamily.ANALYST to 3.0,
    )

    private val typeWeightOverrides = mapOf(
        EventType.GUIDANCE_RAISE to 10.0,
        EventType.GUIDANCE_ABOVE_CONSENSUS to 9.0,
        EventType.TAKEOVER_TARGET to 9.0,
        EventType.CONTRACT_WIN to 8.5,
        EventType.APPROVAL_GRANTED to 8.5,
        EventType.ACQUISITION_ANNOUNCED to 8.0,
        EventType.LARGE_ORDER to 8.0,
        EventType.CUSTOMER_WIN to 7.5,
        EventType.ANALYST_UPGRADE to 3.5,
        EventType.ESTIMATE_REVISION_UP to 3.0,
    )

    val baseWeights: Map<EventType, Double> =
        EventType.entries.associateWith { typeWeightOverrides[it] ?: familyBaseWeights.getValue(it.family) }

    val sourceQualityFactors = mapOf(
        SourceQuality.PRIMARY to 1.0,
        SourceQuality.TIER1_NEWS to 0.9,
        SourceQuality.TIER2_NEWS to 0.7,
        SourceQuality.ANALYST to 0.5,
        SourceQuality.OTHER to 0.4,
    )

    val directnessFactors = mapOf(
        Directness.DIRECT to 1.0,
        Directness.INFERRED to 0.5,
    )

    private val familyHalfLives = mapOf(
        EventFamily.ANALYST to 3.0,
        EventFamily.EARNINGS to 10.0,
        EventFamily.GUIDANCE to 20.0,
        EventFamily.COMMERCIAL to 30.0,
        EventFamily.PRODUCT_TECH to 30.0,
        EventFamily.REGULATORY to 45.0,
        EventFamily.CORPORATE_ACTION to 45.0,
        EventFamily.CAPITAL_ALLOCATION to 30.0,
        EventFamily.LEGAL to 30.0,
        EventFamily.MANAGEMENT_OWNERSHIP to 20.0,
        EventFamily.INDUSTRY_EXTERNAL to 7.0,
    )

    private val halfLifeOverrides = mapOf(
        EventType.APPROVAL_GRANTED to 60.0,
        EventType.INDEX_INCLUSION to 90.0,
    )

    private val convergence = mapOf(
        1 to 1.0,
        2 to 1.05,
        3 to 1.1,
        4 to 1.15,
    )

    val config = ScoreConfig(
        version = version,
        baseWeights = baseWeights,
        sourceQualityFactors = sourceQualityFactors,
        directnessFactors = directnessFactors,
        familyHalfLivesDays = familyHalfLives,
        halfLifeOverridesDays = halfLifeOverrides,
        convergenceMultipliers = convergence,
        normalizationScale = 30.0,
        contributionCutoff = 0.01,
    )

    fun halfLifeDays(type: EventType): Double = config.halfLifeDays(type)
}
