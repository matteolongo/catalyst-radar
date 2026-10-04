package com.catalystradar.operations

import com.catalystradar.application.operations.*
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.operations.OperationRunStore
import com.catalystradar.persistence.operations.OperationsSummaryStore
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

@Transactional
class OperationRunRecorderTest : PostgresIntegrationTest() {
    @Autowired private lateinit var runStore: OperationRunStore
    @Autowired private lateinit var summaryStore: OperationsSummaryStore
    @Autowired private lateinit var jdbc: JdbcClient
    private val t0 = Instant.parse("2026-10-04T12:00:00Z")
    private val clock = MutableOperationsClock(t0)
    private val fixtures get() = OperationsFixtures(jdbc)
    private val recorder get() = OperationRunRecorder(runStore, clock)

    @Test
    fun `the next guarded cycle closes unfinished history without inventing finish time`() {
        val earlier = fixtures.run(t0.minusSeconds(600), status = "RUNNING")
        val daily = fixtures.run(t0.minusSeconds(600), status = "RUNNING", kind = "DAILY_SNAPSHOTS")
        val document = fixtures.document(t0.minusSeconds(600))
        val attempt = fixtures.attempt(document, earlier, 1, t0.minusSeconds(590), "RUNNING")
        val completed = fixtures.attempt(document, earlier, 2, t0.minusSeconds(580))
        val recorder = recorder
        val id = recorder.begin(OperationKind.PIPELINE, OperationTrigger.MANUAL, t0.minusSeconds(3600))
        val old = assertNotNull(runStore.detail(earlier, t0)).run
        assertEquals(OperationStatus.INTERRUPTED, old.status)
        assertEquals("UNFINISHED_PREVIOUS_RUN", old.errorCode)
        assertNull(old.finishedAt)
        assertNull(old.durationMs)
        assertFalse(old.captureComplete)
        assertFalse(old.active)
        assertEquals(t0, old.updatedAt)
        assertTrue(recorder.isActive(id))
        assertEquals(setOf(id), recorder.activeIds())
        assertEquals(t0, runStore.detail(id, t0)?.run?.startedAt)
        assertEquals(t0.minusSeconds(3600), runStore.detail(id, t0)?.run?.asOf)
        assertEquals(OperationPhase.INGESTION, runStore.detail(id, t0)?.run?.phase)
        assertEquals(OperationStatus.RUNNING, runStore.detail(daily, t0)?.run?.status)
        val unfinished = jdbc.sql("SELECT status,error_code,finished_at,updated_at FROM document_processing_attempts WHERE id=:id")
            .param("id", attempt).query { rs, _ -> listOf(rs.getString("status"), rs.getString("error_code"), rs.getTimestamp("finished_at"), rs.getTimestamp("updated_at").toInstant()) }.single()
        assertEquals(listOf("INTERRUPTED", "UNFINISHED_PREVIOUS_RUN", null, t0), unfinished)
        assertEquals("COMPLETED", fixtures.attemptStatus(completed))
    }

    @Test
    fun `pipeline records phase boundaries progress and final counts on wall clock`() {
        val recorder = recorder
        val id = recorder.begin(OperationKind.PIPELINE, OperationTrigger.SCHEDULED, t0.minusSeconds(86400))
        clock.now = t0.plusSeconds(10)
        recorder.phase(id, OperationPhase.PROCESSING)
        recorder.progress(id, OperationCounts(documentsConsidered = 4, documentsCompleted = 1))
        assertEquals(1, runStore.detail(id, clock.now)?.run?.documentsCompleted)
        clock.now = t0.plusSeconds(20)
        recorder.phase(id, OperationPhase.SCORING)
        clock.now = t0.plusSeconds(30)
        val counts = OperationCounts(4, 2, 1, 1, 0, 3, 2, 3, 2, 1)
        recorder.finish(id, OperationStatus.PARTIAL, counts, "SCORING_FAILURE", true)
        val detail = assertNotNull(runStore.detail(id, clock.now))
        assertEquals(OperationStatus.PARTIAL, detail.run.status)
        assertEquals(OperationPhase.FINISHED, detail.run.phase)
        assertEquals(t0.plusSeconds(30), detail.run.finishedAt)
        assertEquals(30000L, detail.run.durationMs)
        assertTrue(detail.run.captureComplete)
        assertEquals(2, detail.run.documentsCompleted)
        assertEquals(1, detail.run.documentsSkipped)
        assertEquals(1, detail.run.documentsRetryScheduled)
        assertEquals(3, detail.run.eventsInserted)
        assertEquals(2, detail.run.eventsReused)
        assertEquals(3, detail.run.companiesConsidered)
        assertEquals(2, detail.run.companiesRescored)
        assertEquals(1, detail.run.companiesFailed)
        assertEquals(listOf(10000L, 10000L, 10000L), detail.phases.map { it.durationMs })
        assertFalse(recorder.isActive(id))
    }

    @Test
    fun `failed and cancelled outer paths preserve recorded counts and remain incomplete`() {
        val recorder = recorder
        listOf(OperationStatus.FAILED, OperationStatus.CANCELLED).forEach { status ->
            val id = recorder.begin(OperationKind.PIPELINE, OperationTrigger.MANUAL, t0)
            recorder.progress(id, OperationCounts(documentsConsidered = 5, documentsCompleted = 2, companiesRescored = 1))
            clock.now = clock.now.plusSeconds(10)
            recorder.finish(id, status, null, if (status == OperationStatus.CANCELLED) "CANCELLED" else "PROCESSING_FAILURE", false)
            val run = assertNotNull(runStore.detail(id, clock.now)).run
            assertEquals(status, run.status)
            assertEquals(5, run.documentsConsidered)
            assertEquals(2, run.documentsCompleted)
            assertEquals(1, run.companiesRescored)
            assertFalse(run.captureComplete)
            assertFalse(recorder.isActive(id))
        }
    }

    @Test
    fun `daily cycles start scoring and successful capture alone contributes recalculation totals`() {
        val recorder = recorder
        val id = recorder.begin(OperationKind.DAILY_SNAPSHOTS, OperationTrigger.SCHEDULED, t0.minusSeconds(86400))
        assertEquals(OperationPhase.SCORING, runStore.detail(id, t0)?.run?.phase)
        recorder.progress(id, OperationCounts(companiesRescored = 100))
        val window = ActivityWindow(t0.minusSeconds(1), t0.plusSeconds(60))
        assertEquals(0L, summaryStore.read(window, t0, recorder.activeIds()).activity.successfulRecalculations)
        clock.now = t0.plusSeconds(20)
        recorder.finish(id, OperationStatus.SUCCESS, OperationCounts(companiesConsidered = 3, companiesRescored = 3), null, true)
        val detail = assertNotNull(runStore.detail(id, clock.now))
        assertEquals(listOf(OperationPhaseTiming("SCORING", t0, clock.now, 20000)), detail.phases)
        assertEquals(3L, summaryStore.read(window, clock.now, recorder.activeIds()).activity.successfulRecalculations)
        val boundaries = jdbc.sql("SELECT ingestion_finished_at,processing_finished_at FROM operation_runs WHERE id=:id")
            .param("id", id).query { rs, _ -> rs.getTimestamp(1) to rs.getTimestamp(2) }.single()
        assertEquals(null to null, boundaries)
    }

    @Test
    fun `recorded tied issues page with explicit identities and safe messages`() {
        val recorder = recorder
        val id = recorder.begin(OperationKind.PIPELINE, OperationTrigger.MANUAL, t0)
        val document = fixtures.document(t0)
        val company = fixtures.company("ABC")
        repeat(26) { recorder.issue(id, OperationPhase.SCORING, "SCORING_FAILURE", document, company) }
        val first = runStore.issues(id, PageRequest(), t0)
        val second = runStore.issues(id, PageRequest(25, assertNotNull(first.nextCursor)), t0)
        val all = first.items + second.items
        assertEquals(26, all.size)
        assertEquals(26, all.map { it.id }.distinct().size)
        assertEquals(all.map { it.id.toString() }.sortedDescending(), all.map { it.id.toString() })
        assertTrue(all.all { it.documentId == document && it.companyId == company && it.ticker == "ABC" && it.errorMessage == OperationalErrors.message("SCORING_FAILURE") })
        assertNull(second.nextCursor)
        assertFailsWith<IllegalArgumentException> { recorder.issue(id, OperationPhase.FINISHED, "UNKNOWN_FAILURE") }
    }

    @Test
    fun `failed persistence clears active observation and rejects nonterminal finish`() {
        // Only the storage failure is simulated; active registration and cleanup remain real.
        val failingStore = spy(AopTestUtils.getUltimateTargetObject<OperationRunStore>(runStore))
        val recorder = OperationRunRecorder(failingStore, clock)
        val id = recorder.begin(OperationKind.PIPELINE, OperationTrigger.MANUAL, t0)
        assertFailsWith<IllegalArgumentException> { recorder.finish(id, OperationStatus.RUNNING, null, null, false) }
        assertTrue(recorder.isActive(id))
        doThrow(IllegalStateException("storage unavailable")).`when`(failingStore)
            .finish(id, OperationStatus.FAILED, null, "UNKNOWN_FAILURE", false, t0)
        assertFailsWith<IllegalStateException> { recorder.finish(id, OperationStatus.FAILED, null, "UNKNOWN_FAILURE", false) }
        assertFalse(recorder.isActive(id))
        doThrow(IllegalStateException("storage unavailable")).`when`(failingStore)
            .begin(any(UUID::class.java) ?: UUID.randomUUID(), eq(OperationKind.PIPELINE) ?: OperationKind.PIPELINE,
                eq(OperationTrigger.MANUAL) ?: OperationTrigger.MANUAL, eq(t0) ?: t0, eq(t0) ?: t0)
        assertFailsWith<IllegalStateException> { recorder.begin(OperationKind.PIPELINE, OperationTrigger.MANUAL, t0) }
        assertTrue(recorder.activeIds().isEmpty())
    }

    @Test
    fun `active IDs snapshots are immutable and two operation kinds stay independent`() {
        val recorder = recorder
        val pipeline = recorder.begin(OperationKind.PIPELINE, OperationTrigger.MANUAL, t0)
        val captured = recorder.activeIds()
        val daily = recorder.begin(OperationKind.DAILY_SNAPSHOTS, OperationTrigger.SCHEDULED, t0)
        assertEquals(setOf(pipeline), captured)
        assertEquals(setOf(pipeline, daily), recorder.activeIds())
        recorder.finish(daily, OperationStatus.SUCCESS, OperationCounts(), null, true)
        assertEquals(setOf(pipeline), recorder.activeIds())
    }

    @Test
    fun `complete capture requires final counts and phase durations never become negative`() {
        val recorder = recorder
        val id = recorder.begin(OperationKind.PIPELINE, OperationTrigger.MANUAL, t0)
        assertFailsWith<IllegalArgumentException> { recorder.finish(id, OperationStatus.FAILED, null, null, true) }
        assertTrue(recorder.isActive(id))
        assertNull(OperationRunStore.duration(null, t0))
        assertEquals(0L, OperationRunStore.duration(t0, t0.minusSeconds(1)))
    }
}

private class MutableOperationsClock(var now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
