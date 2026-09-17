package com.catalystradar.application.scoring

import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceQuality
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoreConfigTest {

    @Test
    fun `every event type has a base weight and half-life`() {
        EventType.entries.forEach { type ->
            assertTrue((ScoreV1.baseWeights[type] ?: 0.0) > 0.0, "weight for $type")
            assertTrue((ScoreV1.halfLifeDays(type)) > 0.0, "half-life for $type")
        }
    }

    @Test
    fun `design anchor weights hold`() {
        assertEquals(10.0, ScoreV1.baseWeights[EventType.GUIDANCE_RAISE])
        assertEquals(9.0, ScoreV1.baseWeights[EventType.GUIDANCE_ABOVE_CONSENSUS])
    }

    @Test
    fun `analyst actions weigh less than guidance`() {
        val analyst = ScoreV1.baseWeights[EventType.ANALYST_UPGRADE] ?: 0.0
        val guidance = ScoreV1.baseWeights[EventType.GUIDANCE_RAISE] ?: 0.0

        assertTrue(analyst < guidance)
    }

    @Test
    fun `source tiers order regulatory above analyst`() {
        val quality = ScoreV1.sourceQualityFactors

        assertTrue(quality[SourceQuality.PRIMARY]!! > quality[SourceQuality.TIER1_NEWS]!!)
        assertTrue(quality[SourceQuality.TIER1_NEWS]!! > quality[SourceQuality.TIER2_NEWS]!!)
        assertTrue(quality[SourceQuality.TIER2_NEWS]!! > quality[SourceQuality.ANALYST]!!)
    }

    @Test
    fun `direct evidence outweighs inferred`() {
        assertTrue(
            ScoreV1.directnessFactors[Directness.DIRECT]!! >
                ScoreV1.directnessFactors[Directness.INFERRED]!!,
        )
    }

    @Test
    fun `config carries the score version`() {
        assertEquals("score-v1", ScoreV1.version)
    }

    @Test
    fun `half-lives follow the design ladder`() {
        val analyst = ScoreV1.halfLifeDays(EventType.ANALYST_UPGRADE)
        val earnings = ScoreV1.halfLifeDays(EventType.EARNINGS_BEAT)
        val guidance = ScoreV1.halfLifeDays(EventType.GUIDANCE_RAISE)
        val contract = ScoreV1.halfLifeDays(EventType.CONTRACT_WIN)

        assertTrue(analyst < earnings)
        assertTrue(earnings < guidance)
        assertTrue(guidance < contract)
    }
}
