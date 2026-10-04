package com.catalystradar.operations

import com.catalystradar.adapters.finnhub.FinnhubProperties
import com.catalystradar.adapters.finnhub.FinnhubNewsProvider
import com.catalystradar.adapters.openai.OpenAiProperties
import com.catalystradar.adapters.openai.OpenAiEventExtractionProvider
import com.catalystradar.adapters.openai.OpenAiEmbeddingProvider
import com.catalystradar.adapters.polygon.PolygonProperties
import com.catalystradar.adapters.polygon.PolygonNewsProvider
import com.catalystradar.application.catalyst.SnapshotProperties
import com.catalystradar.application.ingestion.IngestionProperties
import com.catalystradar.application.operations.*
import com.catalystradar.application.pipeline.PipelineProperties
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.persistence.operations.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.env.MockEnvironment
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

@Transactional
class OperationsSummaryStoreTest : PostgresIntegrationTest() {
    @Autowired private lateinit var store: OperationsSummaryStore
    @Autowired private lateinit var documents: DocumentInspectionStore
    @Autowired private lateinit var models: ModelInspectionStore
    @Autowired private lateinit var runs: OperationRunStore
    @Autowired private lateinit var attempts: ProcessingAttemptStore
    @Autowired private lateinit var events: EventStore
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var mapper: ObjectMapper
    @MockitoSpyBean private lateinit var polygonProvider: PolygonNewsProvider
    @MockitoSpyBean private lateinit var finnhubProvider: FinnhubNewsProvider
    @MockitoSpyBean private lateinit var extractionProvider: OpenAiEventExtractionProvider
    @MockitoSpyBean private lateinit var embeddingProvider: OpenAiEmbeddingProvider
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val window = ActivityWindow(now.minusSeconds(86400), now)
    private val fixtures get() = OperationsFixtures(jdbc)

    @Test
    fun `future retries and active processing are not due`() {
        val pending = fixtures.document(now.minusSeconds(600))
        val future = fixtures.document(now.minusSeconds(500))
        val processing = fixtures.document(now.minusSeconds(400))
        val run = fixtures.run(now.minusSeconds(120), status = "RUNNING")
        fixtures.processing(pending, "PENDING", updatedAt = now.minusSeconds(600))
        fixtures.processing(future, "RETRYABLE_ERROR", next = now.plusSeconds(60), updatedAt = now)
        fixtures.processing(processing, "PROCESSING", attempts = 1, updatedAt = now)
        fixtures.attempt(processing, run, 1, now.minusSeconds(30), status = "RUNNING")
        val data = store.read(window, now, setOf(run))
        assertEquals(2L, data.queue.waiting)
        assertEquals(1L, data.queue.processing)
        assertEquals(1L, data.queue.due)
        assertEquals(pending, data.queue.oldestDueDocumentId)
        assertEquals(run, data.oldestActiveAttempt?.runId)
        assertFalse(data.activeRuns.single().active)
        assertEquals(2L, store.read(window, now, emptySet()).queue.due)
        assertNull(store.read(window, now, emptySet()).oldestActiveAttempt)
    }

    @Test
    fun `queue is global and recovered processing uses creation time with deterministic oldest order`() {
        val old = now.minusSeconds(90000)
        val pending = fixtures.document(old)
        val recovered = fixtures.document(old)
        fixtures.processing(pending, "PENDING", updatedAt = old)
        fixtures.processing(recovered, "PROCESSING", updatedAt = old)
        fixtures.attempt(recovered, fixtures.run(old, "RUNNING"), 1, old, "RUNNING")
        val retry = fixtures.document(now.minusSeconds(60))
        fixtures.processing(retry, "RETRYABLE_ERROR", next = now, updatedAt = now)
        val noDate = fixtures.document(now)
        fixtures.processing(noDate, "RETRYABLE_ERROR", updatedAt = now)
        val data = store.read(window, now, emptySet())
        assertEquals(3L, data.queue.waiting)
        assertEquals(3L, data.queue.due)
        assertEquals(old, data.queue.oldestDueAt)
        assertEquals(listOf(pending, recovered).minBy(UUID::toString), data.queue.oldestDueDocumentId)
        assertEquals(1L, data.activity.documentsNew)
        assertTrue(data.activeRuns.isEmpty())
    }

    @Test
    fun `independent counts do not multiply documents companies events or model calls`() {
        val activityWindow = ActivityWindow(now.minusSeconds(60), now)
        val baseline = store.read(activityWindow, now, emptySet())
        val at = now.minusSeconds(30)
        val document = fixtures.document(at)
        val company = fixtures.company("AAA")
        fixtures.link(document, company)
        fixtures.link(document, fixtures.company("BBB"))
        fixtures.processing(document, "PENDING", updatedAt = at)
        repeat(3) {
            val cluster = UUID.randomUUID()
            jdbc.sql("INSERT INTO event_clusters(id,company_id,event_type,first_seen_at,created_at) VALUES(:id,:company,'GUIDANCE_RAISE',:at::timestamptz,:at::timestamptz)")
                .param("id", cluster).param("company", company).param("at", at.toString()).update()
            jdbc.sql("""INSERT INTO events(id,company_id,cluster_id,source_document_id,event_type,family,direction,confidence,
                source_quality,expected_horizon,directness,discovered_at,taxonomy_version,extractor_version,created_at)
                VALUES(:id,:company,:cluster,:document,'GUIDANCE_RAISE','GUIDANCE','POSITIVE',0.9,'TIER1_NEWS','DAYS','DIRECT',
                :at::timestamptz,'taxonomy-v1','extract-v1',:at::timestamptz)""")
                .param("id", UUID.randomUUID()).param("company", company).param("cluster", cluster)
                .param("document", document).param("at", at.toString()).update()
        }
        repeat(4) { fixtures.model(at, null, 10, 2, document = document) }
        ingestion(at, at.plusSeconds(1), "SUCCESS", fetched = 9, duplicates = 8)
        ingestion(at, null, "RUNNING", fetched = 4, duplicates = 3)
        val data = store.read(activityWindow, now, emptySet())
        assertEquals(baseline.activeCompanies + 2, data.activeCompanies)
        assertEquals(baseline.queue.pending + 1, data.queue.pending)
        assertEquals(1L, data.activity.documentsNew)
        assertEquals(13L, data.activity.documentsFetched)
        assertEquals(11L, data.activity.documentsDuplicate)
        assertEquals(3L, data.activity.eventsInserted)
        assertEquals(3L, data.activity.clustersCreated)
        assertEquals(4L, data.models.calls)
        assertEquals(40L, data.models.inputTokens)
        assertNull(data.models.estimatedCostUsd)
    }

    @Test
    fun `untracked states follow association and processing state takes precedence`() {
        val baseline = store.read(window, now, emptySet()).queue
        fixtures.document(now)
        val linked = fixtures.document(now)
        fixtures.link(linked, fixtures.company("AAA"))
        fixtures.link(linked, fixtures.company("BBB"))
        val terminal = fixtures.document(now)
        fixtures.processing(terminal, "TERMINAL_ERROR", updatedAt = now)
        val skipped = fixtures.document(now)
        fixtures.processing(skipped, "SKIPPED", attempts = 1, updatedAt = now)
        val data = store.read(window, now, emptySet())
        assertEquals(baseline.unresolved + 1, data.queue.unresolved)
        assertEquals(baseline.notTracked + 1, data.queue.notTracked)
        assertEquals(baseline.terminal + 1, data.queue.terminal)
        assertEquals(baseline.due, data.queue.due)
    }

    @Test
    fun `recalculation totals are unknown before history and count only completed captured cycles`() {
        val empty = store.read(window, now, emptySet())
        assertFalse(empty.activity.recalculationHistoryAvailable)
        assertNull(empty.activity.successfulRecalculations)
        assertNull(empty.activity.recalculationFailures)
        assertNull(empty.lastSnapshotSuccessAt)
        assertNull(empty.historyStartedAt)
        val pipeline = fixtures.run(now.minusSeconds(60))
        val daily = fixtures.run(now.minusSeconds(30), kind = "DAILY_SNAPSHOTS")
        val incomplete = fixtures.run(now.minusSeconds(10), status = "RUNNING")
        listOf(pipeline, daily, incomplete).forEach { id ->
            jdbc.sql("UPDATE operation_runs SET companies_rescored=4,companies_failed=2 WHERE id=:id").param("id", id).update()
        }
        val data = store.read(window, now, emptySet())
        assertTrue(data.activity.recalculationHistoryAvailable)
        assertEquals(8L, data.activity.successfulRecalculations)
        assertEquals(4L, data.activity.recalculationFailures)
        assertEquals(now.minusSeconds(29), data.lastSnapshotSuccessAt)
        assertEquals(now.minusSeconds(60), data.historyStartedAt)
        assertTrue(data.activeRuns.isEmpty())
    }

    @Test
    fun `dependency outcomes use observation time outside activity and exclude cancellation unfinished and future rows`() {
        val old = now.minusSeconds(7200)
        ingestion(old, now.minusSeconds(300), "SUCCESS")
        ingestion(old, now.minusSeconds(200), "PARTIAL", code = "RATE_LIMITED")
        ingestion(old, now.minusSeconds(100), "FAILED", code = "CANCELLED")
        ingestion(old, null, "RUNNING", code = "AUTHENTICATION_FAILED")
        ingestion(old, now.plusSeconds(1), "SUCCESS")
        ingestion(old, now.minusSeconds(400), "FAILED", provider = "finnhub", code = "AUTHENTICATION_FAILED")
        fixtures.model(now.minusSeconds(4000), null, null, null, success = false)
        fixtures.model(now.minusSeconds(60), null, null, null, success = false)
        fixtures.model(now.minusSeconds(30), null, null, null)
        fixtures.model(now.plusSeconds(1), null, null, null, success = false)
        val data = store.read(ActivityWindow(now.minusSeconds(1), now), now, emptySet())
        assertEquals(now.minusSeconds(300), data.lastIngestionSuccessAt)
        assertEquals(listOf("polygon", "finnhub", "openai"), data.dependencyObservations.map { it.provider })
        val polygon = data.dependencyObservations[0]
        assertEquals(now.minusSeconds(200), polygon.lastObservedAt)
        assertEquals(now.minusSeconds(300), polygon.lastSuccessAt)
        assertEquals(false, polygon.latestSucceeded)
        assertEquals(1L, polygon.failedObservations)
        assertEquals("RATE_LIMITED", polygon.lastErrorCode)
        val openai = data.dependencyObservations[2]
        assertEquals(1L, openai.failedObservations)
        assertEquals(true, openai.latestSucceeded)
        assertNull(openai.lastErrorCode)
    }

    @Test
    fun `freshness retains source specific success when disabled and excludes later successes`() {
        ingestion(now.minusSeconds(90), now.minusSeconds(60), "SUCCESS")
        ingestion(now, now.plusSeconds(1), "SUCCESS")
        fixtures.run(now.minusSeconds(10))
        val daily = fixtures.run(now.minusSeconds(180), kind = "DAILY_SNAPSHOTS")
        fixtures.run(now.plusSeconds(10), kind = "DAILY_SNAPSHOTS")
        fixtures.run(now.minusSeconds(2), "FAILED", "DAILY_SNAPSHOTS")
        jdbc.sql("""INSERT INTO catalyst_snapshots(id,company_id,score,score_version,state,velocity_1d,velocity_3d,velocity_7d,taxonomy_version,as_of,created_at)
            VALUES(:id,:company,0,'score-v1','NORMAL',0,0,0,'taxonomy-v1',:at::timestamptz,:at::timestamptz)""")
            .param("id", UUID.randomUUID()).param("company", fixtures.company("AAA")).param("at", now.toString()).update()
        val off = service().overview(window)
        assertEquals(FreshnessStatus.OFF, off.ingestionFreshness.status)
        assertEquals(now.minusSeconds(60), off.ingestionFreshness.lastSuccessAt)
        assertEquals(FreshnessStatus.OFF, off.snapshotFreshness.status)
        assertEquals(now.minusSeconds(179), off.snapshotFreshness.lastSuccessAt)
        assertEquals(daily, service().run(daily).run.id)
        val on = service(IngestionProperties(enabled = true, interval = Duration.ofSeconds(30)),
            SnapshotProperties(enabled = true, interval = Duration.ofSeconds(60))).overview(window)
        assertEquals(FreshnessStatus.CURRENT, on.ingestionFreshness.status)
        assertEquals(FreshnessStatus.OVERDUE, on.snapshotFreshness.status)
    }

    @Test
    fun `dependency failure window is inclusive at start exclusive at end with deterministic authentication signal`() {
        val sampleNow = Instant.parse("2099-10-04T12:00:00Z")
        val start = sampleNow.minusSeconds(3600)
        ingestion(start.minusSeconds(2), start.minusSeconds(1), "FAILED", code = "RATE_LIMITED")
        ingestion(start.minusSeconds(1), start, "FAILED", code = "AUTHENTICATION_FAILED")
        ingestion(sampleNow.minusSeconds(1), sampleNow, "FAILED", provider = "finnhub", code = "AUTHENTICATION_FAILED")
        val inputs = store.read(window, sampleNow, emptySet())
        assertEquals(1L, inputs.dependencyObservations[0].failedObservations)
        assertEquals(0L, inputs.dependencyObservations[1].failedObservations)
        assertEquals(sampleNow, inputs.dependencyObservations[1].lastObservedAt)
        ingestion(sampleNow.minusSeconds(20), sampleNow.minusSeconds(10), "FAILED", provider = "polygon", code = "AUTHENTICATION_FAILED")
        ingestion(sampleNow.minusSeconds(20), sampleNow.minusSeconds(10), "FAILED", provider = "finnhub", code = "AUTHENTICATION_FAILED")
        val overview = service(polygon = PolygonProperties(apiKey = "configured"), finnhub = FinnhubProperties(apiKey = "configured"),
            clock = Clock.fixed(sampleNow.plusSeconds(1), ZoneOffset.UTC)).overview(window)
        val auth = overview.signals.single { it.code == "AUTHENTICATION_FAILURE" }
        assertEquals(2L, auth.count)
        assertEquals("polygon", auth.provider)
        assertEquals(listOf(DependencyStatus.DEGRADED, DependencyStatus.DEGRADED, DependencyStatus.NOT_CONFIGURED), overview.dependencies.map { it.status })
    }

    @Test
    fun `service composes disabled and never freshness and treats persisted running as inactive`() {
        val firstSuccess = jdbc.sql("""SELECT finished_at FROM ingestion_runs WHERE status='SUCCESS' AND finished_at <= :now::timestamptz
            UNION ALL SELECT finished_at FROM operation_runs WHERE kind='DAILY_SNAPSHOTS' AND status='SUCCESS' AND finished_at <= :now::timestamptz
            ORDER BY finished_at ASC LIMIT 1""")
            .param("now", now.toString()).query(java.sql.Timestamp::class.java).optional().orElse(null)?.toInstant()
        val cutoff = firstSuccess?.minusNanos(1000) ?: now
        val cutoffWindow = ActivityWindow(cutoff.minusSeconds(86400), cutoff)
        val clock = Clock.fixed(cutoff, ZoneOffset.UTC)
        val baseline = store.read(cutoffWindow, cutoff, emptySet())
        val run = fixtures.run(now.minusSeconds(30), "RUNNING")
        val document = fixtures.document(now.minusSeconds(30))
        fixtures.processing(document, "PROCESSING", updatedAt = now.minusSeconds(30))
        fixtures.attempt(document, run, 1, now.minusSeconds(20), "RUNNING")
        val off = service(clock = clock).overview(cutoffWindow)
        assertEquals(cutoff, off.generatedAt)
        assertEquals(FreshnessStatus.OFF, off.ingestionFreshness.status)
        assertEquals(FreshnessStatus.OFF, off.snapshotFreshness.status)
        assertTrue(off.activeRuns.isEmpty())
        assertEquals(baseline.queue.due + 1, off.queue.due)
        assertEquals(listOf(document), service().documents(DocumentQuery(dueOnly = true, runId = run)).items.map { it.id })
        assertFalse(service().run(run).run.active)
        val enabled = service(IngestionProperties(enabled = true), SnapshotProperties(enabled = true), clock = clock).overview(cutoffWindow)
        assertEquals(FreshnessStatus.NEVER, enabled.ingestionFreshness.status)
        assertEquals(FreshnessStatus.NEVER, enabled.snapshotFreshness.status)
        assertEquals(listOf("INGESTION_OVERDUE", "SNAPSHOTS_OVERDUE", "DUE_DOCUMENTS"), enabled.signals.map { it.code }.filter {
            it in setOf("INGESTION_OVERDUE", "SNAPSHOTS_OVERDUE", "DUE_DOCUMENTS")
        })
    }

    @Test
    fun `safe configuration exposes declared fields real versions and only blankness of keys`() {
        val service = service(IngestionProperties(enabled = true, interval = Duration.ofSeconds(17)),
            SnapshotProperties(interval = Duration.ofSeconds(23)),
            PolygonProperties(baseUrl = "secret-polygon-url", apiKey = "secret-polygon-key"),
            FinnhubProperties(apiKey = "   "), OpenAiProperties(apiKey = "secret-openai-key", extractionModel = "extract-model", embeddingModel = "embed-model"))
        val config = service.config()
        assertEquals(now, config.generatedAt)
        assertTrue(config.publicApiAuthEnabled)
        assertEquals(17L, config.ingestion.intervalSeconds)
        assertEquals(23L, config.snapshots.intervalSeconds)
        assertEquals(listOf(ProviderConfig("polygon", true), ProviderConfig("finnhub", false), ProviderConfig("openai", true)), config.providers)
        assertEquals(ArtifactVersions("score-v1", "taxonomy-v1", "event-extractor-v1", "event-extractor-v1", "extract-model", "embed-model"), config.versions)
        val json = mapper.readTree(mapper.writeValueAsString(config))
        assertEquals(setOf("generatedAt", "publicApiAuthEnabled", "singleInstance", "ingestion", "snapshots", "primaryNewsProvider", "fallbackNewsProvider", "pipelineBatchSize", "pipelineMaxAttempts", "pipelineRetryDelaySeconds", "providers", "versions"), json.propertyNames().toSet())
        assertFalse(json.toString().contains("secret"))
        assertEquals(7, service.document(fixtures.document(now)).maxAttempts)
    }

    @Test
    fun `child feeds validate explicit parent resources`() {
        val id = UUID.randomUUID()
        val service = service()
        listOf<() -> Any>({ service.document(id) }, { service.documentBody(id) }, { service.documentEvents(id, PageRequest()) },
            { service.documentAttempts(id, PageRequest()) }, { service.documentModelRuns(id, PageRequest()) }).forEach { read ->
            assertEquals(OperationsResource.DOCUMENT, assertFailsWith<OperationsResourceNotFoundException> { read() }.resource)
        }
        assertEquals(OperationsResource.OPERATION_RUN, assertFailsWith<OperationsResourceNotFoundException> { service.runIssues(id, PageRequest()) }.resource)
        assertEquals(OperationsResource.OPERATION_RUN, assertFailsWith<OperationsResourceNotFoundException> { service.ingestionRuns(IngestionQuery(window, runId = id)) }.resource)
        assertEquals(OperationsResource.MODEL_RUN, assertFailsWith<OperationsResourceNotFoundException> { service.modelRun(id) }.resource)
    }

    @Test
    fun `operational reads use stored data without invoking news extraction or embedding providers`() {
        val activityWindow = ActivityWindow(now.minusSeconds(60), now)
        val document = fixtures.document(now.minusSeconds(30))
        val run = fixtures.run(now.minusSeconds(20))
        val attempt = fixtures.attempt(document, run, 1, now.minusSeconds(10))
        val call = fixtures.model(now.minusSeconds(5), null, null, null, document = document, attempt = attempt)
        clearInvocations(polygonProvider, finnhubProvider, extractionProvider, embeddingProvider)
        val service = service()
        service.config()
        assertEquals(1L, service.overview(activityWindow).models.calls)
        assertEquals(document, service.documents(DocumentQuery(from = activityWindow.from, to = activityWindow.to)).items.single().id)
        assertEquals(document, service.document(document).document.id)
        assertEquals("Evidence", service.documentBody(document).text)
        assertTrue(service.documentEvents(document, PageRequest()).events.isEmpty())
        assertEquals(attempt, service.documentAttempts(document, PageRequest()).items.single().id)
        assertEquals(call, service.documentModelRuns(document, PageRequest()).items.single().id)
        assertEquals(run, service.runs(RunQuery(window)).items.single().id)
        assertEquals(run, service.run(run).run.id)
        assertTrue(service.runIssues(run, PageRequest()).items.isEmpty())
        assertTrue(service.ingestionRuns(IngestionQuery(activityWindow)).items.isEmpty())
        assertEquals(call, service.modelRuns(ModelQuery(activityWindow)).items.single().id)
        assertEquals(call, service.modelRun(call).id)
        assertEquals(1L, service.modelSummary(ModelQuery(activityWindow)).totals.calls)
        verifyNoInteractions(polygonProvider, finnhubProvider, extractionProvider, embeddingProvider)
    }

    @Test
    fun `overview captures its clock exactly once for all composed values`() {
        var reads = 0
        val clock = object : Clock() {
            override fun instant(): Instant = now.plusSeconds((reads++).toLong())
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
        }
        val overview = service(clock = clock).overview(window)
        assertEquals(1, reads)
        assertEquals(now, overview.generatedAt)
        assertEquals(now.minusSeconds(3600), overview.dependencies.first().observedSince)
    }

    private fun service(ingestion: IngestionProperties = IngestionProperties(), snapshots: SnapshotProperties = SnapshotProperties(),
                        polygon: PolygonProperties = PolygonProperties(), finnhub: FinnhubProperties = FinnhubProperties(), openai: OpenAiProperties = OpenAiProperties(),
                        clock: Clock = Clock.fixed(now, ZoneOffset.UTC)) =
        OperationsService(store, documents, models, runs, attempts, events, OperationsProperties(), ingestion,
            PipelineProperties(maxAttempts = 7), snapshots, polygon, finnhub, openai,
            MockEnvironment().withProperty("catalyst.api.auth-enabled", "true"), clock)

    private fun ingestion(start: Instant, finish: Instant?, status: String, provider: String = "polygon", code: String? = null,
                          fetched: Int = 0, duplicates: Int = 0) {
        jdbc.sql("""INSERT INTO ingestion_runs(id,provider,status,started_at,finished_at,error_code,documents_fetched,documents_duplicate)
            VALUES(:id,:provider,:status,:start::timestamptz,:finish::timestamptz,:code,:fetched,:duplicates)""")
            .param("id", UUID.randomUUID()).param("provider", provider).param("status", status).param("start", start.toString())
            .param("finish", finish?.toString()).param("code", code).param("fetched", fetched).param("duplicates", duplicates).update()
    }
}
