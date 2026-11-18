package com.catalystradar.api.dto

import com.catalystradar.application.pipeline.DocumentExtraction
import com.catalystradar.application.pipeline.ExtractionStepResult
import com.catalystradar.application.workbench.MarketScanResult
import com.catalystradar.application.workbench.TickerFetchResult
import com.catalystradar.persistence.workbench.NewsSymbolPage
import com.catalystradar.persistence.workbench.NewsSymbolRow
import java.time.Instant
import java.util.UUID

/** [lookback] is an ISO-8601 duration such as `PT2H`; omitted, the scheduled cycle's lookback applies. */
data class MarketScanRequest(val lookback: String? = null)

data class MarketScanResponse(
    val from: Instant,
    val to: Instant,
    val newDocuments: Int,
    val runs: List<IngestionRunResponse>,
)

fun MarketScanResult.toResponse() = MarketScanResponse(from, to, newDocuments, runs.map { it.toResponse() })

data class NewsSymbolResponse(
    val ticker: String,
    val companyName: String?,
    val tracked: Boolean,
    val documents: Int,
    val queuedDocuments: Int,
    val latestTitle: String,
    val latestAt: Instant,
)

data class NewsSymbolsResponse(
    val since: Instant,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val symbols: List<NewsSymbolResponse>,
)

fun NewsSymbolRow.toResponse() = NewsSymbolResponse(ticker, companyName, tracked, documents, queuedDocuments, latestTitle, latestAt)

fun NewsSymbolPage.toResponse(since: Instant, limit: Int, offset: Int) =
    NewsSymbolsResponse(since, total, limit, offset, symbols.map { it.toResponse() })

/** [lookback] is an ISO-8601 duration; omitted, the per-symbol fetch default applies. */
data class TickerFetchRequest(val tickers: List<String>, val lookback: String? = null)

data class TickerFetchResponse(val ticker: String, val newDocuments: Int, val runs: List<IngestionRunResponse>)

data class TickerFetchesResponse(val from: Instant, val to: Instant, val tickers: List<TickerFetchResponse>)

fun TickerFetchResult.toResponse() = TickerFetchResponse(ticker, newDocuments, runs.map { it.toResponse() })

data class WorkbenchExtractionRequest(val documentIds: List<UUID>)

data class DocumentExtractionResponse(
    val documentId: UUID,
    /** Processing status after this step; null when the document was never queued. */
    val status: String?,
    val eventsInserted: Int,
    val eventsReused: Int,
    /** `NOT_QUEUED` or `ALREADY_PROCESSED` when the document was left untouched. */
    val refused: String?,
)

data class ExtractionResponse(val runId: UUID, val status: String, val documents: List<DocumentExtractionResponse>)

fun DocumentExtraction.toResponse() = DocumentExtractionResponse(sourceDocumentId, status?.name, eventsInserted, eventsReused, refused)

fun ExtractionStepResult.toResponse() = ExtractionResponse(runId, status, documents.map { it.toResponse() })
