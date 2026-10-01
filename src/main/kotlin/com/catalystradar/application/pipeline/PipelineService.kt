package com.catalystradar.application.pipeline

import com.catalystradar.application.catalyst.CatalystService
import com.catalystradar.application.clustering.EventClusteringService
import com.catalystradar.application.company.CompanyService
import com.catalystradar.application.event.EventNormalizationService
import com.catalystradar.application.ingestion.IngestionService
import com.catalystradar.domain.company.normalizeTicker
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.ports.ExtractionRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class PipelineResult(
    val status: String,
    val documentsProcessed: Int,
    val eventsExtracted: Int,
    val companiesRescored: Int,
    val error: String? = null,
)

/**
 * End-to-end catalyst pipeline: ingest fresh documents, extract
 * structured candidates for resolved universe companies, normalize
 * and persist events, assign canonical clusters, and recalculate
 * affected companies. A single failing document degrades the cycle
 * to PARTIAL instead of aborting the rest.
 */
@Service
class PipelineService(
    private val ingestion: IngestionService,
    private val extraction: EventExtractionProvider,
    private val normalization: EventNormalizationService,
    private val clustering: EventClusteringService,
    private val catalyst: CatalystService,
    private val documents: SourceDocumentStore,
    private val companies: CompanyService,
    private val metrics: CatalystMetrics,
) {

    private val log = LoggerFactory.getLogger(PipelineService::class.java)

    suspend fun runCycle(now: Instant = Instant.now()): PipelineResult {
        var processed = 0
        var extracted = 0
        val affected = mutableSetOf<UUID>()
        var firstError: String? = null
        try {
            val ingestionResult = ingestion.ingestCycle(now)
            for (fresh in ingestionResult.newDocuments) {
                try {
                    extracted += processDocument(fresh.id, fresh.tickers, affected)
                    processed++
                } catch (e: RuntimeException) {
                    if (firstError == null) firstError = e.message
                    log.warn("pipeline skipping document {}: {}", fresh.id, e.message)
                }
            }
            for (companyId in affected) {
                try {
                    catalyst.recalculate(companyId, now)
                } catch (e: RuntimeException) {
                    if (firstError == null) firstError = e.message
                    log.warn("pipeline skipping recalculation for {}: {}", companyId, e.message)
                }
            }
        } catch (e: RuntimeException) {
            metrics.pipelineCycle("failed")
            return PipelineResult("FAILED", processed, extracted, affected.size, e.message)
        }
        val status = if (firstError != null) "PARTIAL" else "SUCCESS"
        metrics.pipelineCycle(status.lowercase())
        return PipelineResult(status, processed, extracted, affected.size, firstError)
    }

    private suspend fun processDocument(
        documentId: UUID,
        tickers: List<String>,
        affected: MutableSet<UUID>,
    ): Int {
        val document = documents.findById(documentId) ?: return 0
        val resolved = tickers.mapNotNull { ticker ->
            try {
                companies.findByTicker(normalizeTicker(ticker))
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        if (resolved.isEmpty()) return 0
        val events = normalization.processDocument(
            document,
            extraction.extract(ExtractionRequest(document, resolved)),
        )
        for (event in events) {
            clustering.clusterEvent(event.id)
            affected += event.companyId
        }
        return events.size
    }
}
