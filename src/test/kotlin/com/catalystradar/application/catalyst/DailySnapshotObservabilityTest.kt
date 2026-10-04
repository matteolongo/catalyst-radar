package com.catalystradar.application.catalyst

import com.catalystradar.adapters.finnhub.FinnhubNewsProvider
import com.catalystradar.adapters.openai.OpenAiEmbeddingProvider
import com.catalystradar.adapters.openai.OpenAiEventExtractionProvider
import com.catalystradar.adapters.polygon.PolygonNewsProvider
import com.catalystradar.application.operations.*
import com.catalystradar.operations.OperationsFixtures
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.operations.OperationRunStore
import com.catalystradar.persistence.operations.OperationsSummaryStore
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlin.test.*

@Transactional
class DailySnapshotObservabilityTest : PostgresIntegrationTest() {
    @Autowired private lateinit var companies: CompanyStore
    @Autowired private lateinit var runs: OperationRunStore
    @Autowired private lateinit var summary: OperationsSummaryStore
    @Autowired private lateinit var jdbc: JdbcClient
    @MockitoSpyBean private lateinit var polygon: PolygonNewsProvider
    @MockitoSpyBean private lateinit var finnhub: FinnhubNewsProvider
    @MockitoSpyBean private lateinit var extraction: OpenAiEventExtractionProvider
    @MockitoSpyBean private lateinit var embedding: OpenAiEmbeddingProvider
    private val t0 = Instant.parse("2026-10-04T12:00:00Z")
    private val fixtures get() = OperationsFixtures(jdbc)
    private val window = ActivityWindow(t0.minusSeconds(1), t0.plusSeconds(60))
    private fun recorder(at: Instant = t0) = OperationRunRecorder(runs, Clock.fixed(at, ZoneOffset.UTC))

    @Test
    fun `daily failures are recorded separately from ingestion`() {
        val failing = fixtures.company("AAA")
        val succeeding = fixtures.company("ZZZ")
        val catalyst = mock<CatalystService>()
        whenever(catalyst.recalculate(eq(failing), any(), any())).thenThrow(IllegalStateException("secret test failure"))
        clearInvocations(polygon, finnhub, extraction, embedding)
        val recorder = recorder()
        val service = DailySnapshotService(companies, catalyst, recorder)

        val result = service.recalculateActive(t0, OperationTrigger.SCHEDULED)

        assertEquals(DailySnapshotStatus.PARTIAL, result.status)
        verify(catalyst).recalculate(eq(succeeding), eq(t0), any())
        val run = runs.search(RunQuery(window, OperationKind.DAILY_SNAPSHOTS), t0.plusSeconds(60)).items.single()
        assertEquals(OperationStatus.PARTIAL, run.status)
        assertEquals(1, run.companiesRescored)
        assertEquals(1, run.companiesFailed)
        assertTrue(run.captureComplete)
        assertEquals(OperationTrigger.SCHEDULED, run.trigger)
        assertEquals(t0, run.startedAt)
        assertEquals(t0, run.finishedAt)
        assertTrue(recorder.activeIds().isEmpty())
        val issue = runs.issues(run.id, PageRequest(), t0.plusSeconds(60)).items.single()
        assertEquals(failing, issue.companyId)
        assertEquals("AAA", issue.ticker)
        assertEquals("SCORING_FAILURE", issue.errorCode)
        assertEquals(OperationalErrors.message("SCORING_FAILURE"), issue.errorMessage)
        val overview = summary.read(window, t0.plusSeconds(60), emptySet())
        assertNull(overview.lastSnapshotSuccessAt)
        assertNull(overview.lastIngestionSuccessAt)
        assertEquals(1L, overview.activity.successfulRecalculations)
        assertEquals(1L, overview.activity.recalculationFailures)
        verifyNoInteractions(polygon, finnhub, extraction, embedding)
    }

    @Test
    fun `successful daily freshness uses ledger clock and later partial does not replace it`() {
        val company = fixtures.company("AAA")
        val unfinishedPipeline = fixtures.run(t0.minusSeconds(600), status = "RUNNING")
        val catalyst = mock<CatalystService>()
        val historicalAsOf = t0.minusSeconds(86400)
        val recorder = recorder()

        val result = DailySnapshotService(companies, catalyst, recorder).recalculateActive(historicalAsOf)

        assertEquals(DailySnapshotStatus.SUCCESS, result.status)
        verify(catalyst).recalculate(eq(company), eq(historicalAsOf), any())
        val success = runs.search(RunQuery(window, OperationKind.DAILY_SNAPSHOTS), t0.plusSeconds(60)).items.single()
        assertEquals(historicalAsOf, success.asOf)
        assertEquals(t0, success.startedAt)
        assertEquals(t0, success.finishedAt)
        assertEquals(OperationTrigger.MANUAL, success.trigger)
        assertEquals(listOf(OperationPhaseTiming("SCORING", t0, t0, 0L)), assertNotNull(runs.detail(success.id, t0)).phases)
        assertEquals(OperationStatus.RUNNING, runs.detail(unfinishedPipeline, t0)?.run?.status)
        whenever(catalyst.recalculate(eq(company), any(), any())).thenThrow(IllegalStateException("failure"))
        DailySnapshotService(companies, catalyst, recorder(t0.plusSeconds(10))).recalculateActive(t0)
        val lastSuccess = summary.read(window, t0.plusSeconds(60), emptySet()).lastSnapshotSuccessAt
        assertEquals(t0, lastSuccess)
        val policy = OperationsStatusPolicy(OperationsProperties())
        val current = policy.freshness(true, Duration.ofSeconds(30), lastSuccess, t0.plusSeconds(60))
        assertEquals(FreshnessStatus.CURRENT, current.status)
        assertEquals(60L, current.ageSeconds)
        assertEquals(FreshnessStatus.OVERDUE, policy.freshness(true, Duration.ofSeconds(30), lastSuccess, t0.plusSeconds(60).plusNanos(1000)).status)
        val disabled = policy.freshness(false, Duration.ofSeconds(1), lastSuccess, t0.plusSeconds(60))
        assertEquals(FreshnessStatus.OFF, disabled.status)
        assertEquals(t0, disabled.lastSuccessAt)
    }

    @Test
    fun `daily cancellation closes incomplete history and preserves earlier progress`() {
        fixtures.company("AAA")
        val cancelled = fixtures.company("ZZZ")
        val catalyst = mock<CatalystService>()
        whenever(catalyst.recalculate(eq(cancelled), any(), any())).thenThrow(CancellationException("secret cancellation"))
        val recorder = recorder()
        val service = DailySnapshotService(companies, catalyst, recorder)

        assertFailsWith<CancellationException> { service.recalculateActive(t0) }

        val run = runs.search(RunQuery(window, OperationKind.DAILY_SNAPSHOTS), t0.plusSeconds(60)).items.single()
        assertEquals(OperationStatus.CANCELLED, run.status)
        assertEquals(2, run.companiesConsidered)
        assertEquals(1, run.companiesRescored)
        assertEquals(0, run.companiesFailed)
        assertFalse(run.captureComplete)
        assertEquals("CANCELLED", run.errorCode)
        assertEquals("CANCELLED", runs.issues(run.id, PageRequest(), t0).items.single().errorCode)
        assertTrue(recorder.activeIds().isEmpty())
        assertNull(summary.read(window, t0, emptySet()).lastSnapshotSuccessAt)
    }
}
