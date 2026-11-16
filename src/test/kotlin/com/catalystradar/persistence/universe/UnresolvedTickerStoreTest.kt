package com.catalystradar.persistence.universe

import com.catalystradar.application.ingestion.DocumentRegistration
import com.catalystradar.application.ingestion.SourceDocumentRegistrationService
import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.ports.RawArticle
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

@Transactional
class UnresolvedTickerStoreTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var store: UnresolvedTickerStore

    @Autowired
    private lateinit var registrations: SourceDocumentRegistrationService

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var jdbc: JdbcClient

    private val now = Instant.parse("2026-11-16T12:00:00Z")
    private val month = now.minusSeconds(30L * 86_400)

    @Test
    fun `registration records only the tickers no configured company explains`() {
        companies.save(Company(ticker = "DELL", name = "Dell"))

        val registration = register("mixed", listOf("DELL", "smci", "SMCI", "bad ticker"))

        val documentId = assertIs<DocumentRegistration.Queued>(registration).documentId
        assertEquals(listOf("SMCI"), store.findTickers(documentId))
    }

    @Test
    fun `candidates rank by stored documents and count never-queued documents separately`() {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        register("a", listOf("DELL", "SMCI"))
        register("b", listOf("SMCI"))
        register("c", listOf("SMCI", "ARM"))
        register("d", listOf("ARM"))
        register("e", listOf("DELL", "PLTR"))

        val page = store.candidates(CandidateQuery(since = month))

        assertEquals(listOf("SMCI", "ARM", "PLTR"), page.candidates.map { it.ticker })
        assertEquals(3, page.total)
        val smci = page.candidates.first()
        assertEquals(3, smci.documents)
        assertEquals(2, smci.neverQueuedDocuments)
        assertEquals(0, page.candidates.last().neverQueuedDocuments)
    }

    @Test
    fun `candidates honour the window, minimum and paging, and drop configured tickers`() {
        register("old", listOf("ARM"), discoveredAt = month.minusSeconds(60))
        register("b", listOf("SMCI"))
        register("c", listOf("SMCI"))
        register("d", listOf("ARM"))
        register("e", listOf("PLTR"))

        assertEquals(listOf("SMCI"), store.candidates(CandidateQuery(since = month, minDocuments = 2)).candidates.map { it.ticker })
        assertEquals(listOf("PLTR"), store.candidates(CandidateQuery(since = month, limit = 1, offset = 2)).candidates.map { it.ticker })

        companies.save(Company(ticker = "SMCI", name = "Super Micro"))

        assertEquals(listOf("ARM", "PLTR"), store.candidates(CandidateQuery(since = month)).candidates.map { it.ticker })
    }

    @Test
    fun `migration backfills unresolved tickers from stored raw payloads`() {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO source_documents (id, provider, title, body, discovered_at, content_hash, raw_payload)
            VALUES (:id, 'polygon', 'Legacy', 'Legacy body', :at, :hash, CAST(:payload AS jsonb))
            """.trimIndent(),
        )
            .param("id", id)
            .param("at", java.sql.Timestamp.from(now))
            .param("hash", "legacy-$id")
            .param("payload", """{"provider":"polygon","tickers":"DELL, smci,bad ticker,ARM"}""")
            .update()

        jdbc.sql(backfillStatement()).update()

        assertEquals(listOf("ARM", "SMCI"), store.findTickers(id))
    }

    private fun backfillStatement(): String {
        val migration = javaClass.getResource("/db/migration/V10__add_unresolved_document_tickers.sql")!!.readText()
        return migration.substring(migration.indexOf("INSERT INTO")).trim().removeSuffix(";")
    }

    private fun register(id: String, tickers: List<String>, discoveredAt: Instant = now): DocumentRegistration =
        registrations.registerIfNew(
            "polygon",
            RawArticle(
                provider = "polygon",
                providerArticleId = "article-$id",
                url = "https://example.com/$id",
                title = "Story $id",
                body = "Body of story $id",
                publishedAt = discoveredAt,
                tickers = tickers,
            ),
            discoveredAt,
        )
}
