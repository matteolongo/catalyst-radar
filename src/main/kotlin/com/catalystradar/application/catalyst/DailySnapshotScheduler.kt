package com.catalystradar.application.catalyst

import com.catalystradar.observability.CatalystMetrics
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-process daily refresh coordinator. It makes no provider or LLM calls;
 * the guard prevents overlapping scheduled runs in this POC deployment.
 */
@Component
@ConditionalOnProperty(name = ["catalyst.snapshots.enabled"], havingValue = "true")
class DailySnapshotScheduler(
    private val snapshots: DailySnapshotService,
    private val metrics: CatalystMetrics,
) {

    private val log = LoggerFactory.getLogger(DailySnapshotScheduler::class.java)
    private val running = AtomicBoolean(false)

    @Scheduled(fixedDelayString = "\${catalyst.snapshots.interval:PT24H}")
    fun runDailySnapshots() {
        if (!running.compareAndSet(false, true)) {
            metrics.dailySnapshotCycle("skipped")
            log.warn("daily snapshot cycle skipped because another cycle is running")
            return
        }
        try {
            val result = snapshots.recalculateActive()
            metrics.dailySnapshotCycle(result.status.name.lowercase())
            log.info(
                "daily snapshot cycle finished status={} considered={} recalculated={} failures={}",
                result.status,
                result.companiesConsidered,
                result.companiesRecalculated,
                result.failures,
            )
        } catch (e: RuntimeException) {
            metrics.dailySnapshotCycle("failed")
            log.error("daily snapshot cycle failed", e)
        } finally {
            running.set(false)
        }
    }
}
