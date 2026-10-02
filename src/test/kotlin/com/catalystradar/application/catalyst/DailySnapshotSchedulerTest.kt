package com.catalystradar.application.catalyst

import com.catalystradar.observability.CatalystMetrics
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest(
    classes = [DailySnapshotScheduler::class],
    properties = ["catalyst.snapshots.enabled=true"],
)
class DailySnapshotSchedulerTest {

    @MockitoBean
    private lateinit var snapshots: DailySnapshotService

    @MockitoBean
    private lateinit var metrics: CatalystMetrics

    @Autowired
    private lateinit var scheduler: DailySnapshotScheduler

    @Test
    fun `manual trigger runs one daily snapshot cycle`() {
        whenever(snapshots.recalculateActive(any())).thenReturn(
            DailySnapshotResult(DailySnapshotStatus.SUCCESS, 2, 2, 0),
        )

        scheduler.runDailySnapshots()

        verify(snapshots, times(1)).recalculateActive(any())
        verify(metrics).dailySnapshotCycle("success")
    }
}
