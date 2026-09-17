package com.catalystradar.domain.event

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceDocumentTest {

    @Test
    fun `creates source document with required fields`() {
        val discoveredAt = Instant.parse("2026-09-16T10:00:00Z")

        val document = SourceDocument(
            provider = "polygon",
            title = "Dell raises guidance",
            body = "Dell raised its full-year outlook.",
            discoveredAt = discoveredAt,
        )

        assertEquals("polygon", document.provider)
        assertEquals("Dell raises guidance", document.title)
        assertEquals(discoveredAt, document.discoveredAt)
        assertNull(document.publishedAt)
        assertNull(document.providerDocumentId)
    }

    @Test
    fun `rejects blank title`() {
        assertThrows<IllegalArgumentException> {
            SourceDocument(
                provider = "polygon",
                title = "  ",
                body = "Dell raised its full-year outlook.",
                discoveredAt = Instant.now(),
            )
        }
    }

    @Test
    fun `rejects blank body`() {
        assertThrows<IllegalArgumentException> {
            SourceDocument(
                provider = "finnhub",
                title = "Dell raises guidance",
                body = "",
                discoveredAt = Instant.now(),
            )
        }
    }
}
