package com.catalystradar.application.pipeline

import com.catalystradar.application.catalyst.CatalystService
import com.catalystradar.application.clustering.DedupProperties
import com.catalystradar.application.clustering.EventClusteringService
import com.catalystradar.application.event.EventNormalizationService
import com.catalystradar.application.ingestion.DocumentProcessingStatus
import com.catalystradar.application.ingestion.IngestionProperties
import com.catalystradar.application.ingestion.IngestionService
import com.catalystradar.application.ingestion.SourceDocumentRegistrationService
import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.application.operations.ProcessingAttemptService
import com.catalystradar.application.operations.OperationRunRecorder
import com.catalystradar.operations.OperationsFixtures
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.DocumentProcessingStore
import com.catalystradar.persistence.document.SourceDocumentCompanyStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.event.EventClusterStore
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.persistence.ingestion.IngestionRunStore
import com.catalystradar.ports.NewsFetchRequest
import com.catalystradar.ports.NewsFetchResult
import com.catalystradar.ports.NewsProvider
import com.catalystradar.ports.*
import org.springframework.transaction.support.TransactionSynchronizationManager
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * One document's events, clusters, and final status must commit together.
 *
 * These tests deliberately skip the @Transactional fixture: an atomicity
 * bug is invisible when the assertions share a transaction with the writes
 * under test. Rows are removed again in [removeTestData] because this
 * context commits for real.
 */
class PipelineAtomicityTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var companyStore: CompanyStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Autowired
    private lateinit var documentCompanies: SourceDocumentCompanyStore

    @Autowired
    private lateinit var ingestionRuns: IngestionRunStore

    @Autowired
    private lateinit var registrations: SourceDocumentRegistrationService

@Autowired
    private lateinit var normalization: EventNormalizationService

    @Autowired
    private lateinit var catalyst: CatalystService

    @Autowired
    private lateinit var writer: DocumentOutcomePersistenceService

    @Autowired
    private lateinit var eventStore: EventStore

    @Autowired
    private lateinit var clusters: EventClusterStore

    @Autowired
    private lateinit var processing: DocumentProcessingStore

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var attempts: ProcessingAttemptService

    @Autowired private lateinit var recorder: OperationRunRecorder

    private val fixtures get() = OperationsFixtures(jdbc)
    private val fixtureDocuments = mutableListOf<UUID>()
    private val fixtureRuns = mutableListOf<UUID>()

    @MockitoSpyBean
    private lateinit var processingSpy: DocumentProcessingStore

    private val meterRegistry = SimpleMeterRegistry()

    private val metrics = CatalystMetrics(meterRegistry)

    /**
     * The writer under test is the autowired bean, so its counters land in
     * the application registry, not a test-local one. Deltas keep the
     * assertion correct even when a shared context ran other tests first.
     */
    @Autowired
    private lateinit var applicationMeters: MeterRegistry

    private fun extractedEventCount(): Double =
        applicationMeters.find("catalyst_events_total").counters().sumOf { it.count() }

    @AfterEach
    fun removeTestData() {
        val ownedDocuments = fixtureDocuments + jdbc.sql("SELECT source_document_id FROM source_document_companies WHERE company_id=:id")
            .param("id", testCompanyId()).query(UUID::class.java).list()
        val ingestionIds = ownedDocuments.flatMap { id ->
            jdbc.sql("SELECT first_ingestion_run_id FROM source_documents WHERE id=:id AND first_ingestion_run_id IS NOT NULL")
                .param("id", id).query(UUID::class.java).list()
        }.distinct()
        val operationIds = (fixtureRuns + ownedDocuments.flatMap { id ->
            jdbc.sql("SELECT DISTINCT operation_run_id FROM document_processing_attempts WHERE source_document_id=:id")
                .param("id", id).query(UUID::class.java).list()
        } + ingestionIds.flatMap { id ->
            jdbc.sql("SELECT operation_run_id FROM ingestion_runs WHERE id=:id AND operation_run_id IS NOT NULL")
                .param("id", id).query(UUID::class.java).list()
        }).distinct()
        ownedDocuments.forEach { id ->
            jdbc.sql("DELETE FROM model_runs WHERE source_document_id=:id").param("id", id).update()
            jdbc.sql("DELETE FROM operation_run_issues WHERE source_document_id=:id").param("id", id).update()
        }
        jdbc.sql("DELETE FROM operation_run_issues WHERE company_id=:id").param("id", testCompanyId()).update()
        operationIds.forEach { id -> jdbc.sql("DELETE FROM operation_run_issues WHERE operation_run_id=:id").param("id", id).update() }
        ownedDocuments.forEach { id -> jdbc.sql("DELETE FROM document_processing_attempts WHERE source_document_id=:id").param("id", id).update() }
        jdbc.sql("DELETE FROM events WHERE company_id = :id").param("id", testCompanyId()).update()
        jdbc.sql("DELETE FROM event_clusters WHERE company_id = :id").param("id", testCompanyId()).update()
        jdbc.sql("DELETE FROM catalyst_snapshots WHERE company_id = :id").param("id", testCompanyId()).update()
        jdbc.sql("DELETE FROM state_transitions WHERE company_id = :id").param("id", testCompanyId()).update()
        jdbc.sql(
            """
            DELETE FROM document_processing WHERE source_document_id IN (
              SELECT c.source_document_id FROM source_document_companies c
              JOIN companies co ON co.id = c.company_id WHERE co.ticker = :ticker
            )
            """,
        ).param("ticker", TICKER).update()
        jdbc.sql(
            """
            DELETE FROM source_document_companies WHERE company_id IN (
              SELECT id FROM companies WHERE ticker = :ticker
            )
            """,
        ).param("ticker", TICKER).update()
        ownedDocuments.forEach { id ->
            jdbc.sql("DELETE FROM document_processing WHERE source_document_id=:id").param("id", id).update()
            jdbc.sql("DELETE FROM source_document_companies WHERE source_document_id=:id").param("id", id).update()
            jdbc.sql("DELETE FROM source_documents WHERE id=:id").param("id", id).update()
        }
        ingestionIds.forEach { id -> jdbc.sql("DELETE FROM ingestion_runs WHERE id=:id").param("id", id).update() }
        operationIds.forEach { id ->
            jdbc.sql("DELETE FROM ingestion_runs WHERE operation_run_id=:id").param("id", id).update()
            jdbc.sql("DELETE FROM operation_runs WHERE id=:id").param("id", id).update()
        }
        jdbc.sql("DELETE FROM company_aliases WHERE company_id=:id").param("id", testCompanyId()).update()
        jdbc.sql("DELETE FROM companies WHERE ticker = :ticker").param("ticker", TICKER).update()
    }

    private fun testCompanyId(): UUID =
        jdbc.sql("SELECT id FROM companies WHERE ticker = :ticker")
            .param("ticker", TICKER)
            .query(UUID::class.java)
            .optional()
            .orElse(UUID(0, 0))

    @Test
    fun `successful attempt closure rolls back with document persistence`() {
        val t0 = Instant.parse("2026-10-04T12:00:00Z")
        val doc = fixtures.document(t0).also { fixtureDocuments += it }
        fixtures.processing(doc, "PENDING", updatedAt = t0)
        val run = fixtures.run(t0, status = "RUNNING").also { fixtureRuns += it }
        val attempt = attempts.begin(doc, run, t0)
        doThrow(IllegalStateException("processing store unavailable"))
            .whenever(processingSpy).markCompleted(any(), any())
        assertFailsWith<RuntimeException> {
            writer.persist(DocumentPersistencePlan(doc, emptyList(), processingAttemptId = attempt.id), DocumentProcessingStatus.COMPLETED, t0)
        }
        assertEquals("RUNNING", fixtures.attemptStatus(attempt.id))
        assertEquals("PROCESSING", fixtures.processingStatus(doc))
    }

@Test
    fun `writes no event or cluster rows when the document outcome cannot be finalized`() = runTest {
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val company = companyStore.save(Company(ticker = TICKER, name = "Atomicity"))
        val document = documents.save(
            SourceDocument(
                provider = "polygon",
                title = "Atomicity raise story",
                body = "Atomicity raised its outlook.",
                discoveredAt = now,
            ),
        )
        documentCompanies.link(document.id, company.id)
        processing.ensurePending(document.id, now)
        val extractedBefore = extractedEventCount()
        doThrow(IllegalStateException("processing store unavailable"))
            .whenever(processingSpy)
            .markCompleted(any(), any())

        val result = pipeline().runCycle(now)

        assertEquals(DocumentProcessingStatus.TERMINAL_ERROR, processing.findBySourceDocumentId(document.id)?.status)
        assertEquals(emptyList(), eventStore.findByCompanyId(company.id))
        assertEquals(0, clusterCount(company.id))
        assertEquals(0, result.eventsInserted)
        assertEquals(0, result.documentsCompleted)
        assertEquals("TERMINAL_ERROR", jdbc.sql("SELECT status FROM document_processing_attempts WHERE source_document_id=:id")
            .param("id", document.id).query(String::class.java).single())
        assertEquals(
            extractedBefore,
            extractedEventCount(),
            "a rolled back document must never be reported as extracted",
        )
    }

    @Test
    fun `commits the same document fully when the outcome finalizes`() = runTest {
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val company = companyStore.save(Company(ticker = TICKER, name = "Atomicity"))
        val document = documents.save(
            SourceDocument(
                provider = "polygon",
                title = "Atomicity raise story",
                body = "Atomicity raised its outlook.",
                discoveredAt = now,
            ),
        )
        documentCompanies.link(document.id, company.id)
        processing.ensurePending(document.id, now)
        val extractedBefore = extractedEventCount()

        val result = pipeline().runCycle(now)

        assertEquals(DocumentProcessingStatus.COMPLETED, processing.findBySourceDocumentId(document.id)?.status)
        assertEquals(1, eventStore.findByCompanyId(company.id).size)
        assertEquals(1, clusterCount(company.id))
        assertEquals(1, result.eventsInserted)
        assertEquals(1, result.documentsCompleted)
        assertEquals("COMPLETED", jdbc.sql("SELECT status FROM document_processing_attempts WHERE source_document_id=:id")
            .param("id", document.id).query(String::class.java).single())
        assertEquals(1.0, extractedEventCount() - extractedBefore)
    }

    private fun clusterCount(companyId: UUID): Int =
        jdbc.sql("SELECT count(*) FROM event_clusters WHERE company_id = :id")
            .param("id", companyId)
            .query(Int::class.java)
            .single()

    private fun pipeline() = PipelineService(
        ingestion = IngestionService(
            providers = listOf(NoNewsProvider),
            properties = IngestionProperties(provider = NoNewsProvider.name, fallbackProvider = "none"),
            companies = companyStore,
            registrations = registrations,
            runs = ingestionRuns,
            metrics = metrics,
        ),
        extraction = object : EventExtractionProvider {
            override suspend fun extract(request: ExtractionRequest): ExtractionResult {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "extraction must be outside a transaction")
                assertNotNull(request.processingAttemptId)
                return PipelineServiceTest.TitleExtraction.extract(request)
            }
        },
        normalization = normalization,
        clustering = EventClusteringService(
            embeddings = object : EmbeddingProvider {
                override val model = PipelineServiceTest.FakeEmbeddings.model
                override suspend fun embed(text: String) = PipelineServiceTest.FakeEmbeddings.embed(text)
                override suspend fun embed(request: EmbeddingRequest): Embedding {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "embedding must be outside a transaction")
                    assertNotNull(request.sourceDocumentId)
                    assertNotNull(request.processingAttemptId)
                    return embed(request.text)
                }
            },
            clusters = clusters,
            events = eventStore,
            companies = companyStore,
            properties = DedupProperties(),
        ),
catalyst = catalyst,
        persistence = writer,
        documents = documents,
        documentCompanies = documentCompanies,
        processing = processingSpy,
        companies = companyStore,
        properties = PipelineProperties(),
        metrics = metrics,
        recorder = recorder,
        attempts = attempts,
    )

    object NoNewsProvider : NewsProvider {
        override val name: String = "empty"

        override suspend fun fetch(request: NewsFetchRequest): NewsFetchResult =
            NewsFetchResult(articles = emptyList(), nextCursor = null)
    }

    companion object {
        const val TICKER = "ZZAT"
    }
}
