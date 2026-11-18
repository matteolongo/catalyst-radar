package com.catalystradar.application.workbench

import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.ingestion.IngestionService
import com.catalystradar.application.pipeline.ExtractionStepResult
import com.catalystradar.application.pipeline.PipelineService
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.ingestion.IngestionRunRecord
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** A cycle or another operator step holds the pipeline guard; nothing was done. */
class PipelineBusyException : RuntimeException("a pipeline cycle or another step is already running")

data class MarketScanResult(val from: Instant, val to: Instant, val runs: List<IngestionRunRecord>, val newDocuments: Int)

data class TickerFetchResult(val ticker: String, val runs: List<IngestionRunRecord>, val newDocuments: Int)

/**
 * The guided run: each pipeline stage as a separate operator step, so a
 * person chooses what moves on. Scan the news, choose symbols, fetch their
 * history, choose articles, extract them; scoring stays the existing
 * per-company recalculation. Every step shares the cycle's guard.
 */
@Service
class WorkbenchService(
    private val pipeline: PipelineService,
    private val ingestion: IngestionService,
    private val companies: CompanyStore,
) {

    /** Stores the window's market news and queues tracked companies' articles; extracts nothing. */
    suspend fun scan(from: Instant, now: Instant = Instant.now()): MarketScanResult {
        val result = pipeline.exclusively { ingestion.ingestMarket(from, now) } ?: throw PipelineBusyException()
        return MarketScanResult(from, now, result.runs, result.newDocuments.size)
    }

    /**
     * Fetches each symbol's news history; extracts nothing. Every symbol must
     * be tracked, checked before any provider call.
     */
    suspend fun fetch(tickers: List<String>, from: Instant, now: Instant = Instant.now()): List<TickerFetchResult> {
        val tracked = tickers.distinct().map { ticker ->
            companies.findByTicker(ticker)?.takeIf { it.active }?.ticker ?: throw CompanyNotFoundException(ticker)
        }
        return pipeline.exclusively {
            tracked.map { ticker ->
                val result = ingestion.ingestTicker(ticker, from, now)
                TickerFetchResult(ticker, result.runs, result.newDocuments.size)
            }
        } ?: throw PipelineBusyException()
    }

    suspend fun extract(sourceDocumentIds: List<UUID>, now: Instant = Instant.now()): ExtractionStepResult =
        pipeline.extractDocuments(sourceDocumentIds, now) ?: throw PipelineBusyException()
}
