package com.catalystradar.persistence.document

import com.catalystradar.application.ingestion.DocumentProcessingStatus
import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Transactional
class DocumentProcessingStoreTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var documentCompanies: SourceDocumentCompanyStore

    @Autowired
    private lateinit var processing: DocumentProcessingStore

    @Test
    fun `keeps resolved companies and one pending processing record per document`() {
        val document = documents.save(newDocument())
        val dell = companies.save(Company(ticker = "DELL", name = "Dell Technologies"))
        val hp = companies.save(Company(ticker = "HPQ", name = "HP"))

        documentCompanies.link(document.id, listOf(hp.id, dell.id, hp.id))
        val first = processing.ensurePending(document.id, NOW)
        val second = processing.ensurePending(document.id, NOW.plusSeconds(60))

        assertEquals(listOf(dell.id, hp.id).sortedBy { it.toString() }, documentCompanies.findCompanyIds(document.id))
        assertEquals(DocumentProcessingStatus.PENDING, first.status)
        assertEquals(0, first.attemptCount)
        assertNull(first.nextAttemptAt)
        assertEquals(first, second)
        assertEquals(listOf(document.id), processing.findDue(NOW, limit = 10).map { it.sourceDocumentId })
    }

    @Test
    fun `makes retryable processing available only at its next attempt time`() {
        val document = documents.save(newDocument())
        processing.ensurePending(document.id, NOW)
        processing.markProcessing(document.id, NOW.plusSeconds(1))
        val retryAt = NOW.plusSeconds(300)
        processing.markRetryable(
            sourceDocumentId = document.id,
            errorCode = "TEMPORARY_UNAVAILABLE",
            errorMessage = "provider timed out",
            nextAttemptAt = retryAt,
            now = NOW.plusSeconds(2),
        )

        assertEquals(emptyList(), processing.findDue(NOW.plusSeconds(299), limit = 10))
        val due = processing.findDue(retryAt, limit = 10).single()
        assertEquals(document.id, due.sourceDocumentId)
        assertEquals(DocumentProcessingStatus.RETRYABLE_ERROR, due.status)
        assertEquals(1, due.attemptCount)
        assertEquals(retryAt, due.nextAttemptAt)
    }

    private fun newDocument() = SourceDocument(
        provider = "polygon",
        title = "Dell raises guidance",
        body = "Dell raised its full-year outlook.",
        discoveredAt = NOW,
    )

    companion object {
        val NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
    }
}
