package com.catalystradar.persistence.workbench

import com.catalystradar.application.ingestion.SourceDocumentRegistrationService
import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.ports.RawArticle
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Transactional
class NewsSymbolStoreTest : PostgresIntegrationTest() {

    @Autowired private lateinit var store: NewsSymbolStore
    @Autowired private lateinit var registrations: SourceDocumentRegistrationService
    @Autowired private lateinit var companies: CompanyStore

    private val now = Instant.parse("2026-11-17T12:00:00Z")

    private fun register(id: String, tickers: List<String>, publishedAt: Instant = now.minusSeconds(600)) =
        registrations.registerIfNew(
            "polygon",
            RawArticle("polygon", "article-$id", "https://example.com/$id", "Story $id", "Body $id", publishedAt, tickers),
            now,
        )

    @Test
    fun `lists tracked and untracked symbols ranked by documents with queued counts`() {
        companies.save(Company(ticker = "TSN", name = "Tyson Foods"))
        register("a", listOf("TSN", "SLS"))
        register("b", listOf("SLS"))
        register("c", listOf("SLS", "TSN"))
        register("d", listOf("ENR"))

        val page = store.symbols(NewsSymbolQuery(since = now.minus(Duration.ofDays(1))))

        assertEquals(listOf("SLS", "TSN", "ENR"), page.symbols.map { it.ticker })
        assertEquals(3, page.total)
        val (sls, tsn, enr) = page.symbols
        assertEquals(false, sls.tracked)
        assertNull(sls.companyName)
        assertEquals(3, sls.documents)
        assertEquals(0, sls.queuedDocuments)
        assertEquals(true, tsn.tracked)
        assertEquals("Tyson Foods", tsn.companyName)
        assertEquals(2, tsn.queuedDocuments)
        assertEquals(1, enr.documents)
    }

    @Test
    fun `the window follows publication time, not when the article was stored`() {
        register("old", listOf("SLS"), publishedAt = now.minus(Duration.ofDays(3)))
        register("new", listOf("ENR"))

        val page = store.symbols(NewsSymbolQuery(since = now.minus(Duration.ofDays(1))))

        assertEquals(listOf("ENR"), page.symbols.map { it.ticker })
    }

    @Test
    fun `a promoted symbol is listed once, as tracked`() {
        register("a", listOf("SLS"))
        companies.save(Company(ticker = "SLS", name = "SELLAS"))
        register("b", listOf("SLS"))

        val sls = store.symbols(NewsSymbolQuery(since = now.minus(Duration.ofDays(1)))).symbols.single()

        assertEquals(true, sls.tracked)
        assertEquals(1, sls.documents)
    }
}
