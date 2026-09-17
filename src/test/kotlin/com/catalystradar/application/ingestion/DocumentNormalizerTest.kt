package com.catalystradar.application.ingestion

import com.catalystradar.ports.RawArticle
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DocumentNormalizerTest {

    @Test
    fun `hash is stable for identical input`() {
        val first = normalizeArticle(newArticle(), Instant.parse("2026-09-16T10:00:00Z"))
        val second = normalizeArticle(newArticle(), Instant.parse("2026-09-17T10:00:00Z"))

        assertEquals(first.contentHash, second.contentHash)
        assertEquals(64, first.contentHash.length)
    }

    @Test
    fun `hash changes when body changes`() {
        val first = normalizeArticle(newArticle(), Instant.now())
        val second = normalizeArticle(newArticle().copy(body = "Something else happened."), Instant.now())

        assertNotEquals(first.contentHash, second.contentHash)
    }

    @Test
    fun `trims surrounding whitespace before hashing`() {
        val padded = normalizeArticle(newArticle().copy(title = "  Dell raises forecast  "), Instant.now())
        val plain = normalizeArticle(newArticle(), Instant.now())

        assertEquals(plain.contentHash, padded.contentHash)
        assertEquals("Dell raises forecast", padded.title)
    }

    private fun newArticle() = RawArticle(
        provider = "polygon",
        providerArticleId = "poly-1",
        url = "https://example.com/a",
        title = "Dell raises forecast",
        body = "Dell raised its full-year outlook.",
        publishedAt = Instant.parse("2026-09-16T14:30:00Z"),
        tickers = listOf("DELL"),
    )
}
