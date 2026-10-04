package com.catalystradar.operations

import com.catalystradar.application.ingestion.IngestionStatus
import com.catalystradar.application.operations.*
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.operations.OperationRunStore
import com.catalystradar.persistence.operations.ProcessingAttemptStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.*

@Transactional
class OperationsHistoryStoreTest : PostgresIntegrationTest() {
    @Autowired private lateinit var runs: OperationRunStore
    @Autowired private lateinit var attempts: ProcessingAttemptStore
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var mapper: ObjectMapper
    private val at = Instant.parse("2026-10-04T10:00:00Z")
    private val window = ActivityWindow(at, at.plusSeconds(60))
    private val fixtures get() = OperationsFixtures(jdbc)

    @Test
    fun `twenty six tied runs page without duplicates and cursors bind filters`() {
        val ids = List(26) { fixtures.run(at) }
        fixtures.run(at.minusSeconds(1))
        fixtures.run(window.to)
        fixtures.run(at, "FAILED")
        fixtures.run(at, kind = "DAILY_SNAPSHOTS")
        val query = RunQuery(window, OperationKind.PIPELINE, OperationStatus.SUCCESS)
        val first = runs.search(query, window.to)
        val token = assertNotNull(first.nextCursor)
        val second = runs.search(query.copy(page = PageRequest(25, token)), window.to)
        assertEquals(25, first.items.size)
        assertEquals(ids.map(UUID::toString).sortedDescending(), (first.items + second.items).map { it.id.toString() })
        assertNull(second.nextCursor)
        assertEquals(window, first.window)
        listOf(query.copy(kind = null), query.copy(status = null), query.copy(window = ActivityWindow(at.minusSeconds(1), window.to))).forEach {
            assertFailsWith<IllegalArgumentException> { runs.search(it.copy(page = PageRequest(25, token)), window.to) }
        }
    }

    @Test
    fun `detail uses real counters independent child counts and exact phase boundaries`() {
        val run = fixtures.run(at)
        jdbc.sql("""UPDATE operation_runs SET ingestion_finished_at=:ingestion::timestamptz,processing_finished_at=:processing::timestamptz,
            finished_at=:finish::timestamptz,documents_considered=7,documents_completed=4,documents_skipped=1,
            documents_retry_scheduled=1,documents_terminal_failures=1,events_inserted=3,events_reused=2,
            companies_considered=6,companies_rescored=5,companies_failed=1 WHERE id=:id""")
            .param("id", run).param("ingestion", at.plusSeconds(10).toString()).param("processing", at.plusSeconds(20).toString())
            .param("finish", at.plusSeconds(30).toString()).update()
        repeat(2) { ingestion(run) }
        val document = fixtures.document(at, body = "secret-body")
        jdbc.sql("UPDATE source_documents SET raw_payload=:payload::jsonb WHERE id=:id")
            .param("payload", "{\"text\":\"secret-raw-payload\"}").param("id", document).update()
        val attempt = fixtures.attempt(document, run, 1, at)
        repeat(4) { fixtures.model(at, null, null, null, document = document, attempt = attempt) }
        repeat(3) { issue(run, document) }
        val detail = assertNotNull(runs.detail(run, window.to))
        assertEquals(2L, detail.ingestionRuns)
        assertEquals(1L, detail.documentAttempts)
        assertEquals(3L, detail.issues)
        assertEquals(7, detail.run.documentsConsidered)
        assertEquals(4, detail.run.documentsCompleted)
        assertEquals(1, detail.run.documentsSkipped)
        assertEquals(1, detail.run.documentsRetryScheduled)
        assertEquals(1, detail.run.documentsTerminalFailures)
        assertEquals(3, detail.run.eventsInserted)
        assertEquals(2, detail.run.eventsReused)
        assertEquals(6, detail.run.companiesConsidered)
        assertEquals(5, detail.run.companiesRescored)
        assertEquals(1, detail.run.companiesFailed)
        assertEquals(30000L, detail.run.durationMs)
        assertFalse(detail.run.active)
        assertEquals(listOf(OperationPhaseTiming("INGESTION", at, at.plusSeconds(10), 10000),
            OperationPhaseTiming("PROCESSING", at.plusSeconds(10), at.plusSeconds(20), 10000),
            OperationPhaseTiming("SCORING", at.plusSeconds(20), at.plusSeconds(30), 10000)), detail.phases)
        assertFalse(mapper.writeValueAsString(detail).contains("secret"))
    }

    @Test
    fun `missing phase boundaries remain unknown and daily has only scoring`() {
        val pipeline = assertNotNull(runs.detail(fixtures.run(at, "FAILED"), window.to))
        assertEquals(listOf("INGESTION", "PROCESSING", "SCORING"), pipeline.phases.map { it.phase })
        assertTrue(pipeline.phases.all { it.durationMs == null })
        assertNull(pipeline.phases[1].startedAt)
        val daily = assertNotNull(runs.detail(fixtures.run(at, kind = "DAILY_SNAPSHOTS"), window.to))
        assertEquals(listOf(OperationPhaseTiming("SCORING", at, at.plusSeconds(1), 1000)), daily.phases)
        assertNull(runs.detail(UUID.randomUUID(), window.to))
    }

    @Test
    fun `issues page deterministically and retain explicit identities with safe errors`() {
        val run = fixtures.run(at)
        val other = fixtures.run(at)
        val document = fixtures.document(at)
        val company = fixtures.company("AAA")
        val ids = List(26) { issue(run, document, company) }
        issue(other, document)
        val first = runs.issues(run, PageRequest(), window.to)
        val token = assertNotNull(first.nextCursor)
        val second = runs.issues(run, PageRequest(25, token), window.to)
        assertEquals(ids.map(UUID::toString).sortedDescending(), (first.items + second.items).map { it.id.toString() })
        assertNull(second.nextCursor)
        first.items.forEach {
            assertEquals(document, it.documentId)
            assertEquals(company, it.companyId)
            assertEquals("AAA", it.ticker)
            assertEquals("SCORING_FAILURE", it.errorCode)
            assertEquals(OperationalErrors.message("SCORING_FAILURE"), it.errorMessage)
        }
        assertFailsWith<IllegalArgumentException> { runs.issues(other, PageRequest(25, token), window.to) }
        assertTrue(runs.issues(UUID.randomUUID(), PageRequest(), window.to).items.isEmpty())
    }

    @Test
    fun `attempt pages cap ordered model ids while preserving actual totals and empty calls`() {
        val run = fixtures.run(at)
        val document = fixtures.document(at, body = "secret-body")
        val ids = List(3) { fixtures.attempt(document, run, it + 1, at) }
        val earlier = fixtures.model(at.minusSeconds(30), null, null, null, document = document, attempt = ids[0])
        val calls = List(100) { fixtures.model(at, null, null, null, document = document, attempt = ids[0]) }
        fixtures.model(at, null, null, null, document = document)
        val first = attempts.search(document, PageRequest(2), window.to)
        val token = assertNotNull(first.nextCursor)
        val second = attempts.search(document, PageRequest(2, token), window.to)
        val items = first.items + second.items
        assertEquals(ids.map(UUID::toString).sortedDescending(), items.map { it.id.toString() })
        val recorded = items.single { it.id == ids[0] }
        assertEquals(101L, recorded.modelCallsTotal)
        assertTrue(recorded.modelCallsTruncated)
        assertEquals(listOf(earlier.toString()) + calls.map(UUID::toString).sorted().take(99), recorded.modelCallIds.map(UUID::toString))
        assertEquals(run, recorded.runId)
        assertEquals(document, recorded.documentId)
        items.filter { it.id != ids[0] }.forEach { assertEquals(0L, it.modelCallsTotal); assertFalse(it.modelCallsTruncated) }
        assertNull(second.nextCursor)
        assertFalse(mapper.writeValueAsString(items).contains("secret"))
        assertFailsWith<IllegalArgumentException> { attempts.search(UUID.randomUUID(), PageRequest(2, token), window.to) }
    }

    @Test
    fun `ingestion filters by explicit run identity and sanitizes legacy failures`() {
        val run = fixtures.run(at)
        val other = fixtures.run(at)
        val linked = ingestion(run)
        ingestion(other)
        val legacy = ingestion(null, "FAILED")
        val all = runs.ingestion(IngestionQuery(window), window.to)
        assertEquals(3, all.items.size)
        val scoped = runs.ingestion(IngestionQuery(null, provider = "polygon", status = IngestionStatus.SUCCESS, runId = run), window.to)
        assertEquals(listOf(linked), scoped.items.map { it.id })
        assertEquals(run, scoped.items.single().runId)
        assertEquals(6, scoped.items.single().fetched)
        assertEquals(2, scoped.items.single().added)
        assertEquals(4, scoped.items.single().duplicates)
        assertEquals(1000L, scoped.items.single().durationMs)
        val lookup = runs.ingestion(IngestionQuery(null, ingestionRunId = legacy), window.to).items.single()
        assertNull(lookup.runId)
        assertEquals("UNKNOWN_FAILURE", lookup.errorCode)
        assertEquals(OperationalErrors.message("UNKNOWN_FAILURE"), lookup.error)
        assertFalse(mapper.writeValueAsString(all).contains("secret"))
        assertTrue(runs.ingestion(IngestionQuery(null, ingestionRunId = UUID.randomUUID()), window.to).items.isEmpty())
        assertFailsWith<IllegalArgumentException> { runs.ingestion(IngestionQuery(null), window.to) }
    }

    @Test
    fun `ingestion cursor is bound to filters and pages tied timestamps`() {
        val run = fixtures.run(at)
        val ids = List(26) { ingestion(run) }
        val query = IngestionQuery(window, provider = "polygon", status = IngestionStatus.SUCCESS, runId = run)
        val first = runs.ingestion(query, window.to)
        val token = assertNotNull(first.nextCursor)
        val second = runs.ingestion(query.copy(page = PageRequest(25, token)), window.to)
        assertEquals(ids.map(UUID::toString).sortedDescending(), (first.items + second.items).map { it.id.toString() })
        assertNull(second.nextCursor)
        listOf(query.copy(provider = null), query.copy(status = null), query.copy(runId = null), query.copy(window = null)).forEach {
            assertFailsWith<IllegalArgumentException> { runs.ingestion(it.copy(page = PageRequest(25, token)), window.to) }
        }
    }

    private fun ingestion(run: UUID?, status: String = "SUCCESS"): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("""INSERT INTO ingestion_runs(id,provider,status,documents_fetched,documents_new,documents_duplicate,
            error_summary,operation_run_id,started_at,finished_at) VALUES(:id,'polygon',:status,6,2,4,'secret-payload',:run,:at::timestamptz,:finish::timestamptz)""")
            .param("id", id).param("status", status).param("run", run).param("at", at.toString()).param("finish", at.plusSeconds(1).toString()).update()
        return id
    }

    private fun issue(run: UUID, document: UUID, company: UUID? = null): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("""INSERT INTO operation_run_issues(id,operation_run_id,phase,source_document_id,company_id,error_code,error_message,created_at)
            VALUES(:id,:run,'SCORING',:document,:company,'SCORING_FAILURE','secret-payload',:at::timestamptz)""")
            .param("id", id).param("run", run).param("document", document).param("company", company).param("at", at.toString()).update()
        return id
    }
}
