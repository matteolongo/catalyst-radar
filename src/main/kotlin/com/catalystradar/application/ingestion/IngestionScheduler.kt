package com.catalystradar.application.ingestion

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scheduled ingestion coordinator. fixedDelay on a single scheduler
 * thread can never overlap with itself; the in-memory guard additionally
 * covers future manual triggers. No distributed lock: v0.1 is a single
 * deployable, and a DB-backed lock returns if multi-instance ever lands.
 */
@Component
@ConditionalOnProperty(name = ["catalyst.ingestion.enabled"], havingValue = "true")
class IngestionScheduler(
    private val service: IngestionService,
) {

    private val log = LoggerFactory.getLogger(IngestionScheduler::class.java)
    private val running = AtomicBoolean(false)

    @Scheduled(fixedDelayString = "\${catalyst.ingestion.interval:PT30M}")
    fun runIngestion() {
        if (!running.compareAndSet(false, true)) {
            log.info("ingestion cycle skipped: previous cycle still running")
            return
        }
        try {
            val result = runBlocking { service.ingestCycle() }
            result.runs.forEach {
                log.info(
                    "ingestion run finished provider={} status={} fetched={} new={} duplicates={}",
                    it.provider, it.status, it.fetched, it.added, it.duplicates,
                )
            }
        } catch (e: Exception) {
            log.error("ingestion cycle failed", e)
        } finally {
            running.set(false)
        }
    }
}
