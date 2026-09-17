package com.catalystradar.persistence.document

import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.persistence.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Transactional
class SourceDocumentStoreTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var store: SourceDocumentStore

    @Test
    fun `persists and reloads document with provenance`() {
        val publishedAt = Instant.parse("2026-09-15T21:00:00Z")
        val discoveredAt = Instant.parse("2026-09-16T10:00:00Z")

        val saved = store.save(
            SourceDocument(
                provider = "polygon",
                providerDocumentId = "poly-1",
                canonicalUrl = "https://example.com/a",
                title = "Dell raises guidance",
                body = "Dell raised its full-year outlook.",
                publishedAt = publishedAt,
                discoveredAt = discoveredAt,
                contentHash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            ),
        )

        val reloaded = store.findByProviderAndProviderDocumentId("polygon", "poly-1")

        assertNotNull(reloaded)
        assertEquals(saved.id, reloaded.id)
        assertEquals("https://example.com/a", reloaded.canonicalUrl)
        assertEquals(publishedAt, reloaded.publishedAt)
        assertEquals(discoveredAt, reloaded.discoveredAt)
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", reloaded.contentHash)
    }

    @Test
    fun `rejects duplicate provider document`() {
        store.save(newDocument(providerDocumentId = "poly-1"))

        assertThrows<DataIntegrityViolationException> {
            store.save(newDocument(providerDocumentId = "poly-1"))
        }
    }

    @Test
    fun `rejects duplicate content hash`() {
        store.save(newDocument(providerDocumentId = "poly-1", contentHash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"))

        assertThrows<DataIntegrityViolationException> {
            store.save(newDocument(providerDocumentId = "poly-2", contentHash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"))
        }
    }

    @Test
    fun `allows documents without external id`() {
        val first = store.save(newDocument(providerDocumentId = null))
        val second = store.save(newDocument(providerDocumentId = null))

        assertNotNull(store.findById(first.id))
        assertNotNull(store.findById(second.id))
    }

    private fun newDocument(providerDocumentId: String? = "poly-1", contentHash: String? = null) =
        SourceDocument(
            provider = "polygon",
            providerDocumentId = providerDocumentId,
            title = "Dell raises guidance",
            body = "Dell raised its full-year outlook.",
            discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
            contentHash = contentHash,
        )
}

