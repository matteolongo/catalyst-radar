package com.catalystradar.application.ingestion

import com.catalystradar.application.pipeline.PipelineService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Scheduled coordinator for the full ingestion-to-score pipeline. The
 * single-flight guard belongs to [PipelineService] so manual and scheduled
 * triggers share the same protection in this single-deployable POC.
 */
@Component
@ConditionalOnProperty(name = ["catalyst.ingestion.enabled"], havingValue = "true")
class IngestionScheduler(
    private val pipeline: PipelineService,
) {

    private val log = LoggerFactory.getLogger(IngestionScheduler::class.java)

    @Scheduled(fixedDelayString = "\${catalyst.ingestion.interval:PT30M}")
    fun runIngestion() {
        try {
            val result = runBlocking { pipeline.runCycle() }
            log.info(
                "pipeline cycle finished status={} processed={} events={} rescored={} retries={} terminalFailures={}",
                result.status,
                result.documentsProcessed,
                result.eventsExtracted,
                result.companiesRescored,
                result.documentsRetryScheduled,
                result.documentsTerminalFailures,
            )
        } catch (e: Exception) {
            log.error("pipeline cycle failed", e)
        }
    }
}
