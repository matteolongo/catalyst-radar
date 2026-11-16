package com.catalystradar.persistence.universe

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class CandidateQuery(
    val since: Instant,
    val minDocuments: Int = 1,
    val limit: Int = 50,
    val offset: Int = 0,
)

/**
 * A ticker seen in stored news that no configured company explains.
 * [documents] counts stored documents naming it, not events: syndicated
 * copies of one story each count. [neverQueuedDocuments] is the subset that
 * was stored as unresolved and would be queued if the ticker were promoted.
 */
data class CandidateRow(
    val ticker: String,
    val documents: Int,
    val neverQueuedDocuments: Int,
    val firstDiscoveredAt: Instant,
    val lastDiscoveredAt: Instant,
)

data class CandidatePage(
    val candidates: List<CandidateRow>,
    val total: Int,
)

/** Ledger of provider tickers that resolved to no configured company (ADR 0002). */
@Repository
class UnresolvedTickerStore(private val jdbc: JdbcClient) {

    fun record(sourceDocumentId: UUID, tickers: Collection<String>) {
        tickers.distinct().forEach { ticker ->
            jdbc.sql(
                """
                INSERT INTO source_document_unresolved_tickers (source_document_id, ticker)
                VALUES (:documentId, :ticker)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).param("documentId", sourceDocumentId).param("ticker", ticker).update()
        }
    }

    fun findTickers(sourceDocumentId: UUID): List<String> =
        jdbc.sql("SELECT ticker FROM source_document_unresolved_tickers WHERE source_document_id = :id ORDER BY ticker")
            .param("id", sourceDocumentId)
            .query { rs, _ -> rs.getString("ticker") }
            .list()

    /**
     * Ranked by stored documents, then ticker. Tickers that have since become
     * configured companies drop out. Offset paging is bounded, as for discovery.
     */
    fun candidates(query: CandidateQuery): CandidatePage {
        require(query.limit in 1..100) { "limit must be within 1..100" }
        require(query.offset in 0..MAX_OFFSET) { "offset must be within 0..$MAX_OFFSET" }
        require(query.minDocuments >= 1) { "minDocuments must be positive" }
        val rows = jdbc.sql(
            """
            SELECT COUNT(*) OVER () AS total, u.ticker,
                   COUNT(*) AS documents,
                   COUNT(*) FILTER (WHERE p.source_document_id IS NULL) AS never_queued,
                   MIN(sd.discovered_at) AS first_discovered_at,
                   MAX(sd.discovered_at) AS last_discovered_at
            FROM source_document_unresolved_tickers u
            JOIN source_documents sd ON sd.id = u.source_document_id
            LEFT JOIN document_processing p ON p.source_document_id = u.source_document_id
            WHERE sd.discovered_at >= :since
              AND NOT EXISTS (SELECT 1 FROM companies c WHERE c.ticker = u.ticker)
            GROUP BY u.ticker
            HAVING COUNT(*) >= :minDocuments
            ORDER BY documents DESC, u.ticker ASC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
        )
            .param("since", Timestamp.from(query.since))
            .param("minDocuments", query.minDocuments)
            .param("limit", query.limit)
            .param("offset", query.offset)
            .query { rs, _ ->
                rs.getInt("total") to CandidateRow(
                    ticker = rs.getString("ticker"),
                    documents = rs.getInt("documents"),
                    neverQueuedDocuments = rs.getInt("never_queued"),
                    firstDiscoveredAt = rs.getTimestamp("first_discovered_at").toInstant(),
                    lastDiscoveredAt = rs.getTimestamp("last_discovered_at").toInstant(),
                )
            }.list()
        return CandidatePage(rows.map { it.second }, rows.firstOrNull()?.first ?: 0)
    }

    companion object {
        const val MAX_OFFSET = 10_000
    }
}
