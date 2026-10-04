package com.catalystradar.persistence.operations

import com.catalystradar.application.operations.*
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class ModelInspectionStore(private val jdbc: NamedParameterJdbcTemplate) {
    private val cursor = OperationsCursor()

    fun search(query: ModelQuery, generatedAt: Instant): OperationsPage<ModelCall> {
        require(query.window != null || query.documentId != null) { "all-date model feeds require a document ID" }
        val filters = mapOf(
            "provider" to (query.provider ?: ""), "operation" to (query.operation ?: ""),
            "model" to (query.model ?: ""), "success" to (query.success?.toString() ?: ""),
            "documentId" to (query.documentId?.toString() ?: ""),
            "attemptId" to (query.attemptId?.toString() ?: ""), "runId" to (query.runId?.toString() ?: ""),
            "from" to (query.window?.from?.toString() ?: ""), "to" to (query.window?.to?.toString() ?: ""),
            "sort" to "created_at DESC,id DESC",
        )
        val position = query.page.cursor?.let { cursor.decode(it, "models", filters) }
        val params = mutableMapOf<String, Any?>("limit" to query.page.limit + 1)
        val where = where(query, params, position)
        val rows = jdbc.query(
            "SELECT $PROJECTION $RELATION $where ORDER BY m.created_at DESC, m.id DESC LIMIT :limit", params,
        ) { rs, _ -> call(rs) }
        val items = rows.take(query.page.limit)
        val next = if (rows.size > query.page.limit) items.last().let {
            cursor.encode("models", CursorPosition(it.createdAt, it.id), filters)
        } else null
        return OperationsPage(generatedAt, query.window, items, query.page.limit, next)
    }

    fun detail(id: UUID): ModelCall? = jdbc.query(
        "SELECT $PROJECTION $RELATION WHERE m.id=:id", mapOf("id" to id),
    ) { rs, _ -> call(rs) }.singleOrNull()

    fun summary(query: ModelQuery, generatedAt: Instant): ModelSummary {
        val window = requireNotNull(query.window) { "model summaries require a resolved activity window" }
        val params = mutableMapOf<String, Any?>()
        val where = where(query, params)
        val totals = requireNotNull(jdbc.queryForObject("SELECT $AGGREGATE $RELATION $where", params) { rs, _ -> usage(rs) })
        val groups = jdbc.query(
            "SELECT m.provider, m.operation, m.model, $AGGREGATE $RELATION $where " +
                "GROUP BY m.provider, m.operation, m.model ORDER BY m.provider ASC, m.operation ASC, m.model ASC LIMIT 51", params,
        ) { rs, _ -> ModelUsageGroup(rs.getString("provider"), rs.getString("operation"), rs.getString("model"), usage(rs)) }
        return ModelSummary(generatedAt, window, totals, groups.take(50), groups.size > 50)
    }

    private fun where(query: ModelQuery, params: MutableMap<String, Any?>, position: CursorPosition? = null): String {
        val clauses = mutableListOf<String>()
        query.window?.let {
            clauses += "m.created_at >= :from AND m.created_at < :to"
            params["from"] = Timestamp.from(it.from)
            params["to"] = Timestamp.from(it.to)
        }
        query.provider?.let { clauses += "m.provider=:provider"; params["provider"] = it }
        query.operation?.let { clauses += "m.operation=:operation"; params["operation"] = it }
        query.model?.let { clauses += "m.model=:model"; params["model"] = it }
        query.success?.let { clauses += "m.success=:success"; params["success"] = it }
        query.documentId?.let { clauses += "m.source_document_id=:documentId"; params["documentId"] = it }
        query.attemptId?.let { clauses += "m.processing_attempt_id=:attemptId"; params["attemptId"] = it }
        query.runId?.let { clauses += "a.operation_run_id=:runId"; params["runId"] = it }
        position?.let {
            clauses += "(m.created_at, m.id) < (:cursorAt, :cursorId)"
            params["cursorAt"] = Timestamp.from(it.at)
            params["cursorId"] = it.id
        }
        return if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
    }

    private fun call(rs: ResultSet): ModelCall {
        val errorCode = rs.getString("error_code") ?: if (!rs.getBoolean("success") || rs.getBoolean("has_error")) "UNKNOWN_FAILURE" else null
        return ModelCall(
            id = rs.getObject("id", UUID::class.java), provider = rs.getString("provider"),
            operation = rs.getString("operation"), model = rs.getString("model"),
            promptVersion = rs.getString("prompt_version"), extractorVersion = rs.getString("extractor_version"),
            sourceDocumentId = rs.getObject("source_document_id", UUID::class.java),
            attemptId = rs.getObject("processing_attempt_id", UUID::class.java),
            runId = rs.getObject("operation_run_id", UUID::class.java),
            inputTokens = rs.getObject("input_tokens", Int::class.javaObjectType),
            outputTokens = rs.getObject("output_tokens", Int::class.javaObjectType),
            latencyMs = rs.getObject("latency_ms", Long::class.javaObjectType),
            estimatedCost = rs.getBigDecimal("estimated_cost"), success = rs.getBoolean("success"),
            errorCode = errorCode, errorMessage = errorCode?.let(OperationalErrors::message),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    private fun usage(rs: ResultSet) = ModelUsageSummary(
        calls = rs.getLong("calls"), successfulCalls = rs.getLong("successful_calls"), failedCalls = rs.getLong("failed_calls"),
        inputTokens = rs.getObject("input_tokens", Long::class.javaObjectType), inputTokensKnownCalls = rs.getLong("input_known"),
        outputTokens = rs.getObject("output_tokens", Long::class.javaObjectType), outputTokensExpectedCalls = rs.getLong("output_expected"),
        outputTokensKnownCalls = rs.getLong("output_known"), estimatedCostUsd = rs.getBigDecimal("estimated_cost"),
        costKnownCalls = rs.getLong("cost_known"), latencyKnownCalls = rs.getLong("latency_known"),
        p50LatencyMs = rs.getObject("p50_latency", Double::class.javaObjectType),
        p95LatencyMs = rs.getObject("p95_latency", Double::class.javaObjectType),
    )

    companion object {
        private const val RELATION = """FROM model_runs m
            LEFT JOIN document_processing_attempts a ON a.id=m.processing_attempt_id"""
        private const val PROJECTION = """m.id, m.provider, m.operation, m.model, m.prompt_version, m.extractor_version,
            m.source_document_id, m.processing_attempt_id, a.operation_run_id, m.input_tokens, m.output_tokens,
            m.latency_ms, m.estimated_cost, m.success, m.error_code, (m.error IS NOT NULL) AS has_error, m.created_at"""
        private const val AGGREGATE = """COUNT(*) AS calls,
            COUNT(*) FILTER (WHERE m.success) AS successful_calls,
            COUNT(*) FILTER (WHERE NOT m.success) AS failed_calls,
            CASE WHEN COUNT(*)=0 OR COUNT(m.input_tokens)>0 THEN COALESCE(SUM(m.input_tokens),0) END AS input_tokens,
            COUNT(m.input_tokens) AS input_known,
            CASE WHEN COUNT(*) FILTER (WHERE m.operation='extract')=0
                OR COUNT(m.output_tokens) FILTER (WHERE m.operation='extract')>0
                THEN COALESCE(SUM(m.output_tokens) FILTER (WHERE m.operation='extract'),0) END AS output_tokens,
            COUNT(*) FILTER (WHERE m.operation='extract') AS output_expected,
            COUNT(m.output_tokens) FILTER (WHERE m.operation='extract') AS output_known,
            CASE WHEN COUNT(*)=0 OR COUNT(m.estimated_cost)>0 THEN COALESCE(SUM(m.estimated_cost),0) END AS estimated_cost,
            COUNT(m.estimated_cost) AS cost_known, COUNT(m.latency_ms) AS latency_known,
            percentile_cont(0.5) WITHIN GROUP (ORDER BY m.latency_ms) FILTER (WHERE m.latency_ms IS NOT NULL) AS p50_latency,
            percentile_cont(0.95) WITHIN GROUP (ORDER BY m.latency_ms) FILTER (WHERE m.latency_ms IS NOT NULL) AS p95_latency"""
    }
}
