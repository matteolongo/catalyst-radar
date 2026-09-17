package com.catalystradar.application.ingestion

import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.ingestion.IngestionRunRecord
import com.catalystradar.persistence.ingestion.IngestionRunStore
import com.catalystradar.persistence.jdbc.toJsonB
import com.catalystradar.ports.NewsFetchRequest
import com.catalystradar.ports.NewsProvider
import com.catalystradar.ports.ProviderException
import com.catalystradar.ports.RawArticle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

data class IngestionCycleResult(val runs: List<IngestionRunRecord>)

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
    private val documents: SourceDocumentStore,
    private val runs: IngestionRunStore,
) {

    private val log = LoggerFactory.getLogger(IngestionService::class.java)
    private val providersByName = providers.associateBy { it.name }

    suspend fun ingestCycle(now: Instant = Instant.now()): IngestionCycleResult {
        val tickers = companies.findAllActive().map { it.ticker }
        if (tickers.isEmpty()) {
            log.info("ingestion cycle skipped: no active companies")
            return IngestionCycleResult(emptyList())
        }
        val primary = providersByName[properties.provider]
        if (primary == null) {
            val runId = runs.startRun(properties.provider)
            runs.finishRun(runId, IngestionStatus.FAILED, 0, 0, 0, "unknown provider: ${properties.provider}")
            return IngestionCycleResult(listOfNotNull(runs.findById(runId)))
        }
        val records = mutableListOf(runProvider(primary, tickers, now))
        val last = records.last()
        if ((last.status == IngestionStatus.FAILED || last.status == IngestionStatus.PARTIAL) &&
            properties.fallbackProvider != primary.name
        ) {
            providersByName[properties.fallbackProvider]?.let { records += runProvider(it, tickers, now) }
        }
        return IngestionCycleResult(records)
    }

    private suspend fun runProvider(
        provider: NewsProvider,
        tickers: List<String>,
        now: Instant,
    ): IngestionRunRecord {
        val runId = runs.startRun(provider.name)
        var fetched = 0
        var added = 0
        var duplicates = 0
        var firstError: String? = null
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
                        if (persistIfNew(provider.name, article, now)) added++ else duplicates++
                    } catch (e: RuntimeException) {
                        if (firstError == null) firstError = e.message
                        log.warn(
                            "ingestion run {} skipping failed document provider={} article={}: {}",
                            runId, provider.name, article.providerArticleId, e.message,
                        )
                    }
                }
            }
        } catch (e: ProviderException.RateLimited) {
            // Preserve the window: a later cycle re-fetches it idempotently.
            return finish(runId, IngestionStatus.PARTIAL, fetched, added, duplicates, "rate limited")
        } catch (e: ProviderException) {
            return finish(runId, IngestionStatus.FAILED, fetched, added, duplicates, e.message)
        }
        val status = if (firstError != null) IngestionStatus.PARTIAL else IngestionStatus.SUCCESS
        return finish(runId, status, fetched, added, duplicates, firstError)
    }

    private fun persistIfNew(provider: String, article: RawArticle, now: Instant): Boolean {
        val normalized = normalizeArticle(article, now)
        val known = normalized.providerDocumentId
            ?.let { documents.findByProviderAndProviderDocumentId(provider, it) }
            ?: documents.findByContentHash(normalized.contentHash)
        if (known != null) return false
        documents.save(
            normalized.toDocument(),
            rawPayload = mapOf(
                "provider" to article.provider,
                "tickers" to article.tickers.joinToString(","),
            ).toJsonB(),
        )
        return true
    }

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
