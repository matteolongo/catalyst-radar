package com.catalystradar.api.internal

import com.catalystradar.api.dto.WorkbenchExtractionRequest
import com.catalystradar.api.dto.ExtractionResponse
import com.catalystradar.api.dto.MarketScanRequest
import com.catalystradar.api.dto.MarketScanResponse
import com.catalystradar.api.dto.NewsSymbolsResponse
import com.catalystradar.api.dto.TickerFetchRequest
import com.catalystradar.api.dto.TickerFetchesResponse
import com.catalystradar.api.dto.toResponse
import com.catalystradar.application.ingestion.IngestionProperties
import com.catalystradar.application.pipeline.PipelineProperties
import com.catalystradar.application.workbench.WorkbenchService
import com.catalystradar.common.InvalidInputException
import com.catalystradar.common.requireValidInput
import com.catalystradar.domain.company.normalizeTicker
import com.catalystradar.persistence.workbench.NewsSymbolQuery
import com.catalystradar.persistence.workbench.NewsSymbolStore
import kotlinx.coroutines.runBlocking
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Guided run (operator workbench): one route per pipeline stage, so a
 * person chooses what moves to the next one. Admin key required by the auth
 * filter. Steps answer `409 PIPELINE_BUSY` while a cycle or another step runs.
 * Scoring is the existing `POST /internal/companies/{ticker}/recalculate`.
 */
@RestController
@RequestMapping("/internal/workbench")
class InternalWorkbenchController(
    private val workbench: WorkbenchService,
    private val symbols: NewsSymbolStore,
    private val ingestion: IngestionProperties,
    private val pipeline: PipelineProperties,
) {

    /** Step 1: store the window's market news. Calls the news provider; extracts nothing. */
    @PostMapping("/scans")
    fun scan(@RequestBody(required = false) request: MarketScanRequest?): ResponseEntity<MarketScanResponse> {
        val lookback = request?.lookback?.let(::parseLookback) ?: ingestion.lookback
        requireWindow(lookback, ingestion.marketScanMaxLookback)
        val now = Instant.now()
        return noStore(runBlocking { workbench.scan(now.minus(lookback), now) }.toResponse())
    }

    /** Step 1 result: every symbol named by articles published since [since], tracked or not. */
    @GetMapping("/symbols")
    fun symbols(
        @RequestParam(required = false) since: Instant?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): ResponseEntity<NewsSymbolsResponse> {
        requireValidInput(limit in 1..100) { "limit must be within 1..100" }
        requireValidInput(offset in 0..NewsSymbolStore.MAX_OFFSET) { "offset must be within 0..${NewsSymbolStore.MAX_OFFSET}" }
        val now = Instant.now()
        val from = since ?: now.minus(DEFAULT_SYMBOL_WINDOW)
        requireValidInput(!from.isAfter(now)) { "since must not be in the future" }
        requireValidInput(!from.isBefore(now.minus(MAX_SYMBOL_WINDOW))) { "since must be within the last 30 days" }
        val page = symbols.symbols(NewsSymbolQuery(since = from, limit = limit, offset = offset))
        return noStore(page.toResponse(from, limit, offset))
    }

    /** Step 2: fetch the chosen tracked symbols' news history. Calls the provider once per symbol; extracts nothing. */
    @PostMapping("/fetches")
    fun fetch(@RequestBody request: TickerFetchRequest): ResponseEntity<TickerFetchesResponse> {
        requireValidInput(request.tickers.isNotEmpty()) { "tickers must not be empty" }
        requireValidInput(request.tickers.size <= MAX_TICKERS) { "at most $MAX_TICKERS tickers per fetch" }
        val tickers = request.tickers.map(::normalizeTicker).distinct()
        val lookback = request.lookback?.let(::parseLookback) ?: ingestion.tickerFetchDefaultLookback
        requireWindow(lookback, ingestion.tickerFetchMaxLookback)
        val now = Instant.now()
        val from = now.minus(lookback)
        val results = runBlocking { workbench.fetch(tickers, from, now) }
        return noStore(TickerFetchesResponse(from, now, results.map { it.toResponse() }))
    }

    /** Step 3: extract only the chosen queued articles. Calls the LLM; does not rescore. */
    @PostMapping("/extractions")
    fun extract(@RequestBody request: WorkbenchExtractionRequest): ResponseEntity<ExtractionResponse> {
        requireValidInput(request.documentIds.isNotEmpty()) { "documentIds must not be empty" }
        requireValidInput(request.documentIds.size <= pipeline.batchSize) { "at most ${pipeline.batchSize} documents per extraction" }
        return noStore(runBlocking { workbench.extract(request.documentIds) }.toResponse())
    }

    private fun parseLookback(raw: String): Duration =
        try {
            Duration.parse(raw.trim())
        } catch (e: DateTimeParseException) {
            throw InvalidInputException("lookback must be an ISO-8601 duration such as P7D")
        }

    private fun requireWindow(lookback: Duration, max: Duration) =
        requireValidInput(!lookback.isNegative && !lookback.isZero && lookback <= max) {
            "lookback must be positive and at most $max"
        }

    private fun <T : Any> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    companion object {
        const val MAX_TICKERS = 10
        val DEFAULT_SYMBOL_WINDOW: Duration = Duration.ofDays(1)
        val MAX_SYMBOL_WINDOW: Duration = Duration.ofDays(30)
    }
}
