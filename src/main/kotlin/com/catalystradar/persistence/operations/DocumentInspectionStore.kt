package com.catalystradar.persistence.operations

import com.catalystradar.application.operations.*
import com.catalystradar.application.pipeline.PipelineProperties
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class DocumentInspectionStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val pipeline: PipelineProperties,
) {
    private val cursor = OperationsCursor()

    fun search(query: DocumentQuery, generatedAt: Instant, activeOperationRunIds: Set<UUID>): OperationsPage<DocumentListItem> {
        val filters = mapOf(
            "states" to query.states.map { it.name }.sorted().joinToString(","),
            "provider" to (query.provider ?: ""), "ticker" to (query.normalizedTicker ?: ""),
            "title" to (query.normalizedTitle ?: ""), "dueOnly" to query.dueOnly.toString(),
            "runId" to (query.runId?.toString() ?: ""), "ingestionRunId" to (query.ingestionRunId?.toString() ?: ""),
            "from" to (query.from?.toString() ?: ""), "to" to (query.to?.toString() ?: ""),
        )
        val position = query.page.cursor?.let { cursor.decode(it, "documents", filters) }
        val params = mutableMapOf<String, Any?>("limit" to query.page.limit + 1)
        val where = mutableListOf<String>()
        if (query.states.isNotEmpty()) {
            where += "($STATE) IN (:states)"
            params["states"] = query.states.map { it.name }
        }
        query.provider?.let { where += "d.provider = :provider"; params["provider"] = it }
        query.normalizedTicker?.let {
            where += "EXISTS (SELECT 1 FROM source_document_companies sc JOIN companies c ON c.id=sc.company_id WHERE sc.source_document_id=d.id AND c.ticker=:ticker)"
            params["ticker"] = it
        }
        query.normalizedTitle?.let {
            where += "d.title ILIKE :title ESCAPE '\\'"
            params["title"] = "%${it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        }
        query.runId?.let {
            where += "EXISTS (SELECT 1 FROM document_processing_attempts a WHERE a.source_document_id=d.id AND a.operation_run_id=:runId)"
            params["runId"] = it
        }
        query.ingestionRunId?.let { where += "d.first_ingestion_run_id=:ingestionRunId"; params["ingestionRunId"] = it }
        query.from?.let { where += "d.discovered_at >= :from"; params["from"] = Timestamp.from(it) }
        query.to?.let { where += "d.discovered_at < :to"; params["to"] = Timestamp.from(it) }
        position?.let {
            where += "(d.discovered_at, d.id) < (:cursorAt, :cursorId)"
            params["cursorAt"] = Timestamp.from(it.at); params["cursorId"] = it.id
        }
        if (query.dueOnly) {
            val recoverable = if (activeOperationRunIds.isEmpty()) "TRUE" else {
                params["activeIds"] = activeOperationRunIds
                "NOT EXISTS (SELECT 1 FROM document_processing_attempts a WHERE a.source_document_id=d.id AND a.status='RUNNING' AND a.operation_run_id IN (:activeIds))"
            }
            where += "(p.status='PENDING' OR (p.status='RETRYABLE_ERROR' AND p.next_attempt_at <= :generatedAt) OR (p.status='PROCESSING' AND $recoverable))"
            params["generatedAt"] = Timestamp.from(generatedAt)
        }
        val rows = jdbc.query(
            "SELECT $PROJECTION FROM source_documents d LEFT JOIN document_processing p ON p.source_document_id=d.id " +
                (if (where.isEmpty()) "" else "WHERE ${where.joinToString(" AND ")} ") +
                "ORDER BY d.discovered_at DESC, d.id DESC LIMIT :limit", params,
        ) { rs, _ -> listItem(rs) }
        val page = rows.take(query.page.limit)
        val associations = tickers(page.map { it.id })
        val items = page.map { item ->
            val linked = associations[item.id].orEmpty()
            item.copy(tickers = linked.map { it.first }, tickersTruncated = (linked.firstOrNull()?.second ?: 0) > 100)
        }
        val next = if (rows.size > query.page.limit) page.last().let {
            cursor.encode("documents", CursorPosition(it.discoveredAt, it.id), filters)
        } else null
        return OperationsPage(generatedAt, null, items, query.page.limit, next)
    }

    fun detail(id: UUID, generatedAt: Instant): DocumentDetail? {
        val detail = jdbc.query(
            """SELECT $PROJECTION, d.canonical_url, d.provider_document_id, p.completed_at,
                (SELECT COUNT(*) FROM document_processing_attempts a WHERE a.source_document_id=d.id) AS captured_attempts,
                (SELECT COUNT(*) FROM model_runs m WHERE m.source_document_id=d.id) AS model_calls
                FROM source_documents d LEFT JOIN document_processing p ON p.source_document_id=d.id WHERE d.id=:id""",
            mapOf("id" to id),
        ) { rs, _ ->
            val item = listItem(rs)
            val captured = rs.getLong("captured_attempts")
            DocumentDetail(
                generatedAt = generatedAt,
                document = item,
                canonicalUrl = rs.getString("canonical_url"),
                providerDocumentId = rs.getString("provider_document_id"),
                completedAt = rs.instant("completed_at"),
                maxAttempts = pipeline.maxAttempts,
                capturedAttemptCount = captured,
                unrecordedAttemptCount = ((item.attemptCount ?: 0).toLong() - captured).coerceAtLeast(0),
                historyAvailable = captured > 0,
                companies = emptyList(),
                companiesTotal = 0,
                companiesTruncated = false,
                modelCallsRecorded = rs.getLong("model_calls"),
                eventReports = item.eventReports,
                canonicalClusters = item.canonicalClusters,
            )
        }.singleOrNull() ?: return null
        val companies = jdbc.query(
            """SELECT c.id, c.ticker, c.name, COUNT(*) OVER () AS total,
                    s.as_of, s.created_at AS snapshot_created_at
                FROM source_document_companies sc JOIN companies c ON c.id=sc.company_id
                LEFT JOIN LATERAL (
                    SELECT as_of, created_at FROM catalyst_snapshots WHERE company_id=c.id
                    ORDER BY as_of DESC, created_at DESC, id DESC LIMIT 1
                ) s ON TRUE
                WHERE sc.source_document_id=:id ORDER BY c.ticker, c.id LIMIT 100""",
            mapOf("id" to id),
        ) { rs, _ -> LinkedCompany(rs.uuid("id"), rs.getString("ticker"), rs.getString("name"),
            rs.instant("as_of"), rs.instant("snapshot_created_at")) to rs.getLong("total") }
        val total = companies.firstOrNull()?.second ?: 0
        return detail.copy(document = detail.document.copy(tickers = companies.map { it.first.ticker }, tickersTruncated = total > 100),
            companies = companies.map { it.first }, companiesTotal = total, companiesTruncated = total > 100)
    }

    fun body(id: UUID): DocumentBody? = jdbc.query(
        "SELECT id, substring(body FROM 1 FOR 20000) AS text, char_length(body) AS original_characters FROM source_documents WHERE id=:id",
        mapOf("id" to id),
    ) { rs, _ ->
        val count = rs.getInt("original_characters")
        DocumentBody(rs.uuid("id"), rs.getString("text"), count, count > 20000)
    }.singleOrNull()

    private fun tickers(ids: List<UUID>): Map<UUID, List<Pair<String, Long>>> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.query(
            """SELECT documents.id AS document_id, linked.ticker, linked.total
                FROM source_documents documents
                JOIN LATERAL (
                    SELECT c.ticker, COUNT(*) OVER () AS total FROM source_document_companies sc
                    JOIN companies c ON c.id=sc.company_id WHERE sc.source_document_id=documents.id
                    ORDER BY c.ticker, c.id LIMIT 100
                ) linked ON TRUE WHERE documents.id IN (:ids) ORDER BY documents.id, linked.ticker""",
            mapOf("ids" to ids),
        ) { rs, _ -> rs.uuid("document_id") to (rs.getString("ticker") to rs.getLong("total")) }
            .groupBy({ it.first }, { it.second })
    }

    private fun listItem(rs: ResultSet) = DocumentListItem(
        id = rs.uuid("id"),
        provider = rs.getString("provider"),
        title = rs.getString("title"),
        publishedAt = rs.instant("published_at"),
        discoveredAt = requireNotNull(rs.instant("discovered_at")),
        createdAt = requireNotNull(rs.instant("created_at")),
        state = DocumentState.valueOf(rs.getString("state")),
        attemptCount = rs.getObject("attempt_count", Int::class.javaObjectType),
        nextAttemptAt = rs.instant("next_attempt_at"),
        updatedAt = rs.instant("updated_at"),
        lastErrorCode = rs.getString("last_error_code"),
        lastErrorMessage = rs.getString("last_error_message"),
        tickers = emptyList(),
        tickersTruncated = false,
        eventReports = rs.getLong("event_reports"),
        canonicalClusters = rs.getLong("canonical_clusters"),
        firstIngestionRunId = rs.getObject("first_ingestion_run_id", UUID::class.java),
    )

    private fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()
    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

    companion object {
        private const val STATE = """CASE WHEN p.status IS NOT NULL THEN p.status
            WHEN NOT EXISTS (SELECT 1 FROM source_document_companies sc WHERE sc.source_document_id=d.id) THEN 'UNRESOLVED'
            ELSE 'NOT_TRACKED' END"""
        private const val PROJECTION = """d.id, d.provider, d.title, d.published_at, d.discovered_at, d.created_at,
            $STATE AS state, p.attempt_count, p.next_attempt_at, p.updated_at, p.last_error_code, p.last_error_message,
            d.first_ingestion_run_id,
            (SELECT COUNT(*) FROM events e WHERE e.source_document_id=d.id) AS event_reports,
            (SELECT COUNT(DISTINCT e.cluster_id) FROM events e WHERE e.source_document_id=d.id) AS canonical_clusters"""
    }
}
