package com.catalystradar.application.catalyst

import com.catalystradar.application.operations.OperationTrigger
import com.catalystradar.observability.CatalystMetrics
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CancellationException
import kotlin.test.assertFailsWith

class DailySnapshotSchedulerTest {
    private val snapshots = mock<DailySnapshotService>()
    private val metrics = mock<CatalystMetrics>()
    private val scheduler = DailySnapshotScheduler(snapshots, metrics)
    private val success = DailySnapshotResult(DailySnapshotStatus.SUCCESS, 2, 2, 0)

    @Test
    fun `guarded scheduled trigger records one daily snapshot cycle`() {
        whenever(snapshots.recalculateActive(any(), eq(OperationTrigger.SCHEDULED))).thenReturn(success)

        scheduler.runDailySnapshots()

        verify(snapshots).recalculateActive(any(), eq(OperationTrigger.SCHEDULED))
        verify(metrics).dailySnapshotCycle("success")
    }

    @Test
    fun `busy scheduler skips without opening another cycle`() {
        whenever(snapshots.recalculateActive(any(), eq(OperationTrigger.SCHEDULED))).thenAnswer {
            scheduler.runDailySnapshots()
            success
        }

        scheduler.runDailySnapshots()

        verify(snapshots).recalculateActive(any(), eq(OperationTrigger.SCHEDULED))
        verify(metrics).dailySnapshotCycle("skipped")
    }

    @Test
    fun `scheduler propagates cancellation and releases its guard`() {
        whenever(snapshots.recalculateActive(any(), eq(OperationTrigger.SCHEDULED)))
            .thenThrow(CancellationException("cancelled")).thenReturn(success)

        assertFailsWith<CancellationException> { scheduler.runDailySnapshots() }
        scheduler.runDailySnapshots()

        verify(snapshots, times(2)).recalculateActive(any(), eq(OperationTrigger.SCHEDULED))
        verify(metrics).dailySnapshotCycle("success")
    }

    @Test
    fun `ordinary outer failure releases the scheduled guard`() {
        whenever(snapshots.recalculateActive(any(), eq(OperationTrigger.SCHEDULED)))
            .thenThrow(IllegalStateException("database unavailable")).thenReturn(success)

        scheduler.runDailySnapshots()
        scheduler.runDailySnapshots()

        verify(snapshots, times(2)).recalculateActive(any(), eq(OperationTrigger.SCHEDULED))
        verify(metrics).dailySnapshotCycle("failed")
        verify(metrics).dailySnapshotCycle("success")
    }
}
