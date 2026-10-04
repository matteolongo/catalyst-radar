package com.catalystradar.application.pipeline

import com.catalystradar.adapters.polygon.PolygonNewsProvider
import com.catalystradar.adapters.polygon.PolygonProperties
import com.catalystradar.application.catalyst.CatalystService
import com.catalystradar.application.operations.*
import com.catalystradar.persistence.operations.OperationRunStore
import com.catalystradar.persistence.operations.ProcessingAttemptStore
import com.catalystradar.application.clustering.EventClusteringService
import com.catalystradar.application.clustering.DedupProperties
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.EventEvidence
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.application.event.EventNormalizationService
import com.catalystradar.application.extraction.ExtractionValidator
import com.catalystradar.application.ingestion.IngestionProperties
import com.catalystradar.application.ingestion.IngestionService
import com.catalystradar.application.ingestion.DocumentProcessingStatus
import com.catalystradar.application.ingestion.IngestionStatus
import com.catalystradar.application.ingestion.SourceDocumentRegistrationService
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.application.company.CompanyService
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.catalyst.CatalystSnapshotStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.document.DocumentProcessingStore
import com.catalystradar.persistence.document.SourceDocumentCompanyStore
import com.catalystradar.persistence.event.EventClusterStore
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.persistence.ingestion.IngestionRunStore
import com.catalystradar.ports.Embedding
import com.catalystradar.ports.EmbeddingProvider
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.ports.ExtractedEvent
import com.catalystradar.ports.ExtractionRequest
import com.catalystradar.ports.ExtractionResult
import com.catalystradar.ports.NewsProvider
import com.catalystradar.ports.NewsFetchRequest
import com.catalystradar.ports.NewsFetchResult
import com.catalystradar.ports.ProviderException
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.spy
import org.mockito.kotlin.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

@Transactional
class PipelineServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var companies: CompanyService

    @Autowired
    private lateinit var companyStore: CompanyStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Autowired
    private lateinit var documentCompanies: SourceDocumentCompanyStore

    @Autowired
    private lateinit var processing: DocumentProcessingStore

    @Autowired
    private lateinit var registrations: SourceDocumentRegistrationService

    @Autowired
    private lateinit var runs: IngestionRunStore

    @Autowired
    private lateinit var events: EventStore

    @Autowired
    private lateinit var clusters: EventClusterStore

    @Autowired
    private lateinit var snapshots: CatalystSnapshotStore

    @Autowired
    private lateinit var validator: ExtractionValidator

    @Autowired
    private lateinit var normalization: EventNormalizationService

    @Autowired
    private lateinit var catalyst: CatalystService

    @Autowired
    private lateinit var persistence: DocumentOutcomePersistenceService

    @Autowired private lateinit var recorder: OperationRunRecorder
    @Autowired private lateinit var attempts: ProcessingAttemptService
    @Autowired private lateinit var operationRuns: OperationRunStore
    @Autowired private lateinit var attemptStore: ProcessingAttemptStore

    @Autowired
    private lateinit var jdbc: JdbcClient

    private val meterRegistry = SimpleMeterRegistry()
    private val metrics = CatalystMetrics(meterRegistry)

    private fun pipeline(
        providers: List<NewsProvider> = listOf(polygon()),
        extraction: EventExtractionProvider = TitleExtraction,
        ingestionProperties: IngestionProperties = IngestionProperties(),
        pipelineProperties: PipelineProperties = PipelineProperties(),
        catalyst: CatalystService = this.catalyst,
        recorder: OperationRunRecorder = this.recorder,
    ) = PipelineService(
        ingestion = IngestionService(
            providers = providers,
            properties = ingestionProperties,
            companies = companyStore,
            registrations = registrations,
            runs = runs,
            metrics = metrics,
        ),
        extraction = extraction,
        normalization = normalization,
        clustering = EventClusteringService(
            embeddings = FakeEmbeddings,
            clusters = clusters,
            events = events,
            companies = companyStore,
            properties = DedupProperties(),
        ),
        catalyst = catalyst,
        persistence = persistence,
        documents = documents,
        documentCompanies = documentCompanies,
        processing = processing,
        companies = companyStore,
        properties = pipelineProperties,
        metrics = metrics,
        recorder = recorder,
        attempts = attempts,
    )

    private fun polygon() = PolygonNewsProvider(
        RestClient.builder(),
        PolygonProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
    )

    /** A pipeline over already-queued documents, with no provider traffic. */
    private fun drainedPipeline(
        extraction: EventExtractionProvider = TitleExtraction,
        catalyst: CatalystService = this.catalyst,
    ) = pipeline(
        providers = listOf(NoNewsProvider),
        extraction = extraction,
        catalyst = catalyst,
        ingestionProperties = IngestionProperties(provider = NoNewsProvider.name, fallbackProvider = "none"),
    )

    private fun queuedDocument(company: Company, now: Instant) =
        documents.save(
            SourceDocument(
                provider = "polygon",
                title = "Dell raise story",
                body = "Dell raised its outlook.",
                discoveredAt = now,
            ),
        ).also {
            documentCompanies.link(it.id, company.id)
            processing.ensurePending(it.id, now)
        }

    /** Simulates a run interrupted before it could finalize its outcome. */
    private fun resetToPending(sourceDocumentId: UUID) {
        jdbc.sql(
            "UPDATE document_processing SET status = 'PENDING', next_attempt_at = NULL WHERE source_document_id = :id",
        ).param("id", sourceDocumentId).update()
        assertEquals(DocumentProcessingStatus.PENDING, processing.findBySourceDocumentId(sourceDocumentId)?.status)
    }

    /** Simulates a process killed mid-document, leaving the row in flight. */
    private fun leaveInProcessing(sourceDocumentId: UUID, now: Instant) {
        jdbc.sql(
            """
            UPDATE document_processing
            SET status = 'PROCESSING', next_attempt_at = NULL, updated_at = :now::timestamptz
            WHERE source_document_id = :id
            """,
        ).param("id", sourceDocumentId).param("now", now.toString()).update()
        assertEquals(DocumentProcessingStatus.PROCESSING, processing.findBySourceDocumentId(sourceDocumentId)?.status)
    }

    @Test
    fun `runs news to snapshot end to end`() = runTest {
        companyStore.save(Company(ticker = "DELL", name = "Dell"))
        val published = Instant.now().minusSeconds(3600).toString()
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(newsPage(published))),
        )

        val result = pipeline().runCycle()

        assertEquals("SUCCESS", result.status)
        assertEquals(4, result.documentsProcessed)
        assertEquals(4, result.eventsExtracted)
        assertEquals(1, result.companiesRescored)
        val snapshot = snapshots.latestSnapshot(companies.findByTicker("DELL")!!.id)
        assertEquals(CatalystState.BUILDING, snapshot?.state)
    }

    @Test
    fun `processes a due document even when ingestion fetches nothing new`() = runTest {
        val company = companyStore.save(Company(ticker = "DELL", name = "Dell"))
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val document = documents.save(
            SourceDocument(
                provider = "polygon",
                title = "Dell raise story",
                body = "Dell raised its outlook.",
                discoveredAt = now,
            ),
        )
        documentCompanies.link(document.id, company.id)
        processing.ensurePending(document.id, now)

        val result = pipeline(
            providers = listOf(NoNewsProvider),
            ingestionProperties = IngestionProperties(provider = NoNewsProvider.name, fallbackProvider = "none"),
        ).runCycle(now)

        assertEquals("SUCCESS", result.status)
        assertEquals(1, result.documentsProcessed)
        assertEquals(DocumentProcessingStatus.COMPLETED, processing.findBySourceDocumentId(document.id)?.status)
    }

    @Test
    fun `retries a transient extraction failure without reingesting the document`() = runTest {
        val company = companyStore.save(Company(ticker = "DELL", name = "Dell"))
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val document = documents.save(
            SourceDocument(
                provider = "polygon",
                title = "Dell raise story",
                body = "Dell raised its outlook.",
                discoveredAt = now,
            ),
        )
        documentCompanies.link(document.id, company.id)
        processing.ensurePending(document.id, now)
        var unavailable = true
        val flakyExtraction = object : EventExtractionProvider {
            override suspend fun extract(request: ExtractionRequest): ExtractionResult {
                if (unavailable) throw ProviderException.TemporaryUnavailable("timeout")
                return TitleExtraction.extract(request)
            }
        }
        val pipeline = pipeline(
            providers = listOf(NoNewsProvider),
            extraction = flakyExtraction,
            ingestionProperties = IngestionProperties(provider = NoNewsProvider.name, fallbackProvider = "none"),
            pipelineProperties = PipelineProperties(retryDelay = Duration.ofSeconds(10)),
        )

        val first = pipeline.runCycle(now)

        assertEquals("PARTIAL", first.status)
        assertEquals(1, first.documentsRetryScheduled)
        assertEquals(DocumentProcessingStatus.RETRYABLE_ERROR, processing.findBySourceDocumentId(document.id)?.status)
        assertEquals(
            1.0,
            meterRegistry.counter(
                "catalyst_document_processing_outcomes_total",
                "status",
                "retryable_error",
            ).count(),
        )
        assertEquals(
            1.0,
            meterRegistry.counter("catalyst_document_processing_retry_attempts_total").count(),
        )
        unavailable = false

        val second = pipeline.runCycle(now.plusSeconds(10))

        assertEquals("SUCCESS", second.status)
        assertEquals(1, second.documentsProcessed)
        assertEquals(DocumentProcessingStatus.COMPLETED, processing.findBySourceDocumentId(document.id)?.status)
        assertEquals(1, events.findByCompanyId(company.id).size)
    }

    @Test
    fun `marks invalid extraction output terminal instead of retrying it`() = runTest {
        val company = companyStore.save(Company(ticker = "DELL", name = "Dell"))
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val document = documents.save(
            SourceDocument(
                provider = "polygon",
                title = "Dell raise story",
                body = "Dell raised its outlook.",
                discoveredAt = now,
            ),
        )
        documentCompanies.link(document.id, company.id)
        processing.ensurePending(document.id, now)
        val invalidExtraction = object : EventExtractionProvider {
            override suspend fun extract(request: ExtractionRequest): ExtractionResult =
                throw ProviderException.InvalidResponse("malformed response")
        }

        val result = pipeline(
            providers = listOf(NoNewsProvider),
            extraction = invalidExtraction,
            ingestionProperties = IngestionProperties(provider = NoNewsProvider.name, fallbackProvider = "none"),
        ).runCycle(now)

        assertEquals("PARTIAL", result.status)
        assertEquals(1, result.documentsTerminalFailures)
        assertEquals(DocumentProcessingStatus.TERMINAL_ERROR, processing.findBySourceDocumentId(document.id)?.status)
        assertEquals(emptyList(), processing.findDue(now.plusSeconds(60), limit = 10))
    }

    @Test
    fun `marks irrelevant documents skipped and does not process them again`() = runTest {
        val company = companyStore.save(Company(ticker = "DELL", name = "Dell"))
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val document = documents.save(
            SourceDocument(
                provider = "polygon",
                title = "Dell generic news",
                body = "No catalyst information.",
                discoveredAt = now,
            ),
        )
        documentCompanies.link(document.id, company.id)
        processing.ensurePending(document.id, now)
        val irrelevantExtraction = object : EventExtractionProvider {
            override suspend fun extract(request: ExtractionRequest): ExtractionResult =
                ExtractionResult(documentRelevant = false, events = emptyList())
        }
        val pipeline = pipeline(
            providers = listOf(NoNewsProvider),
            extraction = irrelevantExtraction,
            ingestionProperties = IngestionProperties(provider = NoNewsProvider.name, fallbackProvider = "none"),
        )

        val first = pipeline.runCycle(now)
        val second = pipeline.runCycle(now.plusSeconds(60))

        assertEquals("SUCCESS", first.status)
        assertEquals(1, first.documentsSkipped)
        assertEquals(DocumentProcessingStatus.SKIPPED, processing.findBySourceDocumentId(document.id)?.status)
        assertEquals(0, second.documentsProcessed)
    }

    @Test
    fun `extracts nothing for a retained document that names no configured company`() = runTest {
        companyStore.save(Company(ticker = "DELL", name = "Dell"))
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news")).willReturn(okJson(UNRELATED_TICKER_PAGE)),
        )
        val countingExtraction = CountingExtraction()

        val result = pipeline(extraction = countingExtraction).runCycle(Instant.parse("2026-09-16T15:00:00Z"))

        assertEquals("SUCCESS", result.status)
        assertEquals(0, result.documentsConsidered)
        assertEquals(0, countingExtraction.calls, "an unresolvable document is never sent to the model")
        assertTrue(
            documents.findByProviderAndProviderDocumentId("polygon", "poly-unrelated") != null,
            "the article stays available for audit",
        )
    }

    @Test
    fun `reports failed when every provider fails and no queued document completes`() = runTest {
        companyStore.save(Company(ticker = "DELL", name = "Dell"))

        val result = pipeline(providers = emptyList()).runCycle(Instant.parse("2026-09-16T10:00:00Z"))

        assertEquals("FAILED", result.status)
    }

    @Test
    fun `counts a first processing as inserted events and a completed document`() = runTest {
        val company = companyStore.save(Company(ticker = "DELL", name = "Dell"))
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val document = queuedDocument(company, now)

        val result = drainedPipeline().runCycle(now)

        assertEquals(1, result.documentsConsidered)
        assertEquals(1, result.documentsCompleted)
        assertEquals(1, result.eventsInserted)
        assertEquals(0, result.eventsReused)
        assertEquals(1, result.eventsExtracted)
        assertEquals(1, result.companiesRescored)
        assertEquals(DocumentProcessingStatus.COMPLETED, processing.findBySourceDocumentId(document.id)?.status)
    }

    @Test
    fun `counts a reprocessed document as reused events instead of a second event`() = runTest {
        val company = companyStore.save(Company(ticker = "DELL", name = "Dell"))
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val document = queuedDocument(company, now)

        val first = drainedPipeline().runCycle(now)
        // An interrupted run can leave a row due again; reprocessing must
        // reuse the stored events instead of adding catalyst impact.
        resetToPending(document.id)
        val second = drainedPipeline().runCycle(now.plusSeconds(60))

        assertEquals(1, first.eventsInserted)
        assertEquals(0, first.eventsReused)
        assertEquals(1, second.documentsConsidered)
        assertEquals(1, second.documentsCompleted)
        assertEquals(0, second.eventsInserted)
        assertEquals(1, second.eventsReused)
        assertEquals(1, second.eventsExtracted)
        assertEquals(1, events.findByCompanyId(company.id).size)
        assertEquals(1, clusters.findWithinWindow(
            company.id,
            EventType.GUIDANCE_RAISE,
            Instant.parse("2026-09-01T00:00:00Z"),
            now.plusSeconds(60),
            limit = 10,
        ).size)
    }

    @Test
    fun `picks up a document left processing by a killed run without multiplying score impact`() = runTest {
        val company = companyStore.save(Company(ticker = "DELL", name = "Dell"))
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val document = queuedDocument(company, now)

        drainedPipeline().runCycle(now)
        val scoreAfterFirstRun = snapshots.latestSnapshot(company.id)?.score?.value
        leaveInProcessing(document.id, now)
        val due = processing.findDue(now, limit = 10).map { it.sourceDocumentId }

        assertTrue(due.contains(document.id), "an interrupted document must still be due")
        // Recalculated at the same instant, so an unchanged score proves the
        // rerun added no second event behind it.
        val second = drainedPipeline().runCycle(now)

        assertEquals(1, second.documentsConsidered)
        assertEquals(1, second.documentsCompleted)
        assertEquals(0, second.eventsInserted)
        assertEquals(1, second.eventsReused)
        assertEquals(1, events.findByCompanyId(company.id).size)
        assertEquals(1, clusters.findWithinWindow(
            company.id,
            EventType.GUIDANCE_RAISE,
            Instant.parse("2026-09-01T00:00:00Z"),
            now,
            limit = 10,
        ).size)
        assertEquals(scoreAfterFirstRun, snapshots.latestSnapshot(company.id)?.score?.value)
    }

    @Test
    fun `counts a skipped document as considered but not completed`() = runTest {
        val company = companyStore.save(Company(ticker = "DELL", name = "Dell"))
        val now = Instant.parse("2026-09-16T10:00:00Z")
        queuedDocument(company, now)
        val irrelevantExtraction = object : EventExtractionProvider {
            override suspend fun extract(request: ExtractionRequest): ExtractionResult =
                ExtractionResult(documentRelevant = false, events = emptyList())
        }

        val result = drainedPipeline(extraction = irrelevantExtraction).runCycle(now)

        assertEquals(1, result.documentsConsidered)
        assertEquals(0, result.documentsCompleted)
        assertEquals(1, result.documentsSkipped)
        assertEquals(0, result.eventsInserted)
        assertEquals(0, result.eventsReused)
        assertEquals(0, result.eventsExtracted)
        assertEquals(0, result.companiesRescored)
    }

    @Test
    fun `does not count a company as rescored when recalculation fails`() = runTest {
        val company = companyStore.save(Company(ticker = "DELL", name = "Dell"))
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val document = queuedDocument(company, now)
        val failingCatalyst = mock<CatalystService>()
        whenever(failingCatalyst.recalculate(eq(company.id), any()))
            .thenThrow(IllegalStateException("snapshot store unavailable"))

        val result = drainedPipeline(catalyst = failingCatalyst).runCycle(now)

        assertEquals(1, result.documentsCompleted)
        assertEquals(1, result.eventsInserted)
        assertEquals(0, result.companiesRescored)
        assertEquals("PARTIAL", result.status)
        val id = assertNotNull(result.runId)
        val run = assertNotNull(operationRuns.detail(id, Instant.now())).run
        assertEquals(1, run.companiesConsidered)
        assertEquals(1, run.companiesFailed)
        assertTrue(run.captureComplete)
        assertEquals("SCORING_FAILURE", run.errorCode)
        val issue = operationRuns.issues(id, PageRequest(), Instant.now()).items.single()
        assertEquals(IssuePhase.SCORING, issue.phase)
        assertEquals(company.id, issue.companyId)
        assertEquals(AttemptStatus.COMPLETED, attemptStore.search(document.id, PageRequest(), Instant.now()).items.single().status)
    }

    @Test
    fun `returns skipped while another pipeline cycle is running`() = runTest {
        companyStore.save(Company(ticker = "DELL", name = "Dell"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blockingProvider = object : NewsProvider {
            override val name: String = "blocking"

            override suspend fun fetch(request: NewsFetchRequest): NewsFetchResult {
                started.complete(Unit)
                release.await()
                return NewsFetchResult(articles = emptyList(), nextCursor = null)
            }
        }
        val pipeline = pipeline(
            providers = listOf(blockingProvider),
            ingestionProperties = IngestionProperties(provider = blockingProvider.name, fallbackProvider = "none"),
        )

        val first = async { pipeline.runCycle(Instant.parse("2026-09-16T10:00:00Z")) }
        started.await()
        val active = recorder.activeIds().single()
        val second = pipeline.runCycle(Instant.parse("2026-09-16T10:00:01Z"))
        assertNull(second.runId)
        assertEquals(setOf(active), recorder.activeIds())
        release.complete(Unit)

        assertEquals("SKIPPED", second.status)
        assertTrue(second.alreadyRunning)
        val completed = first.await()
        assertEquals("SUCCESS", completed.status)
        assertEquals(active, completed.runId)
        assertFalse(recorder.isActive(active))
    }

    @Test
    fun `ingestion cancellation closes its provider and operation and releases the guard`() = runTest {
        companyStore.save(Company(ticker = "DELL", name = "Dell"))
        var cancel = true
        val provider = object : NewsProvider {
            override val name = "polygon"
            override suspend fun fetch(request: NewsFetchRequest): NewsFetchResult {
                if (cancel) throw CancellationException("secret cancellation detail")
                return NewsFetchResult(emptyList(), null)
            }
        }
        val pipeline = pipeline(providers = listOf(provider), ingestionProperties = IngestionProperties(fallbackProvider = "none"))
        val before = runs.listRecent(100).map { it.id }.toSet()
        assertFailsWith<CancellationException> { pipeline.runCycle() }
        val providerRun = runs.listRecent(100).single { it.id !in before }
        assertEquals(IngestionStatus.FAILED, providerRun.status)
        assertEquals("CANCELLED", providerRun.errorCode)
        val runId = assertNotNull(providerRun.runId)
        val run = assertNotNull(operationRuns.detail(runId, Instant.now())).run
        assertEquals(OperationStatus.CANCELLED, run.status)
        assertFalse(run.captureComplete)
        assertFalse(recorder.isActive(runId))
        cancel = false
        assertEquals("SUCCESS", pipeline.runCycle().status)
    }

    @Test
    fun `document cancellation interrupts its attempt and never becomes terminal`() = runTest {
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val company = companyStore.save(Company(ticker = "DELL", name = "Dell"))
        val document = queuedDocument(company, now)
        val cancelling = object : EventExtractionProvider {
            override suspend fun extract(request: ExtractionRequest): ExtractionResult = throw CancellationException("cancel")
        }
        val pipeline = drainedPipeline(extraction = cancelling)
        assertFailsWith<CancellationException> { pipeline.runCycle(now) }
        val attempt = attemptStore.search(document.id, PageRequest(), Instant.now()).items.single()
        assertEquals(AttemptStatus.INTERRUPTED, attempt.status)
        assertEquals("CANCELLED", attempt.errorCode)
        assertNull(attempt.finishedAt)
        assertEquals(DocumentProcessingStatus.PROCESSING, processing.findBySourceDocumentId(document.id)?.status)
        val run = assertNotNull(operationRuns.detail(attempt.runId, Instant.now())).run
        assertEquals(OperationStatus.CANCELLED, run.status)
        assertFalse(run.captureComplete)
        assertFalse(recorder.isActive(attempt.runId))
        assertEquals(0, run.documentsConsidered)
    }

    @Test
    fun `scoring cancellation retains committed document counters and stops the cycle`() = runTest {
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val company = companyStore.save(Company(ticker = "DELL", name = "Dell"))
        val document = queuedDocument(company, now)
        val cancelledCatalyst = mock<CatalystService>()
        whenever(cancelledCatalyst.recalculate(eq(company.id), any())).thenThrow(CancellationException("cancel"))
        assertFailsWith<CancellationException> { drainedPipeline(catalyst = cancelledCatalyst).runCycle(now) }
        val attempt = attemptStore.search(document.id, PageRequest(), Instant.now()).items.single()
        assertEquals(AttemptStatus.COMPLETED, attempt.status)
        val run = assertNotNull(operationRuns.detail(attempt.runId, Instant.now())).run
        assertEquals(OperationStatus.CANCELLED, run.status)
        assertFalse(run.captureComplete)
        assertEquals(1, run.documentsCompleted)
        assertEquals(1, run.eventsInserted)
        assertEquals(1, run.companiesConsidered)
        assertEquals(0, run.companiesFailed)
        assertEquals(0, run.companiesRescored)
        assertFalse(recorder.isActive(attempt.runId))
    }

    @Test
    fun `an unexpected progress failure preserves prior recorded counters and committed outcomes`() = runTest {
        val now = Instant.parse("2026-09-16T10:00:00Z")
        val company = companyStore.save(Company(ticker = "DELL", name = "Dell"))
        val first = queuedDocument(company, now)
        val second = queuedDocument(company, now)
        val failingRecorder = spy(recorder)
        doAnswer { call ->
            val counts = call.getArgument<OperationCounts>(1)
            if (counts.documentsConsidered == 2) throw IllegalStateException("progress unavailable")
            call.callRealMethod()
        }.whenever(failingRecorder).progress(any(), any())
        val result = pipeline(
            providers = listOf(NoNewsProvider),
            ingestionProperties = IngestionProperties(provider = NoNewsProvider.name, fallbackProvider = "none"),
            recorder = failingRecorder,
        ).runCycle(now)
        val id = assertNotNull(result.runId)
        assertEquals("FAILED", result.status)
        assertEquals(0, result.documentsProcessed, "legacy outer failure result retains its counters")
        val run = assertNotNull(operationRuns.detail(id, Instant.now())).run
        assertEquals(OperationStatus.FAILED, run.status)
        assertFalse(run.captureComplete)
        assertEquals("PROCESSING_FAILURE", run.errorCode)
        assertEquals(1, run.documentsCompleted, "last successfully recorded progress remains visible")
        assertEquals(DocumentProcessingStatus.COMPLETED, processing.findBySourceDocumentId(first.id)?.status)
        assertEquals(DocumentProcessingStatus.COMPLETED, processing.findBySourceDocumentId(second.id)?.status)
        assertFalse(failingRecorder.isActive(id))
    }

    private fun newsPage(published: String): String {
        fun article(id: String, marker: String) = """
    {"id": "$id", "title": "Dell $marker story", "description": "Dell $marker body.", "article_url": "https://example.com/$id", "published_utc": "$published", "tickers": ["DELL"]}"""
        return """{
  "results": [
${article("poly-1", "raise")},
${article("poly-2", "beat")},
${article("poly-3", "contract")},
${article("poly-4", "takeover")}
  ],
  "status": "OK", "request_id": "r", "count": 4
}"""
    }

    companion object {
        @RegisterExtension
        @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        // The provider labels this story with a ticker nobody tracks.
        const val UNRELATED_TICKER_PAGE = """{
  "results": [
    {"id": "poly-unrelated", "title": "Unlisted Systems wins contract", "description": "Unlisted Systems won a contract.", "article_url": "https://example.com/u", "published_utc": "2026-09-16T14:30:00Z", "tickers": ["ZZZZ"]}
  ],
  "status": "OK", "request_id": "r", "count": 1
}"""
    }

    object TitleExtraction : EventExtractionProvider {
        override suspend fun extract(request: ExtractionRequest): ExtractionResult {
            val title = request.document.title
            val type = when {
                "raise" in title -> EventType.GUIDANCE_RAISE
                "beat" in title -> EventType.EARNINGS_BEAT
                "takeover" in title -> EventType.TAKEOVER_TARGET
                else -> EventType.CONTRACT_WIN
            }
            return ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    ExtractedEvent(
                        ticker = request.companies.first().ticker,
                        type = type,
                        direction = Direction.POSITIVE,
                        confidence = 1.0,
                        magnitude = null,
                        surprise = null,
                        materiality = null,
                        expectedHorizon = EventHorizon.WEEKS,
                        directness = Directness.DIRECT,
                        eventTimestamp = null,
                        evidence = listOf(EventEvidence("quote", null)),
                        attributes = emptyMap(),
                    ),
                ),
            )
        }
    }

    /** Counts how often a document actually reaches the extraction model. */
    class CountingExtraction : EventExtractionProvider {
        var calls: Int = 0
            private set

        override suspend fun extract(request: ExtractionRequest): ExtractionResult {
            calls++
            return TitleExtraction.extract(request)
        }
    }

    object FakeEmbeddings : EmbeddingProvider {
        override val model: String = "fake"

        override suspend fun embed(text: String): Embedding {
            var seed = text.hashCode().toLong()
            val values = List(1536) {
                seed = (seed * 6364136223846793005L + 1442695040888963407L) shr 11
                ((seed ushr 32) % 2000).toFloat() / 1000f - 1f
            }
            return Embedding(values, model)
        }
    }

    object NoNewsProvider : NewsProvider {
        override val name: String = "empty"

        override suspend fun fetch(request: NewsFetchRequest): NewsFetchResult =
            NewsFetchResult(articles = emptyList(), nextCursor = null)
    }
}
