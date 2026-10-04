package com.catalystradar.operations

import com.catalystradar.application.operations.*
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.operations.DocumentInspectionStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.*

@Transactional
class DocumentInspectionStoreTest : PostgresIntegrationTest() {
    @Autowired private lateinit var store: DocumentInspectionStore
    @Autowired private lateinit var jdbc: JdbcClient
    private val at = Instant.parse("2026-10-04T10:00:00Z")
    private val fixtures get() = OperationsFixtures(jdbc)

    @Test
    fun `due work excludes active processing and future retries while recovering abandoned attempts`() {
        val pending = fixtures.document(at)
        fixtures.processing(pending, "PENDING", updatedAt = at)
        val retry = fixtures.document(at)
        fixtures.processing(retry, "RETRYABLE_ERROR", next = at, updatedAt = at)
        val future = fixtures.document(at)
        fixtures.processing(future, "RETRYABLE_ERROR", next = at.plusSeconds(1), updatedAt = at)
        val unknownRetry = fixtures.document(at)
        fixtures.processing(unknownRetry, "RETRYABLE_ERROR", updatedAt = at)
        val processing = fixtures.document(at)
        fixtures.processing(processing, "PROCESSING", attempts = 1, updatedAt = at)
        val run = fixtures.run(at, status = "RUNNING")
        fixtures.attempt(processing, run, 1, at, status = "RUNNING")
        assertEquals(setOf(pending, retry), store.search(DocumentQuery(dueOnly = true), at, setOf(run)).items.map { it.id }.toSet())
        assertEquals(setOf(pending, retry, processing), store.search(DocumentQuery(dueOnly = true), at, emptySet()).items.map { it.id }.toSet())
        assertEquals(setOf(pending, retry, processing), store.search(DocumentQuery(dueOnly = true), at, setOf(UUID.randomUUID())).items.map { it.id }.toSet())
    }

    @Test
    fun `ingestion scope matches first capture and detail chooses latest snapshot deterministically`() {
        val document = fixtures.document(at)
        val other = fixtures.document(at)
        val company = fixtures.company("AAA")
        fixtures.link(document, company)
        val ingestion = UUID.randomUUID()
        jdbc.sql("INSERT INTO ingestion_runs(id,provider,status,started_at) VALUES(:id,'polygon','SUCCESS',:at::timestamptz)")
            .param("id", ingestion).param("at", at.toString()).update()
        jdbc.sql("UPDATE source_documents SET first_ingestion_run_id=:ingestion WHERE id=:id")
            .param("ingestion", ingestion).param("id", document).update()
        assertEquals(listOf(document), store.search(DocumentQuery(ingestionRunId = ingestion), at).items.map { it.id })
        assertEquals(ingestion, store.detail(document, at)?.document?.firstIngestionRunId)
        assertNull(store.detail(other, at)?.document?.firstIngestionRunId)
        listOf(at.minusSeconds(1) to at.plusSeconds(100), at to at, at to at.plusSeconds(1)).forEach { (asOf, created) ->
            jdbc.sql("""INSERT INTO catalyst_snapshots(id,company_id,score,score_version,state,velocity_1d,velocity_3d,velocity_7d,taxonomy_version,as_of,created_at)
                VALUES(:id,:company,0,'score-v1','NORMAL',0,0,0,'v1',:asOf::timestamptz,:created::timestamptz)""")
                .param("id", UUID.randomUUID()).param("company", company).param("asOf", asOf.toString()).param("created", created.toString()).update()
        }
        val linked = requireNotNull(store.detail(document, at)).companies.single()
        assertEquals(at, linked.latestSnapshotAsOf)
        assertEquals(at.plusSeconds(1), linked.latestSnapshotCreatedAt)
    }

    @Test
    fun `title underscores and backslashes match literal characters`() {
        val literal = fixtures.document(at, title = "A_B\\C")
        fixtures.document(at, title = "AXB\\C")
        fixtures.document(at, title = "A_BC")
        assertEquals(listOf(literal), store.search(DocumentQuery(title = "A_B\\C"), at).items.map { it.id })
    }

    @Test
    fun `document pagination does not duplicate a source with multiple companies`() {
        val first = fixtures.document(at, title = "Guidance update")
        val second = fixtures.document(at, title = "Contract update")
        fixtures.link(first, fixtures.company("AAA"))
        fixtures.link(first, fixtures.company("BBB"))
        val page1 = store.search(DocumentQuery(from = at, to = at.plusSeconds(1), page = PageRequest(1)), at.plusSeconds(60))
        val page2 = store.search(DocumentQuery(from = at, to = at.plusSeconds(1), page = PageRequest(1, page1.nextCursor)), at.plusSeconds(60))
        assertEquals(setOf(first, second), (page1.items + page2.items).map { it.id }.toSet())
        assertEquals(2, page1.items.size + page2.items.size)
        assertNull(page2.nextCursor)
        assertNull(page1.window)
        assertEquals(listOf("AAA", "BBB"), (page1.items + page2.items).single { it.id == first }.tickers)
    }

    @Test
    fun `untracked linked sources are distinct from unresolved sources and recorded skipped sources`() {
        val unresolved = fixtures.document(at)
        val notTracked = fixtures.document(at)
        fixtures.link(notTracked, fixtures.company("AAA"))
        val skipped = fixtures.document(at)
        fixtures.processing(skipped, "SKIPPED", attempts = 1, updatedAt = at)
        val page = store.search(DocumentQuery(states = setOf(DocumentState.UNRESOLVED, DocumentState.NOT_TRACKED), from = at, to = at.plusSeconds(1)), at)
        assertEquals(mapOf(unresolved to DocumentState.UNRESOLVED, notTracked to DocumentState.NOT_TRACKED), page.items.associate { it.id to it.state })
        assertEquals(DocumentState.SKIPPED, store.detail(skipped, at)?.document?.state)
        assertFalse(requireNotNull(store.detail(skipped, at)).historyAvailable)
        assertEquals(1, store.detail(skipped, at)?.unrecordedAttemptCount)
    }

    @Test
    fun `filters use literal title normalized ticker provider and half open capture times`() {
        val match = fixtures.document(at, title = "Revenue +10%", provider = "finnhub")
        fixtures.link(match, fixtures.company("AAA"))
        fixtures.document(at, title = "Revenue +100", provider = "finnhub")
        fixtures.document(at.plusSeconds(1), title = "Revenue +10%", provider = "finnhub")
        fixtures.document(at, title = "Revenue +10%", provider = "polygon")
        assertEquals(listOf(match), store.search(DocumentQuery(title = "%", ticker = " aaa ", provider = "finnhub", from = at, to = at.plusSeconds(1)), at).items.map { it.id })
        assertEquals(2, store.search(DocumentQuery(provider = "finnhub", from = at, to = at.plusSeconds(1)), at).items.size)
    }

    @Test
    fun `body is fetched separately and truncates unicode by database characters`() {
        val id = fixtures.document(at, body = "😀".repeat(20001))
        val body = requireNotNull(store.body(id))
        assertEquals("😀".repeat(20000), body.text)
        assertEquals(20001, body.originalCharacters)
        assertTrue(body.truncated)
        assertFalse(requireNotNull(store.body(fixtures.document(at, body = "short"))).truncated)
        assertNull(store.body(UUID.randomUUID()))
        assertNull(store.detail(UUID.randomUUID(), at))
    }

    @Test
    fun `detail counts attempts models reports and distinct clusters without multiplication`() {
        val document = fixtures.document(at)
        val company = fixtures.company("AAA")
        fixtures.link(document, company)
        fixtures.link(document, fixtures.company("BBB"))
        fixtures.processing(document, "COMPLETED", attempts = 3, updatedAt = at)
        val run = fixtures.run(at)
        fixtures.attempt(document, run, 2, at)
        fixtures.attempt(document, run, 3, at)
        repeat(3) { fixtures.model(at, null, null, null, document = document) }
        val cluster = UUID.randomUUID()
        jdbc.sql("INSERT INTO event_clusters(id,company_id,event_type,first_seen_at) VALUES(:id,:company,'GUIDANCE_RAISE',:at::timestamptz)")
            .param("id", cluster).param("company", company).param("at", at.toString()).update()
        repeat(3) {
            jdbc.sql("""INSERT INTO events(id,company_id,source_document_id,cluster_id,event_type,family,direction,confidence,source_quality,expected_horizon,directness,discovered_at,taxonomy_version,extractor_version)
                VALUES(:id,:company,:document,:cluster,'GUIDANCE_RAISE','GUIDANCE','POSITIVE',0.9,'TIER1_NEWS','WEEKS','DIRECT',:at::timestamptz,'v1','v1')""")
                .param("id", UUID.randomUUID()).param("company", company).param("document", document)
                .param("cluster", if (it == 2) null else cluster).param("at", at.toString()).update()
        }
        val detail = requireNotNull(store.detail(document, at))
        assertEquals(3, detail.eventReports)
        assertEquals(1, detail.canonicalClusters)
        assertEquals(3, detail.modelCallsRecorded)
        assertEquals(2, detail.capturedAttemptCount)
        assertEquals(1, detail.unrecordedAttemptCount)
        assertTrue(detail.historyAvailable)
        assertEquals(2, detail.companiesTotal)
        assertEquals(listOf(document), store.search(DocumentQuery(runId = run), at).items.map { it.id })
        assertTrue(store.search(DocumentQuery(runId = UUID.randomUUID()), at).items.isEmpty())
        assertEquals(3, store.search(DocumentQuery(from = at, to = at.plusSeconds(1)), at).items.single().eventReports)
    }

    @Test
    fun `ticker and company associations are capped with explicit truncation`() {
        val document = fixtures.document(at)
        repeat(101) { fixtures.link(document, fixtures.company("A${it.toString().padStart(3, '0')}")) }
        val item = store.search(DocumentQuery(from = at, to = at.plusSeconds(1)), at).items.single()
        assertEquals(100, item.tickers.size)
        assertTrue(item.tickersTruncated)
        assertEquals("A000", item.tickers.first())
        assertEquals("A099", item.tickers.last())
        val detail = requireNotNull(store.detail(document, at))
        assertEquals(101, detail.companiesTotal)
        assertEquals(100, detail.companies.size)
        assertTrue(detail.companiesTruncated)
    }

    @Test
    fun `cursor is bound to the normalized filters`() {
        repeat(2) { fixtures.document(at, title = "10%") }
        val page = store.search(DocumentQuery(title = " 10% ", page = PageRequest(1)), at)
        assertEquals(1, store.search(DocumentQuery(title = "10%", page = PageRequest(1, page.nextCursor)), at).items.size)
        assertFailsWith<IllegalArgumentException> { store.search(DocumentQuery(title = "other", page = PageRequest(1, page.nextCursor)), at) }
    }
}
