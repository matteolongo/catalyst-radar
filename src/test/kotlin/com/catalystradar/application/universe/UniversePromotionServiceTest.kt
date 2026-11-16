package com.catalystradar.application.universe

import com.catalystradar.application.ingestion.DocumentRegistration
import com.catalystradar.application.ingestion.SourceDocumentRegistrationService
import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.DocumentProcessingStore
import com.catalystradar.persistence.document.SourceDocumentCompanyStore
import com.catalystradar.persistence.universe.CandidateQuery
import com.catalystradar.persistence.universe.UnresolvedTickerStore
import com.catalystradar.ports.CompanyLookupProvider
import com.catalystradar.ports.CompanyReference
import com.catalystradar.ports.ProviderException
import com.catalystradar.ports.RawArticle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class UniversePromotionServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var writer: UniversePromotionWriter

    @Autowired
    private lateinit var registrations: SourceDocumentRegistrationService

    @Autowired
    private lateinit var processing: DocumentProcessingStore

    @Autowired
    private lateinit var documentCompanies: SourceDocumentCompanyStore

    @Autowired
    private lateinit var unresolved: UnresolvedTickerStore

    private val now = Instant.parse("2026-11-16T12:00:00Z")
    private val lookups = mutableListOf<String>()

    private val equities = object : CompanyLookupProvider {
        override suspend fun findUsEquity(ticker: String): CompanyReference? {
            lookups += ticker
            return if (ticker == "SMCI") CompanyReference(ticker = "SMCI", name = "Super Micro Computer", exchange = "Nasdaq") else null
        }
    }

    private fun service(lookup: CompanyLookupProvider = equities, cap: Int = 1000) =
        UniversePromotionService(companies, lookup, writer, UniverseProperties(maxActiveCompanies = cap))

    @Test
    fun `promotion tracks the company, links its documents and queues only never-queued ones`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        val mixed = queued(register("mixed", listOf("DELL", "SMCI")))
        val alone = unresolvedId(register("alone", listOf("SMCI")))

        val result = service().promote("smci", now)

        assertTrue(result.created)
        assertEquals("SMCI", result.company.ticker)
        assertEquals(2, result.documentsLinked)
        assertEquals(1, result.documentsQueued)
        assertEquals(now, companies.findTrackedSince(result.company.id))
        assertTrue(documentCompanies.findCompanyIds(alone).contains(result.company.id))
        assertTrue(documentCompanies.findCompanyIds(mixed).contains(result.company.id))
        assertNotNull(processing.findBySourceDocumentId(alone))
        assertEquals(emptyList(), unresolved.candidates(CandidateQuery(since = now.minusSeconds(86_400))).candidates)
    }

    @Test
    fun `promoting an existing company changes nothing and calls no provider`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))

        val result = service().promote("DELL", now)

        assertFalse(result.created)
        assertEquals(0, result.documentsQueued)
        assertNull(result.trackedSince)
        assertEquals(emptyList(), lookups)
    }

    @Test
    fun `a ticker that is not a US common stock is refused and stays a candidate`() = runTest {
        unresolvedId(register("etf", listOf("SPY")))

        assertFailsWith<NotUsEquityException> { service().promote("SPY", now) }

        assertNull(companies.findByTicker("SPY"))
        assertEquals(listOf("SPY"), unresolved.candidates(CandidateQuery(since = now.minusSeconds(86_400))).candidates.map { it.ticker })
    }

    @Test
    fun `promotion stops at the active-company cap before calling the provider`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))

        assertFailsWith<UniverseCapReachedException> { service(cap = 1).promote("SMCI", now) }

        assertEquals(emptyList(), lookups)
        assertNull(companies.findByTicker("SMCI"))
    }

    @Test
    fun `a failing reference provider is reported without creating anything`() = runTest {
        val failing = object : CompanyLookupProvider {
            override suspend fun findUsEquity(ticker: String): CompanyReference? =
                throw ProviderException.TemporaryUnavailable("down", provider = "polygon")
        }

        assertFailsWith<ReferenceLookupFailedException> { service(failing).promote("SMCI", now) }

        assertNull(companies.findByTicker("SMCI"))
    }

    private fun queued(registration: DocumentRegistration): UUID = assertIs<DocumentRegistration.Queued>(registration).documentId

    private fun unresolvedId(registration: DocumentRegistration): UUID = assertIs<DocumentRegistration.Unresolved>(registration).documentId

    private fun register(id: String, tickers: List<String>): DocumentRegistration =
        registrations.registerIfNew(
            "polygon",
            RawArticle(
                provider = "polygon",
                providerArticleId = "article-$id",
                url = "https://example.com/$id",
                title = "Story $id",
                body = "Body of story $id",
                publishedAt = now.minusSeconds(3_600),
                tickers = tickers,
            ),
            now.minusSeconds(3_600),
        )
}
