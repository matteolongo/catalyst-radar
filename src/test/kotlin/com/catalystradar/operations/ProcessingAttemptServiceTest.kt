package com.catalystradar.operations

import com.catalystradar.application.ingestion.DocumentProcessingStatus
import com.catalystradar.application.operations.*
import com.catalystradar.application.pipeline.*
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.persistence.event.EventClusterStore
import com.catalystradar.domain.event.*
import com.catalystradar.application.clustering.EventClusterPlan
import com.catalystradar.ports.Embedding
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.document.DocumentProcessingStore
import com.catalystradar.persistence.operations.DocumentInspectionStore
import com.catalystradar.persistence.operations.ProcessingAttemptStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

/** Commits are intentional: rollback assertions must read the real committed state. */
class ProcessingAttemptServiceTest : PostgresIntegrationTest() {
    @Autowired private lateinit var store: ProcessingAttemptStore
    @Autowired private lateinit var processing: DocumentProcessingStore
    @Autowired private lateinit var inspection: DocumentInspectionStore
    @Autowired private lateinit var events: EventStore
    @Autowired private lateinit var clusters: EventClusterStore
    @Autowired private lateinit var metrics: CatalystMetrics
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcClient
    private val t0 = Instant.parse("2026-10-04T12:00:00Z")
    private val processingAt = t0.minusSeconds(86400)
    private val documentIds = mutableListOf<UUID>()
    private val runIds = mutableListOf<UUID>()
    private val companyIds = mutableListOf<UUID>()
    private val fixtures get() = OperationsFixtures(jdbc)
    private val service get() = ProcessingAttemptService(processing, store, Clock.fixed(t0, ZoneOffset.UTC), transactions)
    private val writer get() = DocumentOutcomePersistenceService(events, clusters, processing, metrics, transactions, store, Clock.fixed(t0, ZoneOffset.UTC))

    private fun document(attempts: Int = 0): UUID = fixtures.document(t0).also {
        documentIds += it
        fixtures.processing(it, "PENDING", attempts, updatedAt = processingAt)
    }
    private fun run(): UUID = fixtures.run(t0, "RUNNING").also { runIds += it }
    private fun history(id: UUID) = store.search(id, PageRequest(100), t0).items

    @AfterEach
    fun cleanup() {
        documentIds.forEach { id ->
            jdbc.sql("DELETE FROM model_runs WHERE source_document_id=:id").param("id", id).update()
            jdbc.sql("DELETE FROM operation_run_issues WHERE source_document_id=:id").param("id", id).update()
            jdbc.sql("DELETE FROM document_processing_attempts WHERE source_document_id=:id").param("id", id).update()
            jdbc.sql("DELETE FROM events WHERE source_document_id=:id").param("id", id).update()
        }
        companyIds.forEach { id -> jdbc.sql("DELETE FROM event_clusters WHERE company_id=:id").param("id", id).update() }
        documentIds.forEach { id ->
            jdbc.sql("DELETE FROM document_processing WHERE source_document_id=:id").param("id", id).update()
            jdbc.sql("DELETE FROM source_documents WHERE id=:id").param("id", id).update()
        }
        runIds.forEach { id -> jdbc.sql("DELETE FROM operation_runs WHERE id=:id").param("id", id).update() }
        companyIds.forEach { id -> jdbc.sql("DELETE FROM companies WHERE id=:id").param("id", id).update() }
    }

    @Test
    fun `begin uses global count and wall clock while recovering the previous attempt`() {
        val doc = document(4)
        val previous = fixtures.attempt(doc, run(), 4, t0.minusSeconds(10), "RUNNING")
        val attempt = service.begin(doc, run(), processingAt)
        assertEquals(5, attempt.number)
        assertEquals(processingAt, jdbc.sql("SELECT updated_at FROM document_processing WHERE source_document_id=:id").param("id", doc).query { rs, _ -> rs.getTimestamp(1).toInstant() }.single())
        val new = history(doc).first { it.id == attempt.id }
        assertEquals(t0, new.startedAt)
        val old = history(doc).first { it.id == previous }
        assertEquals(AttemptStatus.INTERRUPTED, old.status)
        assertEquals("UNFINISHED_PREVIOUS_RUN", old.errorCode)
        assertEquals(t0, old.updatedAt)
        assertNull(old.finishedAt)
        assertNull(old.durationMs)
        val detail = assertNotNull(inspection.detail(doc, t0, 8))
        assertEquals(3, detail.unrecordedAttemptCount)
        assertEquals(2, detail.capturedAttemptCount)
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "remote work after begin must be outside its transaction")
    }

    @Test
    fun `failed ledger insert rolls back increment and interruption`() {
        val doc = document(2)
        val previous = fixtures.attempt(doc, run(), 2, t0.minusSeconds(10), "RUNNING")
        assertFailsWith<RuntimeException> { service.begin(doc, UUID.randomUUID(), processingAt) }
        assertEquals("PENDING", fixtures.processingStatus(doc))
        assertEquals(2, processing.findBySourceDocumentId(doc)?.attemptCount)
        assertEquals("RUNNING", fixtures.attemptStatus(previous))
        assertEquals(1, history(doc).size)
    }

    @Test
    fun `retry failure atomically stores safe error and original retry timestamp`() {
        val doc = document()
        val attempt = service.begin(doc, run(), processingAt)
        val next = processingAt.plusSeconds(120)
        val outcome = service.fail(attempt, DocumentProcessingStatus.RETRYABLE_ERROR, "RATE_LIMITED", next, processingAt)
        assertEquals(DocumentProcessingStatus.RETRYABLE_ERROR, outcome.status)
        assertEquals("Provider rate limit; wait for the scheduled retry.", outcome.error)
        val latest = assertNotNull(processing.findBySourceDocumentId(doc))
        assertEquals(next, latest.nextAttemptAt)
        assertEquals(processingAt, jdbc.sql("SELECT updated_at FROM document_processing WHERE source_document_id=:id").param("id", doc).query { rs, _ -> rs.getTimestamp(1).toInstant() }.single())
        assertEquals(outcome.error, latest.lastErrorMessage)
        val captured = history(doc).single()
        assertEquals(AttemptStatus.RETRYABLE_ERROR, captured.status)
        assertEquals(next, captured.nextAttemptAt)
        assertEquals(t0, captured.finishedAt)
        assertEquals(0L, captured.durationMs)
    }

    @Test
    fun `terminal failure and invalid retry combinations preserve atomic state`() {
        val doc = document()
        val attempt = service.begin(doc, run(), processingAt)
        assertFailsWith<IllegalArgumentException> { service.fail(attempt, DocumentProcessingStatus.RETRYABLE_ERROR, "RATE_LIMITED", null, processingAt) }
        assertFailsWith<IllegalArgumentException> { service.fail(attempt, DocumentProcessingStatus.TERMINAL_ERROR, "PROCESSING_FAILURE", t0, processingAt) }
        assertEquals("PROCESSING", fixtures.processingStatus(doc))
        val outcome = service.fail(attempt, DocumentProcessingStatus.TERMINAL_ERROR, "PROCESSING_FAILURE", null, processingAt)
        assertEquals(DocumentProcessingStatus.TERMINAL_ERROR, outcome.status)
        assertEquals("Local document processing failed.", outcome.error)
        assertEquals("TERMINAL_ERROR", fixtures.attemptStatus(attempt.id))
        assertFailsWith<RuntimeException> { service.fail(attempt, DocumentProcessingStatus.RETRYABLE_ERROR, "RATE_LIMITED", t0, processingAt) }
        assertEquals("TERMINAL_ERROR", fixtures.processingStatus(doc))
        assertNull(processing.findBySourceDocumentId(doc)?.nextAttemptAt)
    }

    @Test
    fun `cancellation leaves source recoverable and cannot overwrite a finished attempt`() {
        val doc = document()
        val attempt = service.begin(doc, run(), processingAt)
        service.interrupt(attempt, "CANCELLED")
        assertEquals("PROCESSING", fixtures.processingStatus(doc))
        val captured = history(doc).single()
        assertEquals(AttemptStatus.INTERRUPTED, captured.status)
        assertEquals("CANCELLED", captured.errorCode)
        assertNull(captured.finishedAt)
        assertNull(captured.durationMs)
        assertFailsWith<RuntimeException> { store.finish(attempt.id, AttemptStatus.COMPLETED, 0, 0, null, null, t0) }
        assertEquals("INTERRUPTED", fixtures.attemptStatus(attempt.id))
    }

    @Test
    fun `successful and skipped persistence finish the matching attempt only`() {
        val doc = document()
        val other = document()
        val attempt = service.begin(doc, run(), processingAt)
        assertFailsWith<RuntimeException> { writer.persist(DocumentPersistencePlan(other, emptyList(), attempt.id), DocumentProcessingStatus.COMPLETED, processingAt) }
        assertEquals("PENDING", fixtures.processingStatus(other))
        assertEquals("RUNNING", fixtures.attemptStatus(attempt.id))
        writer.persist(DocumentPersistencePlan(doc, emptyList(), attempt.id), DocumentProcessingStatus.SKIPPED, processingAt)
        assertEquals("SKIPPED", fixtures.attemptStatus(attempt.id))
        assertEquals(processingAt, processing.findBySourceDocumentId(doc)?.completedAt)
        assertEquals(0, history(doc).single().eventsInserted)
        assertEquals(0, history(doc).single().eventsReused)
        val second = service.begin(doc, run(), processingAt)
        writer.persist(DocumentPersistencePlan(doc, emptyList(), second.id), DocumentProcessingStatus.COMPLETED, processingAt)
        assertEquals("COMPLETED", fixtures.attemptStatus(second.id))
        assertFailsWith<RuntimeException> { fixtures.model(t0, null, null, null, document = other, attempt = second.id) }
    }

    @Test
    fun `attempt history pages actual rows and bounds linked calls with explicit totals`() {
        val doc = document()
        val run = run()
        val ids = (1..26).map { fixtures.attempt(doc, run, it, t0) }
        repeat(101) { fixtures.model(t0, null, null, null, document = doc, attempt = ids.first()) }
        val first = store.search(doc, PageRequest(), t0)
        val second = store.search(doc, PageRequest(25, assertNotNull(first.nextCursor)), t0)
        val all = first.items + second.items
        assertEquals(26, all.map { it.id }.distinct().size)
        assertNull(second.nextCursor)
        val linked = all.first { it.id == ids.first() }
        assertEquals(100, linked.modelCallIds.size)
        assertEquals(101L, linked.modelCallsTotal)
        assertTrue(linked.modelCallsTruncated)
    }

    @Test
    fun `successful attempt counts reflect inserted and reused event rows`() {
        val doc = document()
        val company = fixtures.company("ZZAC").also { companyIds += it }
        val event = CatalystEvent(companyId = company, type = EventType.GUIDANCE_RAISE, direction = Direction.POSITIVE,
            confidence = 1.0, sourceQuality = SourceQuality.TIER1_NEWS, expectedHorizon = EventHorizon.WEEKS,
            directness = Directness.DIRECT, eventTimestamp = processingAt, discoveredAt = processingAt,
            taxonomyVersion = "taxonomy-v1", extractorVersion = "extractor-v1")
        val planned = PlannedEvent(event, "a".repeat(64), EventClusterPlan.OpenCluster(Embedding(List(1536) { 0f }, "fake")))
        val first = service.begin(doc, run(), processingAt)
        val inserted = writer.persist(DocumentPersistencePlan(doc, listOf(planned), first.id), DocumentProcessingStatus.COMPLETED, processingAt)
        assertEquals(1, inserted.eventsInserted)
        assertEquals(1, history(doc).first { it.id == first.id }.eventsInserted)
        val second = service.begin(doc, run(), processingAt)
        val reused = writer.persist(DocumentPersistencePlan(doc, listOf(planned), second.id), DocumentProcessingStatus.COMPLETED, processingAt)
        assertEquals(0, reused.eventsInserted)
        assertEquals(1, reused.eventsReused)
        assertEquals(0, history(doc).first { it.id == second.id }.eventsInserted)
        assertEquals(1, history(doc).first { it.id == second.id }.eventsReused)
        assertEquals(1, events.findByCompanyId(company).size)
    }
}
