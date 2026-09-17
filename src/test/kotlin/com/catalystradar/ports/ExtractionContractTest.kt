package com.catalystradar.ports

import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtractionContractTest {

    @Test
    fun `extraction provider returns candidates for review`() = runTest {
        val provider = object : EventExtractionProvider {
            override suspend fun extract(request: ExtractionRequest) = ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    ExtractedEvent(
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
                        evidence = listOf(EvidenceSpan("raised guidance", null)),
                        attributes = mapOf("period" to "FY2026"),
                    ),
                ),
            )
        }

        val document = SourceDocument(
            provider = "polygon",
            title = "t",
            body = "b",
            discoveredAt = java.time.Instant.now(),
        )
        val result = provider.extract(
            ExtractionRequest(document = document, companies = emptyList()),
        )

        assertTrue(result.documentRelevant)
        assertEquals(1, result.events.size)
        assertEquals(EventType.GUIDANCE_RAISE, result.events[0].type)
    }

    @Test
    fun `irrelevant documents yield no candidates`() {
        val result = ExtractionResult(documentRelevant = false, events = emptyList())

        assertEquals(emptyList(), result.events)
    }
}
