package com.catalystradar.application.ingestion

import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.application.operations.OperationalErrors
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.ingestion.IngestionRunRecord
import com.catalystradar.persistence.ingestion.IngestionRunStore
import com.catalystradar.ports.NewsFetchRequest
import com.catalystradar.ports.NewsProvider
import com.catalystradar.ports.ProviderException
import com.catalystradar.ports.RawArticle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

data class IngestionCycleResult(
    val runs: List<IngestionRunRecord>,
    val newDocuments: List<NewDocument> = emptyList(),
)

data class NewDocument(
    val id: UUID,
    val tickers: List<String>,
)

/**
 * Scheduled ingestion orchestration: select universe tickers, fetch the
 * configured provider in chunks, normalize, deduplicate on stable
 * provider id or content hash, and persist new source documents.
 *
 * One IngestionRun row tracks each provider attempt. Per-document
 * persistence failures degrade the run to PARTIAL instead of aborting
 * the remaining page.
 */
@Service
class IngestionService(
    providers: List<NewsProvider>,
    private val properties: IngestionProperties,
    private val companies: CompanyStore,
    private val registrations: SourceDocumentRegistrationService,
    private val runs: IngestionRunStore,
    private val metrics: CatalystMetrics,
) {

    private val log = LoggerFactory.getLogger(IngestionService::class.java)
    private val providersByName = providers.associateBy { it.name }

    suspend fun ingestCycle(now: Instant = Instant.now(), operationRunId: UUID? = null): IngestionCycleResult {
        val tickers = companies.findAllActive().map { it.ticker }
        if (tickers.isEmpty()) {
            log.info("ingestion cycle skipped: no active companies")
            return IngestionCycleResult(emptyList())
        }
        log.info(
            "ingestion cycle started tickers={} primary={} lookback={}",
            tickers.size, properties.provider, properties.lookback,
        )
        val primary = providersByName[properties.provider]
        if (primary == null) {
            val runId = runs.startRun(properties.provider, operationRunId)
            runs.finishRun(runId, IngestionStatus.FAILED, 0, 0, 0, OperationalErrors.message("INGESTION_FAILURE"), "INGESTION_FAILURE")
            return IngestionCycleResult(listOfNotNull(runs.findById(runId)))
        }
        val outcomes = mutableListOf(runProvider(primary, tickers, now, operationRunId))
        val last = outcomes.last()
        if ((last.record.status == IngestionStatus.FAILED || last.record.status == IngestionStatus.PARTIAL) &&
            properties.fallbackProvider != primary.name
        ) {
            providersByName[properties.fallbackProvider]?.let { outcomes += runProvider(it, tickers, now, operationRunId) }
        }
        return IngestionCycleResult(
            runs = outcomes.map { it.record },
            newDocuments = outcomes.flatMap { it.newDocuments },
        )
    }

    private data class ProviderOutcome(
        val record: IngestionRunRecord,
        val newDocuments: List<NewDocument>,
    )

    private suspend fun runProvider(
        provider: NewsProvider,
        tickers: List<String>,
        now: Instant,
        operationRunId: UUID?,
    ): ProviderOutcome {
        val runId = runs.startRun(provider.name, operationRunId)
        val started = System.nanoTime()
        var fetched = 0
        var added = 0
        var duplicates = 0
        var unresolved = 0
        var firstErrorCode: String? = null
        val fresh = mutableListOf<NewDocument>()
        try {
            for (chunk in tickers.chunked(TICKER_CHUNK_SIZE)) {
                val page = provider.fetch(
                    NewsFetchRequest(
                        tickers = chunk,
                        from = now.minus(properties.lookback),
                        to = now,
                        pageSize = properties.pageSize,
                    ),
                )
                fetched += page.articles.size
                for (article in page.articles) {
                    try {
                        when (val registration = registrations.registerIfNew(provider.name, article, now, runId, operationRunId)) {
                            is DocumentRegistration.Queued -> {
                                added++
                                fresh += NewDocument(registration.documentId, article.tickers)
                                metrics.documentIngested(provider.name, "new")
                            }
                            is DocumentRegistration.Unresolved -> {
                                added++
                                unresolved++
                                metrics.documentIngested(provider.name, UNRESOLVED_COMPANY_STATUS)
                            }
                            DocumentRegistration.Duplicate -> {
                                duplicates++
                                metrics.documentIngested(provider.name, "duplicate")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RuntimeException) {
                        if (firstErrorCode == null) firstErrorCode = "PROCESSING_FAILURE"
                        metrics.documentIngested(provider.name, "failed")
                        log.warn(
                            "ingestion run {} skipping failed document provider={} article={}: {}",
                            runId, provider.name, article.providerArticleId, e.message,
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            runCatching {
                finish(runId, IngestionStatus.FAILED, fetched, added, duplicates, "CANCELLED")
            }.onFailure { log.warn("ingestion cancellation recording failed for run {}", runId) }
            throw e
        } catch (e: ProviderException.RateLimited) {
            // Preserve the window: a later cycle re-fetches it idempotently.
            metrics.ingestionRun(provider.name, elapsedMs(started))
            reportUnresolved(runId, provider.name, unresolved)
            return ProviderOutcome(finish(runId, IngestionStatus.PARTIAL, fetched, added, duplicates, firstErrorCode ?: OperationalErrors.code(e)), fresh)
        } catch (e: ProviderException) {
            metrics.ingestionRun(provider.name, elapsedMs(started))
            reportUnresolved(runId, provider.name, unresolved)
            return ProviderOutcome(finish(runId, IngestionStatus.FAILED, fetched, added, duplicates, firstErrorCode ?: OperationalErrors.code(e)), fresh)
        } catch (e: RuntimeException) {
            finish(runId, IngestionStatus.FAILED, fetched, added, duplicates, firstErrorCode ?: "INGESTION_FAILURE")
            throw e
        }
        val status = if (firstErrorCode != null) IngestionStatus.PARTIAL else IngestionStatus.SUCCESS
        metrics.ingestionRun(provider.name, elapsedMs(started))
        reportUnresolved(runId, provider.name, unresolved)
        return ProviderOutcome(finish(runId, status, fetched, added, duplicates, firstErrorCode), fresh)
    }

    /**
     * Counts only, never content: a retained document is explained by
     * its reason, and article text, payloads, and ticker lists stay out
     * of the log.
     */
    private fun reportUnresolved(runId: UUID, provider: String, unresolved: Int) {
        if (unresolved == 0) return
        log.info(
            "ingestion run {} retained {} documents without a configured company provider={} reason={}",
            runId, unresolved, provider, NO_CONFIGURED_COMPANY,
        )
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private fun finish(
        runId: java.util.UUID,
        status: IngestionStatus,
        fetched: Int,
        added: Int,
        duplicates: Int,
        errorCode: String?,
    ): IngestionRunRecord {
        runs.finishRun(runId, status, fetched, added, duplicates, errorCode?.let(OperationalErrors::message), errorCode)
        return requireNotNull(runs.findById(runId)) { "ingestion run vanished: $runId" }
    }

    companion object {
        const val TICKER_CHUNK_SIZE = 20

        /** Why a stored document was kept without being queued. */
        const val NO_CONFIGURED_COMPANY = "NO_CONFIGURED_COMPANY"

        /** Bounded metric status for the same outcome. */
        const val UNRESOLVED_COMPANY_STATUS = "unresolved_company"
    }
}
