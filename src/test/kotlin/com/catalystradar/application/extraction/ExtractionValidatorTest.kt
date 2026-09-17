package com.catalystradar.application.extraction

import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.ports.EvidenceSpan
import com.catalystradar.ports.ExtractedEvent
import com.catalystradar.ports.ExtractionResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtractionValidatorTest {

    private val validator = ExtractionValidator()

    @Test
    fun `keeps a valid candidate`() {
        val result = validator.validate(ExtractionResult(documentRelevant = true, events = listOf(valid())))

        assertEquals(1, result.events.size)
    }

    @Test
    fun `drops out-of-range confidence`() {
        val result = validator.validate(
            ExtractionResult(documentRelevant = true, events = listOf(valid().copy(confidence = 1.5))),
        )

        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `drops candidates without evidence`() {
        val result = validator.validate(
            ExtractionResult(documentRelevant = true, events = listOf(valid().copy(evidence = emptyList()))),
        )

        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `drops blank tickers`() {
        val result = validator.validate(
            ExtractionResult(documentRelevant = true, events = listOf(valid().copy(ticker = " "))),
        )

        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `irrelevant verdict clears stray events`() {
        val result = validator.validate(ExtractionResult(documentRelevant = false, events = listOf(valid())))

        assertEquals(false, result.documentRelevant)
        assertTrue(result.events.isEmpty())
    }

    private fun valid() = ExtractedEvent(
        ticker = "DELL",
        type = EventType.GUIDANCE_RAISE,
        direction = Direction.POSITIVE,
        confidence = 0.9,
        magnitude = null,
        surprise = 0.6,
        materiality = 0.8,
        expectedHorizon = EventHorizon.WEEKS,
        directness = Directness.DIRECT,
        eventTimestamp = null,
        evidence = listOf(EvidenceSpan("raised outlook", null)),
        attributes = mapOf("period" to "FY2026"),
    )
}
