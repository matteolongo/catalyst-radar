package com.catalystradar.operations

import com.catalystradar.application.catalyst.CatalystService
import com.catalystradar.application.event.EventNormalizationService
import com.catalystradar.application.ingestion.SourceDocumentRegistrationService
import com.catalystradar.application.operations.*
import com.catalystradar.application.pipeline.DocumentOutcomePersistenceService
import com.catalystradar.common.Versions
import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.*
import com.catalystradar.persistence.event.*
import com.catalystradar.persistence.extraction.ModelRunStore
import com.catalystradar.persistence.ingestion.IngestionRunStore
import com.catalystradar.persistence.operations.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit
import kotlin.test.*

@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = ["catalyst.internal.admin-key=e2e-admin", "catalyst.api.auth-enabled=false"])
class BackofficeE2ETest : PostgresIntegrationTest() {
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
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired @Qualifier("operationsClock") private lateinit var clock: Clock
    private lateinit var fixtures: OperationsFixtures
    private lateinit var pipelineFixture: RecordedPipelineFixture

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
    fun `an operator follows a retry into its final evidence`() = runTest {
        val t0 = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val company = companyStore.save(Company(ticker = "ZZEO", name = "Observability E2E"))
        val pipeline = pipelineFixture.build(company, t0, listOf("A raise story", "B raise story"), "A raise story")
        val first = assertNotNull(pipeline.runCycle(t0).runId)
        val origin = fixtures.firstIngestionFor(first)
        val sources = documentStore.search(DocumentQuery(ingestionRunId = origin), clock.instant(), emptySet()).items
        val sourceA = sources.single { it.title == "A raise story" }
        val sourceB = sources.single { it.title == "B raise story" }
        assertEquals(DocumentState.RETRYABLE_ERROR, sourceA.state)
        assertEquals(DocumentState.COMPLETED, sourceB.state)
        mockMvc.get("/internal/operations/overview") {
            header("X-Admin-Key", "e2e-admin")
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.queue.retrying") { value(1) }
        }

        val second = assertNotNull(pipeline.runCycle(t0.plusSeconds(60)).runId)
        val finalSources = documentStore.search(DocumentQuery(ingestionRunId = origin), clock.instant(), emptySet()).items
        assertEquals(setOf(sourceA.id, sourceB.id), finalSources.map { it.id }.toSet())
        assertEquals(2, finalSources.size)
        assertTrue(finalSources.all { it.state == DocumentState.COMPLETED && it.firstIngestionRunId == origin })
        assertTrue(sourceA.discoveredAt.isBefore(t0.plusSeconds(60)))
        val ledger = attemptStore.search(sourceA.id, PageRequest(), clock.instant()).items
        assertEquals(listOf(2, 1), ledger.map { it.number })
        assertEquals(2, ledger.map { it.id }.distinct().size)
        assertEquals(listOf(AttemptStatus.COMPLETED, AttemptStatus.RETRYABLE_ERROR), ledger.map { it.status })
        assertEquals(listOf(second, first), ledger.map { it.runId })
        assertEquals(1, attemptStore.search(sourceB.id, PageRequest(), clock.instant()).items.size)
        val calls = modelStore.search(ModelQuery(null, documentId = sourceA.id), clock.instant()).items
        val failed = calls.single { !it.success }
        assertEquals("RATE_LIMITED", failed.errorCode)
        assertEquals(ledger[1].id, failed.attemptId)
        assertEquals(first, failed.runId)
        assertTrue(calls.filter { it.success }.all { it.attemptId == ledger[0].id && it.runId == second })
        assertEquals(setOf("extract", "embed"), calls.filter { it.success }.map { it.operation }.toSet())

        mockMvc.get("/internal/operations/documents/${sourceA.id}/attempts") {
            header("X-Admin-Key", "e2e-admin")
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.items.length()") { value(2) }
            jsonPath("$.items[0].number") { value(2) }
            jsonPath("$.items[0].status") { value("COMPLETED") }
            jsonPath("$.items[0].id") { value(ledger[0].id.toString()) }
            jsonPath("$.items[1].status") { value("RETRYABLE_ERROR") }
        }
        // A retry is associated by activity even when discovery precedes this cycle.
        mockMvc.get("/internal/operations/documents?runId=$second") {
            header("X-Admin-Key", "e2e-admin")
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].id") { value(sourceA.id.toString()) }
            jsonPath("$.items[0].firstIngestionRunId") { value(origin.toString()) }
        }
        mockMvc.get("/internal/operations/model-runs/${failed.id}") {
            header("X-Admin-Key", "e2e-admin")
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.sourceDocumentId") { value(sourceA.id.toString()) }
            jsonPath("$.attemptId") { value(ledger[1].id.toString()) }
            jsonPath("$.runId") { value(first.toString()) }
        }
        mockMvc.get("/internal/operations/runs/$first/issues") {
            header("X-Admin-Key", "e2e-admin")
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.items[0].documentId") { value(sourceA.id.toString()) }
            jsonPath("$.items[0].errorCode") { value("RATE_LIMITED") }
            jsonPath("$.items[0].provider") { value("openai") }
        }
        mockMvc.get("/internal/operations/runs/$second") {
            header("X-Admin-Key", "e2e-admin")
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.run.status") { value("SUCCESS") }
            jsonPath("$.run.documentsCompleted") { value(1) }
            jsonPath("$.run.captureComplete") { value(true) }
        }
        mockMvc.get("/v1/companies/ZZEO/catalyst").andExpect {
            status { isOk() }
            jsonPath("$.scoreVersion") { value(Versions.SCORE_V1) }
            jsonPath("$.score") { exists() }
        }
    }

    @Test
    fun `a rate-limited ingestion issue identifies its news provider`() = runTest {
        val at = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val company = companyStore.save(Company(ticker = "ZZIP", name = "Ingestion Provider Issue"))
        val runId = assertNotNull(pipelineFixture.build(company, at, failIngestion = true).runCycle(at).runId)

        mockMvc.get("/internal/operations/runs/$runId/issues") {
            header("X-Admin-Key", "e2e-admin")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].phase") { value("INGESTION") }
            jsonPath("$.items[0].errorCode") { value("RATE_LIMITED") }
            jsonPath("$.items[0].provider") { value("polygon") }
        }
    }
}
