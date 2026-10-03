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
        jdbc.sql("DELETE FROM events WHERE company_id = :id").param("id", testCompanyId()).update()
        jdbc.sql("DELETE FROM event_clusters WHERE company_id = :id").param("id", testCompanyId()).update()
        jdbc.sql("DELETE FROM catalyst_snapshots WHERE company_id = :id").param("id", testCompanyId()).update()
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
        jdbc.sql("DELETE FROM companies WHERE ticker = :ticker").param("ticker", TICKER).update()
    }

    private fun testCompanyId(): UUID =
        jdbc.sql("SELECT id FROM companies WHERE ticker = :ticker")
            .param("ticker", TICKER)
            .query(UUID::class.java)
            .optional()
            .orElse(UUID(0, 0))

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
        extraction = PipelineServiceTest.TitleExtraction,
        normalization = normalization,
        clustering = EventClusteringService(
            embeddings = PipelineServiceTest.FakeEmbeddings,
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
