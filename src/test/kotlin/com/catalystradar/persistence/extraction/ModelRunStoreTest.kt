package com.catalystradar.persistence.extraction

import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.document.SourceDocumentStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class ModelRunStoreTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var runs: ModelRunStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Test
    fun `records successful extraction runs with cost metadata`() {
        val document = documents.save(
            SourceDocument(
                provider = "polygon",
                title = "t",
                body = "b",
                discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
            ),
        )

        runs.record(
            ModelRunInput(
                provider = "openai",
                operation = "extract",
                model = "gpt-4o-mini",
                promptVersion = "event-extractor-v1",
                extractorVersion = "event-extractor-v1",
                sourceDocumentId = document.id,
                inputTokens = 100,
                outputTokens = 50,
                latencyMs = 1200L,
                estimatedCost = BigDecimal("0.000045"),
                success = true,
            ),
        )

        val stored = runs.findByDocument(document.id)

        assertEquals(1, stored.size)
        assertTrue(stored[0].success)
        assertEquals(100, stored[0].inputTokens)
        assertEquals(1200L, stored[0].latencyMs)
        assertEquals(BigDecimal("0.000045"), stored[0].estimatedCost?.stripTrailingZeros())
        assertNull(stored[0].error)
    }

    @Test
    fun `records failed runs with error summary`() {
        val document = documents.save(
            SourceDocument(
                provider = "polygon",
                title = "t",
                body = "b",
                discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
            ),
        )

        runs.record(
            ModelRunInput(
                provider = "openai",
                operation = "extract",
                model = "gpt-4o-mini",
                sourceDocumentId = document.id,
                success = false,
                error = "401 Unauthorized",
            ),
        )

        val stored = runs.findByDocument(document.id)

        assertEquals(1, stored.size)
        assertEquals(false, stored[0].success)
        assertEquals("401 Unauthorized", stored[0].error)
    }
}
