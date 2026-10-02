package com.catalystradar.application.ingestion

import com.catalystradar.observability.CatalystMetrics
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

    suspend fun ingestCycle(now: Instant = Instant.now()): IngestionCycleResult {
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
            val runId = runs.startRun(properties.provider)
            runs.finishRun(runId, IngestionStatus.FAILED, 0, 0, 0, "unknown provider: ${properties.provider}")
            return IngestionCycleResult(listOfNotNull(runs.findById(runId)))
        }
        val outcomes = mutableListOf(runProvider(primary, tickers, now))
        val last = outcomes.last()
        if ((last.record.status == IngestionStatus.FAILED || last.record.status == IngestionStatus.PARTIAL) &&
            properties.fallbackProvider != primary.name
        ) {
            providersByName[properties.fallbackProvider]?.let { outcomes += runProvider(it, tickers, now) }
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
    ): ProviderOutcome {
        val runId = runs.startRun(provider.name)
        val started = System.nanoTime()
        var fetched = 0
        var added = 0
        var duplicates = 0
        var firstError: String? = null
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
                        val freshId = registrations.registerIfNew(provider.name, article, now)
                        if (freshId != null) {
                            added++
                            fresh += NewDocument(freshId, article.tickers)
                            metrics.documentIngested(provider.name, "new")
                        } else {
                            duplicates++
                            metrics.documentIngested(provider.name, "duplicate")
                        }
                    } catch (e: RuntimeException) {
                        if (firstError == null) firstError = e.message
                        metrics.documentIngested(provider.name, "failed")
                        log.warn(
                            "ingestion run {} skipping failed document provider={} article={}: {}",
                            runId, provider.name, article.providerArticleId, e.message,
                        )
                    }
                }
            }
        } catch (e: ProviderException.RateLimited) {
            // Preserve the window: a later cycle re-fetches it idempotently.
            metrics.ingestionRun(provider.name, elapsedMs(started))
            return ProviderOutcome(finish(runId, IngestionStatus.PARTIAL, fetched, added, duplicates, "rate limited"), fresh)
        } catch (e: ProviderException) {
            metrics.ingestionRun(provider.name, elapsedMs(started))
            return ProviderOutcome(finish(runId, IngestionStatus.FAILED, fetched, added, duplicates, e.message), fresh)
        }
        val status = if (firstError != null) IngestionStatus.PARTIAL else IngestionStatus.SUCCESS
        metrics.ingestionRun(provider.name, elapsedMs(started))
        return ProviderOutcome(finish(runId, status, fetched, added, duplicates, firstError), fresh)
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private fun finish(
        runId: java.util.UUID,
        status: IngestionStatus,
        fetched: Int,
        added: Int,
        duplicates: Int,
        error: String?,
    ): IngestionRunRecord {
        runs.finishRun(runId, status, fetched, added, duplicates, error)
        return requireNotNull(runs.findById(runId)) { "ingestion run vanished: $runId" }
    }

    companion object {
        const val TICKER_CHUNK_SIZE = 20
    }
}
