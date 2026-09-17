package com.catalystradar.application.scoring

import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceQuality
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoreCalculatorTest {

    private val asOf = Instant.parse("2026-09-16T10:00:00Z")
    private val calculator = ScoreCalculator()

    @Test
    fun `scores a lone guidance raise`() {
        val result = calculator.calculate(listOf(raise(confidence = 1.0)), asOf)

        assertEquals(9.0, result.contributions.single().value, 1e-9)
        assertEquals(9.0, result.rawScore, 1e-9)
        assertEquals(100 * 9.0 / 39.0, result.score.value, 1e-9)
        assertEquals("score-v1", result.score.version)
    }

    @Test
    fun `confidence scales the contribution`() {
        val result = calculator.calculate(listOf(raise(confidence = 0.5)), asOf)

        assertEquals(4.5, result.contributions.single().value, 1e-9)
    }

    @Test
    fun `negative events subtract from the total`() {
        val result = calculator.calculate(
            listOf(raise(confidence = 1.0), miss(confidence = 1.0)),
            asOf,
        )

        assertEquals((9.0 - 7.2) * 1.05, result.rawScore, 1e-9)
        assertTrue(result.score.value < 100 * 9.0 / 39.0)
    }

    @Test
    fun `time decay halves each half-life`() {
        val aged = raise(confidence = 1.0).copy(
            eventTimestamp = asOf.minusSeconds(20L * 24 * 3600),
            discoveredAt = asOf.minusSeconds(20L * 24 * 3600),
        )

        val result = calculator.calculate(listOf(aged), asOf)

        assertEquals(4.5, result.contributions.single().value, 1e-9)
    }

    @Test
    fun `inferred directness halves against direct`() {
        val direct = calculator.calculate(listOf(raise(confidence = 1.0)), asOf)
        val inferred = calculator.calculate(
            listOf(raise(confidence = 1.0).copy(directness = Directness.INFERRED)),
            asOf,
        )

        assertEquals(direct.contributions.single().value / 2, inferred.contributions.single().value, 1e-9)
    }

    @Test
    fun `tier1 outranks tier2`() {
        val tier1 = calculator.calculate(listOf(raise(confidence = 1.0)), asOf)
        val tier2 = calculator.calculate(
            listOf(
                raise(confidence = 1.0).copy(sourceQuality = SourceQuality.TIER2_NEWS),
            ),
            asOf,
        )

        assertEquals(9.0, tier1.contributions.single().value, 1e-9)
        assertEquals(7.0, tier2.contributions.single().value, 1e-9)
    }

    @Test
    fun `materiality and surprise scale linearly`() {
        val result = calculator.calculate(
            listOf(raise(confidence = 1.0).copy(materiality = 0.5, surprise = 0.5)),
            asOf,
        )

        assertEquals(9.0 * 0.25, result.contributions.single().value, 1e-9)
    }

    @Test
    fun `convergence rewards independent families`() {
        val result = calculator.calculate(
            listOf(raise(confidence = 1.0), earningsBeat()),
            asOf,
        )

        assertEquals(2, result.familyCount)
        assertEquals((9.0 + 7.2) * 1.05, result.rawScore, 1e-9)
    }

    @Test
    fun `all-negative input floors at zero`() {
        val result = calculator.calculate(listOf(miss(confidence = 1.0)), asOf)

        assertEquals(-7.2, result.rawScore, 1e-9)
        assertEquals(0.0, result.score.value, 1e-9)
    }

    @Test
    fun `empty input scores zero`() {
        val result = calculator.calculate(emptyList(), asOf)

        assertEquals(0.0, result.rawScore, 1e-9)
        assertEquals(0.0, result.score.value, 1e-9)
        assertTrue(result.contributions.isEmpty())
    }

    @Test
    fun `neutral direction contributes nothing`() {
        val result = calculator.calculate(
            listOf(raise(confidence = 1.0).copy(direction = Direction.NEUTRAL)),
            asOf,
        )

        assertTrue(result.contributions.isEmpty())
        assertEquals(0.0, result.score.value, 1e-9)
    }

    @Test
    fun `stale contributions below cutoff are ignored`() {
        val stale = raise(confidence = 1.0).copy(
            eventTimestamp = asOf.minusSeconds(200L * 24 * 3600),
            discoveredAt = asOf.minusSeconds(200L * 24 * 3600),
        )

        val result = calculator.calculate(listOf(stale), asOf)

        assertTrue(result.contributions.isEmpty())
        assertEquals(0.0, result.score.value, 1e-9)
    }

    private fun raise(confidence: Double) = CatalystEvent(
        companyId = UUID.randomUUID(),
        type = EventType.GUIDANCE_RAISE,
        direction = Direction.POSITIVE,
        confidence = confidence,
        sourceQuality = SourceQuality.TIER1_NEWS,
        expectedHorizon = EventHorizon.WEEKS,
        directness = Directness.DIRECT,
        eventTimestamp = asOf,
        discoveredAt = asOf,
        taxonomyVersion = "taxonomy-v1",
        extractorVersion = "event-extractor-v1",
    )

    private fun miss(confidence: Double) = raise(confidence).copy(
        type = EventType.EARNINGS_MISS,
        direction = Direction.NEGATIVE,
    )

    private fun earningsBeat() = raise(1.0).copy(type = EventType.EARNINGS_BEAT)
}
