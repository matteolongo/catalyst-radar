package com.catalystradar.operations

import com.catalystradar.application.catalyst.CatalystService
import com.catalystradar.application.event.EventNormalizationService
import com.catalystradar.application.ingestion.SourceDocumentRegistrationService
import com.catalystradar.application.operations.*
import com.catalystradar.application.pipeline.DocumentOutcomePersistenceService
import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.*
import com.catalystradar.persistence.event.*
import com.catalystradar.persistence.extraction.ModelRunStore
import com.catalystradar.persistence.ingestion.IngestionRunStore
import com.catalystradar.persistence.operations.*
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.persistence.extraction.ModelRunInput
import com.catalystradar.api.dto.toResponse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import kotlin.test.*

@Transactional
class PipelineObservabilityTest : PostgresIntegrationTest() {
    @Autowired private lateinit var companyStore: CompanyStore
    @Autowired private lateinit var registrations: SourceDocumentRegistrationService
    @Autowired private lateinit var ingestionRuns: IngestionRunStore
    @Autowired private lateinit var normalization: EventNormalizationService
    @Autowired private lateinit var clusters: EventClusterStore
    @Autowired private lateinit var events: EventStore
    @Autowired private lateinit var persistence: DocumentOutcomePersistenceService
    @Autowired private lateinit var catalyst: CatalystService
    @Autowired private lateinit var documents: SourceDocumentStore
    @Autowired private lateinit var documentCompanies: SourceDocumentCompanyStore
    @Autowired private lateinit var processing: DocumentProcessingStore
    @Autowired private lateinit var modelRuns: ModelRunStore
    @Autowired private lateinit var recorder: OperationRunRecorder
    @Autowired private lateinit var attempts: ProcessingAttemptService
    @Autowired private lateinit var documentStore: DocumentInspectionStore
    @Autowired private lateinit var attemptStore: ProcessingAttemptStore
    @Autowired private lateinit var modelStore: ModelInspectionStore
    @Autowired private lateinit var runStore: OperationRunStore
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired @Qualifier("operationsClock") private lateinit var clock: Clock
    private lateinit var fixtures: OperationsFixtures
    private lateinit var pipelineFixture: RecordedPipelineFixture
    private val t0 = Instant.parse("2026-10-04T12:00:00Z")

    @BeforeEach
    fun setup() {
        fixtures = OperationsFixtures(jdbc)
        pipelineFixture = RecordedPipelineFixture(
            companyStore = companyStore, registrations = registrations, ingestionRuns = ingestionRuns,
            normalization = normalization, clusters = clusters, events = events, persistence = persistence,
            catalyst = catalyst, documents = documents, documentCompanies = documentCompanies,
            processing = processing, modelRuns = modelRuns, recorder = recorder, attempts = attempts,
        )
    }

    @Test
    fun `a pipeline uses explicit IDs for every recorded model call`() = runTest {
        val company = companyStore.save(Company(ticker = "ZZOB", name = "Observability"))
        val result = pipelineFixture.build(company, t0).runCycle(t0)
        val runId = assertNotNull(result.runId)
        val source = documentStore.search(DocumentQuery(ingestionRunId = fixtures.firstIngestionFor(runId)), clock.instant(), emptySet()).items.single()
        val attempt = attemptStore.search(source.id, PageRequest(), clock.instant()).items.single()
        assertEquals(runId, attempt.runId)
        val calls = modelStore.search(ModelQuery(null, documentId = source.id), clock.instant()).items
        assertEquals(setOf("extract", "embed"), calls.map { it.operation }.toSet())
        assertTrue(calls.all { it.attemptId == attempt.id && it.runId == runId })
        val run = assertNotNull(runStore.detail(runId, clock.instant())).run
        assertEquals(OperationStatus.SUCCESS, run.status)
        assertEquals(OperationPhase.FINISHED, run.phase)
        assertTrue(run.captureComplete)
        assertEquals(1, run.documentsConsidered)
        assertEquals(1, run.documentsCompleted)
        assertEquals(1, run.eventsInserted)
        assertEquals(0, run.eventsReused)
        assertEquals(1, run.companiesConsidered)
        assertEquals(1, run.companiesRescored)
        assertFalse(recorder.isActive(runId))
        val legacy = modelRuns.findByDocument(source.id)
        assertTrue(legacy.all { it.runId == runId && it.toResponse().attemptId == attempt.id && it.toResponse().runId == runId })
        assertTrue(modelRuns.listRecent(100).filter { it.sourceDocumentId == source.id }.all { it.runId == runId })
    }

    @Test
    fun `a duplicate source retains its original ingestion and creates no attempt in the later cycle`() = runTest {
        val company = companyStore.save(Company(ticker = "ZZOB", name = "Observability"))
        val pipeline = pipelineFixture.build(company, t0)
        val first = assertNotNull(pipeline.runCycle(t0).runId)
        val ingestionId = fixtures.firstIngestionFor(first)
        val second = assertNotNull(pipeline.runCycle(t0.plusSeconds(1)).runId)
        val source = documentStore.search(DocumentQuery(ingestionRunId = ingestionId), clock.instant(), emptySet()).items.single()
        assertEquals(ingestionId, source.firstIngestionRunId)
        assertEquals(1, attemptStore.search(source.id, PageRequest(), clock.instant()).items.size)
        assertTrue(documentStore.search(DocumentQuery(runId = second), clock.instant(), emptySet()).items.isEmpty())
        val providerRun = ingestionRuns.findById(fixtures.firstIngestionFor(second))
        assertEquals(1, providerRun?.duplicates)
        assertEquals(0, runStore.detail(second, clock.instant())?.run?.documentsConsidered)
    }

    @Test
    fun `a retryable source failure does not prevent another document from completing`() = runTest {
        val company = companyStore.save(Company(ticker = "ZZOB", name = "Observability"))
        val result = pipelineFixture.build(company, t0, listOf("First raise story", "Second raise story"), "First raise story").runCycle(t0)
        val id = assertNotNull(result.runId)
        assertEquals("PARTIAL", result.status)
        val run = assertNotNull(runStore.detail(id, clock.instant())).run
        assertTrue(run.captureComplete)
        assertEquals(2, run.documentsConsidered)
        assertEquals(1, run.documentsRetryScheduled)
        assertEquals(1, run.documentsCompleted)
        assertEquals(1, run.eventsInserted)
        val issue = runStore.issues(id, PageRequest(), clock.instant()).items.single()
        assertEquals(IssuePhase.PROCESSING, issue.phase)
        assertEquals("RATE_LIMITED", issue.errorCode)
        val calls = documentStore.search(DocumentQuery(runId = id), clock.instant(), emptySet()).items.flatMap {
            modelStore.search(ModelQuery(null, documentId = it.id), clock.instant()).items
        }
        assertEquals(1, calls.count { !it.success })
        assertTrue(calls.all { it.runId == id && it.attemptId != null })
        assertEquals(AttemptStatus.RETRYABLE_ERROR, attemptStore.search(assertNotNull(issue.documentId), PageRequest(), clock.instant()).items.single().status)
    }

    @Test
    fun `a queued source without associated companies closes its skipped attempt without a model call`() = runTest {
        val source = documents.save(SourceDocument(provider = "polygon", title = "Unassociated", body = "Evidence", discoveredAt = t0))
        processing.ensurePending(source.id, t0)
        val company = companyStore.save(Company(ticker = "ZZOB", name = "Observability"))
        val result = pipelineFixture.build(company, t0, titles = emptyList()).runCycle(t0)
        assertEquals(1, result.documentsSkipped)
        val attempt = attemptStore.search(source.id, PageRequest(), clock.instant()).items.single()
        assertEquals(AttemptStatus.SKIPPED, attempt.status)
        assertEquals(result.runId, attempt.runId)
        assertEquals("SKIPPED", fixtures.processingStatus(source.id))
        assertTrue(modelStore.search(ModelQuery(null, documentId = source.id), clock.instant()).items.isEmpty())
    }

    @Test
    fun `standalone and replay style model records keep a null attempt and run`() {
        val source = documents.save(SourceDocument(provider = "polygon", title = "Replay", body = "Evidence", discoveredAt = t0))
        modelRuns.record(ModelRunInput(provider = "openai", operation = "extract", model = "fixture", sourceDocumentId = source.id, success = true))
        val record = modelRuns.findByDocument(source.id).single()
        assertNull(record.processingAttemptId)
        assertNull(record.runId)
        assertNull(record.toResponse().runId)
        val call = modelStore.search(ModelQuery(null, documentId = source.id), clock.instant()).items.single()
        assertNull(call.attemptId)
        assertNull(call.runId)
    }
}
