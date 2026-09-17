package com.catalystradar.application.clustering

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

class ClusteringPrimitivesTest {

    @Test
    fun `identical vectors have cosine one`() {
        assertEquals(1.0, cosineSimilarity(listOf(1f, 0f), listOf(1f, 0f)), 1e-9)
    }

    @Test
    fun `orthogonal vectors have cosine zero`() {
        assertEquals(0.0, cosineSimilarity(listOf(1f, 0f), listOf(0f, 1f)), 1e-9)
    }

    @Test
    fun `opposite vectors have cosine minus one`() {
        assertEquals(-1.0, cosineSimilarity(listOf(1f, 0f), listOf(-1f, 0f)), 1e-9)
    }

    @Test
    fun `zero vectors score zero instead of NaN`() {
        assertEquals(0.0, cosineSimilarity(listOf(0f, 0f), listOf(1f, 0f)), 1e-9)
    }

    @Test
    fun `cluster text is deterministic`() {
        val first = clusterText("DELL", EventType.GUIDANCE_RAISE, "POSITIVE", mapOf("period" to "FY2026"))
        val second = clusterText("DELL", EventType.GUIDANCE_RAISE, "POSITIVE", mapOf("period" to "FY2026"))

        assertEquals(first, second)
    }

    @Test
    fun `cluster text sorts attributes for stability`() {
        val ordered = clusterText("DELL", EventType.GUIDANCE_RAISE, "POSITIVE", mapOf("a" to "1", "b" to "2"))
        val reversed = clusterText("DELL", EventType.GUIDANCE_RAISE, "POSITIVE", mapOf("b" to "2", "a" to "1"))

        assertEquals(ordered, reversed)
    }

    @Test
    fun `different facts produce different text`() {
        val texts = setOf(
            clusterText("DELL", EventType.GUIDANCE_RAISE, "POSITIVE", emptyMap()),
            clusterText("DELL", EventType.GUIDANCE_CUT, "NEGATIVE", emptyMap()),
            clusterText("HPQ", EventType.GUIDANCE_RAISE, "POSITIVE", emptyMap()),
        )

        assertEquals(3, texts.size)
    }

    @Test
    fun `candidate text derives from the event`() {
        val text = candidateText("DELL", newEvent())

        assertTrue(text.contains("DELL"))
        assertTrue(text.contains("GUIDANCE_RAISE"))
    }

    private fun newEvent(): CatalystEvent = CatalystEvent(
        companyId = UUID.randomUUID(),
        type = EventType.GUIDANCE_RAISE,
        direction = Direction.POSITIVE,
        confidence = 0.9,
        sourceQuality = SourceQuality.TIER1_NEWS,
        expectedHorizon = EventHorizon.WEEKS,
        directness = Directness.DIRECT,
        discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
        taxonomyVersion = "taxonomy-v1",
        extractorVersion = "event-extractor-v1",
        attributes = mapOf("period" to "FY2026"),
    )
}
