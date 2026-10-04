package com.catalystradar.persistence.operations

import com.catalystradar.application.operations.*
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class OperationsSummaryStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val models: ModelInspectionStore,
    private val properties: OperationsProperties,
) {
    fun read(window: ActivityWindow, generatedAt: Instant, activeRunIds: Set<UUID>): OverviewInputs {
        val params = mutableMapOf<String, Any?>("from" to Timestamp.from(window.from), "to" to Timestamp.from(window.to),
            "now" to Timestamp.from(generatedAt), "observedSince" to Timestamp.from(generatedAt.minus(properties.dependencyWindow)))
        val active = if (activeRunIds.isEmpty()) "FALSE" else {
            params["activeIds"] = activeRunIds
            "a.operation_run_id IN (:activeIds)"
        }
        val queueRows = """WITH queue AS (
            SELECT d.id,CASE WHEN p.status IS NOT NULL THEN p.status
                WHEN NOT EXISTS (SELECT 1 FROM source_document_companies sc WHERE sc.source_document_id=d.id) THEN 'UNRESOLVED'
                ELSE 'NOT_TRACKED' END AS state,
                (p.status='PENDING' OR (p.status='RETRYABLE_ERROR' AND p.next_attempt_at <= :now)
                    OR (p.status='PROCESSING' AND NOT EXISTS (SELECT 1 FROM document_processing_attempts a
                        WHERE a.source_document_id=d.id AND a.status='RUNNING' AND $active))) AS due,
                CASE WHEN p.status='RETRYABLE_ERROR' THEN p.next_attempt_at ELSE p.created_at END AS due_at
            FROM source_documents d LEFT JOIN document_processing p ON p.source_document_id=d.id
        )"""
        val queue = requireNotNull(jdbc.queryForObject(
            """$queueRows SELECT COUNT(*) FILTER (WHERE state='PENDING') AS pending,
                COUNT(*) FILTER (WHERE state='PROCESSING') AS processing,
                COUNT(*) FILTER (WHERE state='RETRYABLE_ERROR') AS retrying,
                COUNT(*) FILTER (WHERE state='TERMINAL_ERROR') AS terminal,
                COUNT(*) FILTER (WHERE state='UNRESOLVED') AS unresolved,
                COUNT(*) FILTER (WHERE state='NOT_TRACKED') AS not_tracked,
                COUNT(*) FILTER (WHERE due) AS due,
                (SELECT due_at FROM queue WHERE due ORDER BY due_at ASC,id ASC LIMIT 1) AS oldest_due_at,
                (SELECT id FROM queue WHERE due ORDER BY due_at ASC,id ASC LIMIT 1) AS oldest_due_id FROM queue""", params,
        ) { rs, _ ->
            val pending = rs.getLong("pending")
            val retrying = rs.getLong("retrying")
            QueueSummary(pending, rs.getLong("processing"), retrying, rs.getLong("terminal"), rs.getLong("unresolved"),
                rs.getLong("not_tracked"), pending + retrying, rs.getLong("due"), rs.getTimestamp("oldest_due_at")?.toInstant(), rs.getObject("oldest_due_id", UUID::class.java))
        })
        val history = requireNotNull(jdbc.queryForObject("SELECT MIN(started_at) AS history_started_at,COUNT(*) AS runs FROM operation_runs", emptyMap<String, Any>()) { rs, _ ->
            rs.getTimestamp("history_started_at")?.toInstant() to (rs.getLong("runs") > 0)
        })
        val activity = requireNotNull(jdbc.queryForObject(
            """SELECT
                (SELECT COUNT(*) FROM source_documents WHERE discovered_at >= :from AND discovered_at < :to) AS documents_new,
                (SELECT COALESCE(SUM(documents_fetched),0) FROM ingestion_runs WHERE started_at >= :from AND started_at < :to) AS fetched,
                (SELECT COALESCE(SUM(documents_duplicate),0) FROM ingestion_runs WHERE started_at >= :from AND started_at < :to) AS duplicates,
                (SELECT COUNT(*) FROM events WHERE created_at >= :from AND created_at < :to) AS events,
                (SELECT COUNT(*) FROM event_clusters WHERE created_at >= :from AND created_at < :to) AS clusters,
                (SELECT COALESCE(SUM(companies_rescored),0) FROM operation_runs WHERE started_at >= :from AND started_at < :to AND finished_at IS NOT NULL AND capture_complete) AS rescored,
                (SELECT COALESCE(SUM(companies_failed),0) FROM operation_runs WHERE started_at >= :from AND started_at < :to AND finished_at IS NOT NULL AND capture_complete) AS failed""", params,
        ) { rs, _ -> ActivitySummary(rs.getLong("documents_new"), rs.getLong("fetched"), rs.getLong("duplicates"),
            rs.getLong("events"), rs.getLong("clusters"), if (history.second) rs.getLong("rescored") else null,
            if (history.second) rs.getLong("failed") else null, history.second) })
        val successes = requireNotNull(jdbc.queryForObject(
            """SELECT (SELECT MAX(finished_at) FROM ingestion_runs WHERE status='SUCCESS' AND finished_at <= :now) AS ingestion,
                (SELECT MAX(finished_at) FROM operation_runs WHERE kind='DAILY_SNAPSHOTS' AND status='SUCCESS' AND finished_at <= :now) AS snapshots""", params,
        ) { rs, _ -> rs.getTimestamp("ingestion")?.toInstant() to rs.getTimestamp("snapshots")?.toInstant() })
        val activeRuns = if (activeRunIds.isEmpty()) emptyList() else jdbc.query(
            "SELECT * FROM operation_runs r WHERE r.id IN (:activeIds) ORDER BY r.started_at DESC,r.id DESC", params,
        ) { rs, _ -> OperationRunStore.mapRun(rs) }
        val oldestActiveAttempt = jdbc.query(
            """SELECT a.source_document_id,a.operation_run_id,a.started_at FROM document_processing_attempts a
                WHERE a.status='RUNNING' AND $active ORDER BY a.started_at ASC,a.id ASC LIMIT 1""", params,
        ) { rs, _ -> ActiveAttemptObservation(rs.getObject("source_document_id", UUID::class.java), rs.getObject("operation_run_id", UUID::class.java), rs.getTimestamp("started_at").toInstant()) }.singleOrNull()
        val companies = requireNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE active", emptyMap<String, Any>(), Long::class.java))
        return OverviewInputs(companies, queue, activity, models.summary(ModelQuery(window), generatedAt).totals,
            successes.first, successes.second, observations(params), oldestActiveAttempt, activeRuns, history.first)
    }

    private fun observations(params: Map<String, Any?>): List<ProviderObservation> = jdbc.query(
        """WITH observations AS (
            SELECT id,provider,finished_at AS observed_at,status='SUCCESS' AS succeeded,
                CASE WHEN status='SUCCESS' THEN NULL ELSE COALESCE(error_code,'UNKNOWN_FAILURE') END AS error_code
            FROM ingestion_runs WHERE provider IN ('polygon','finnhub') AND finished_at <= :now
                AND status IN ('SUCCESS','PARTIAL','FAILED') AND error_code IS DISTINCT FROM 'CANCELLED'
            UNION ALL
            SELECT id,provider,created_at AS observed_at,success AS succeeded,
                CASE WHEN success THEN NULL ELSE COALESCE(error_code,'UNKNOWN_FAILURE') END AS error_code
            FROM model_runs WHERE provider='openai' AND created_at <= :now
        ) SELECT p.provider,latest.observed_at,latest.succeeded,latest.error_code,
            (SELECT MAX(o.observed_at) FROM observations o WHERE o.provider=p.provider AND o.succeeded) AS last_success_at,
            (SELECT COUNT(*) FROM observations o WHERE o.provider=p.provider AND NOT o.succeeded
                AND o.observed_at >= :observedSince AND o.observed_at < :now) AS failures
            FROM (VALUES ('polygon',0),('finnhub',1),('openai',2)) p(provider,position)
            LEFT JOIN LATERAL (SELECT observed_at,succeeded,error_code FROM observations o WHERE o.provider=p.provider
                ORDER BY observed_at DESC,id DESC LIMIT 1) latest ON TRUE ORDER BY p.position""", params,
    ) { rs, _ -> ProviderObservation(rs.getString("provider"), rs.getTimestamp("observed_at")?.toInstant(),
        rs.getTimestamp("last_success_at")?.toInstant(), rs.getObject("succeeded", Boolean::class.javaObjectType), rs.getLong("failures"), rs.getString("error_code")) }
}
