package com.catalystradar.persistence.workbench

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

data class NewsSymbolQuery(
    val since: Instant,
    val limit: Int = 50,
    val offset: Int = 0,
)

/**
 * A symbol named by stored articles published since the query instant.
 * [tracked] symbols resolve to a configured company; the others are
 * universe candidates (ADR 0002). Counts are documents, not events.
 */
data class NewsSymbolRow(
    val ticker: String,
    val companyName: String?,
    val tracked: Boolean,
    val documents: Int,
    /**
     * Documents waiting for extraction for this company: pending, interrupted
     * or due for retry. Always 0 for an untracked symbol, since nothing is
     * extracted for it even when the article is queued for another company.
     */
    val queuedDocuments: Int,
    val latestTitle: String,
    val latestAt: Instant,
)

data class NewsSymbolPage(val symbols: List<NewsSymbolRow>, val total: Int)

/**
 * Every symbol in the news over a window, tracked or not, for the guided
 * run's first selection. Reads stored data only; it never calls a provider.
 */
@Repository
class NewsSymbolStore(private val jdbc: JdbcClient) {

    fun symbols(query: NewsSymbolQuery): NewsSymbolPage {
        require(query.limit in 1..100) { "limit must be within 1..100" }
        require(query.offset in 0..MAX_OFFSET) { "offset must be within 0..$MAX_OFFSET" }
        val rows = jdbc.sql(
            """
            WITH mentions AS (
                SELECT c.ticker, TRUE AS tracked, sc.source_document_id AS document_id
                FROM source_document_companies sc
                JOIN companies c ON c.id = sc.company_id
                UNION ALL
                SELECT u.ticker, FALSE AS tracked, u.source_document_id AS document_id
                FROM source_document_unresolved_tickers u
                WHERE NOT EXISTS (SELECT 1 FROM companies c WHERE c.ticker = u.ticker)
            )
            SELECT COUNT(*) OVER () AS total, m.ticker,
                   BOOL_OR(m.tracked) AS tracked,
                   MAX(co.name) AS company_name,
                   COUNT(DISTINCT m.document_id) AS documents,
                   COUNT(DISTINCT m.document_id) FILTER (
                       WHERE m.tracked AND p.status IN ('PENDING', 'PROCESSING', 'RETRYABLE_ERROR')
                   ) AS queued,
                   (ARRAY_AGG(sd.title ORDER BY COALESCE(sd.published_at, sd.discovered_at) DESC, sd.id))[1] AS latest_title,
                   MAX(COALESCE(sd.published_at, sd.discovered_at)) AS latest_at
            FROM mentions m
            JOIN source_documents sd ON sd.id = m.document_id
            LEFT JOIN document_processing p ON p.source_document_id = m.document_id
            LEFT JOIN companies co ON co.ticker = m.ticker
            WHERE COALESCE(sd.published_at, sd.discovered_at) >= :since
            GROUP BY m.ticker
            ORDER BY documents DESC, m.ticker ASC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
        )
            .param("since", Timestamp.from(query.since))
            .param("limit", query.limit)
            .param("offset", query.offset)
            .query { rs, _ ->
                rs.getInt("total") to NewsSymbolRow(
                    ticker = rs.getString("ticker"),
                    companyName = rs.getString("company_name"),
                    tracked = rs.getBoolean("tracked"),
                    documents = rs.getInt("documents"),
                    queuedDocuments = rs.getInt("queued"),
                    latestTitle = rs.getString("latest_title"),
                    latestAt = rs.getTimestamp("latest_at").toInstant(),
                )
            }.list()
        return NewsSymbolPage(rows.map { it.second }, rows.firstOrNull()?.first ?: 0)
    }

    companion object {
        const val MAX_OFFSET = 10_000
    }
}
