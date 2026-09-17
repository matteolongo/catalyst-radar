package com.catalystradar.persistence

import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.persistence.document.SourceDocumentStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertNotNull

/**
 * Ingestion idempotency guards: stable provider ids win, and identical
 * content from another provider collapses on the content hash so the same
 * story never scores twice.
 */
@Transactional
class DocumentIdempotencyTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var store: SourceDocumentStore

    @Test
    fun `same provider document is rejected on repeat`() {
        store.save(newDocument("polygon", "poly-1", HASH_A))

        assertThrows<DataIntegrityViolationException> {
            store.save(newDocument("polygon", "poly-1", HASH_A))
        }
    }

    @Test
    fun `same content from another provider collapses on hash`() {
        store.save(newDocument("polygon", "poly-1", HASH_A))

        assertThrows<DataIntegrityViolationException> {
            store.save(newDocument("finnhub", "fin-9", HASH_A))
        }
    }

    @Test
    fun `different content from same provider coexists`() {
        val first = store.save(newDocument("polygon", "poly-1", HASH_A))
        val second = store.save(newDocument("polygon", "poly-2", HASH_B))

        assertNotNull(store.findById(first.id))
        assertNotNull(store.findById(second.id))
    }

    private fun newDocument(provider: String, providerDocumentId: String, contentHash: String) =
        SourceDocument(
            provider = provider,
            providerDocumentId = providerDocumentId,
            title = "Dell raises guidance",
            body = "Dell raised its full-year outlook.",
            discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
            contentHash = contentHash,
        )

    companion object {
        const val HASH_A = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        const val HASH_B = "7d793037a0760186574b0282f2f435e806b5a65e374302ac5cf58d7d4d8e"
    }
}
