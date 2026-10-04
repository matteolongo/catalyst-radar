package com.catalystradar.persistence.operations

import com.catalystradar.application.ingestion.IngestionStatus
import com.catalystradar.application.operations.*
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Repository
class OperationRunStore(private val jdbc: NamedParameterJdbcTemplate, transactionManager: PlatformTransactionManager) {
    private val cursor = OperationsCursor()
    private val transaction = TransactionTemplate(transactionManager)

    fun begin(id: UUID, kind: OperationKind, trigger: OperationTrigger, asOf: Instant, now: Instant) {
        val params = mapOf("id" to id, "kind" to kind.name, "trigger" to trigger.name,
            "phase" to if (kind == OperationKind.PIPELINE) "INGESTION" else "SCORING",
            "asOf" to Timestamp.from(asOf), "now" to Timestamp.from(now),
            "message" to OperationalErrors.message("UNFINISHED_PREVIOUS_RUN"))
        transaction.executeWithoutResult {
            jdbc.update("""UPDATE document_processing_attempts SET status='INTERRUPTED',finished_at=NULL,
                updated_at=:now,error_code='UNFINISHED_PREVIOUS_RUN',error_message=:message
                WHERE status='RUNNING' AND operation_run_id IN
                    (SELECT id FROM operation_runs WHERE kind=:kind AND status='RUNNING')""", params)
            jdbc.update("""UPDATE operation_runs SET status='INTERRUPTED',finished_at=NULL,capture_complete=FALSE,
                updated_at=:now,error_code='UNFINISHED_PREVIOUS_RUN',error_message=:message
                WHERE kind=:kind AND status='RUNNING'""", params)
            jdbc.update("""INSERT INTO operation_runs(id,kind,trigger_type,status,phase,as_of,started_at,updated_at)
                VALUES(:id,:kind,:trigger,'RUNNING',:phase,:asOf,:now,:now)""", params)
        }
    }

    fun phase(id: UUID, phase: OperationPhase, now: Instant) {
        require(phase != OperationPhase.FINISHED) { "finish records terminal outcomes" }
        val updated = jdbc.update("""UPDATE operation_runs SET phase=:phase,updated_at=:now,
            ingestion_finished_at=CASE WHEN kind='PIPELINE' AND :phase='PROCESSING'
                THEN COALESCE(ingestion_finished_at,:now) ELSE ingestion_finished_at END,
            processing_finished_at=CASE WHEN kind='PIPELINE' AND :phase='SCORING'
                THEN COALESCE(processing_finished_at,:now) ELSE processing_finished_at END
            WHERE id=:id AND status='RUNNING' AND (kind='PIPELINE' OR :phase='SCORING')""",
            mapOf("id" to id, "phase" to phase.name, "now" to Timestamp.from(now)))
        check(updated == 1) { "operation is not running or phase is invalid for its kind" }
    }

    fun progress(id: UUID, counts: OperationCounts, now: Instant) {
        val params = counts.parameters() + mapOf("id" to id, "now" to Timestamp.from(now))
        check(jdbc.update("UPDATE operation_runs SET $countAssignments,updated_at=:now WHERE id=:id AND status='RUNNING'", params) == 1) {
            "operation is not running"
        }
    }

    fun finish(id: UUID, status: OperationStatus, counts: OperationCounts?, code: String?, captureComplete: Boolean, now: Instant) {
        require(status != OperationStatus.RUNNING) { "finish requires a terminal status" }
        require(!captureComplete || counts != null) { "complete capture requires final counts" }
        val params = (counts?.parameters() ?: emptyMap()) + mapOf("id" to id, "status" to status.name,
            "code" to code, "message" to code?.let(OperationalErrors::message), "complete" to captureComplete, "now" to Timestamp.from(now))
        val counters = if (counts == null) "" else "$countAssignments,"
        check(jdbc.update("""UPDATE operation_runs SET ${counters}status=:status,phase='FINISHED',finished_at=:now,
            updated_at=:now,error_code=:code,error_message=:message,capture_complete=:complete
            WHERE id=:id AND status='RUNNING'""", params) == 1) { "operation is not running" }
    }

    fun issue(id: UUID, phase: OperationPhase, code: String, documentId: UUID?, companyId: UUID?, now: Instant) {
        require(phase != OperationPhase.FINISHED) { "issues require an execution phase" }
        jdbc.update("""INSERT INTO operation_run_issues(id,operation_run_id,phase,source_document_id,company_id,error_code,error_message,created_at)
            VALUES(:issueId,:id,:phase,:document,:company,:code,:message,:now)""",
            mapOf("issueId" to UUID.randomUUID(), "id" to id, "phase" to phase.name, "document" to documentId,
                "company" to companyId, "code" to code, "message" to OperationalErrors.message(code), "now" to Timestamp.from(now)))
    }

    private fun OperationCounts.parameters(): Map<String, Any> = mapOf(
        "documentsConsidered" to documentsConsidered, "documentsCompleted" to documentsCompleted,
        "documentsSkipped" to documentsSkipped, "documentsRetryScheduled" to documentsRetryScheduled,
        "documentsTerminalFailures" to documentsTerminalFailures, "eventsInserted" to eventsInserted,
        "eventsReused" to eventsReused, "companiesConsidered" to companiesConsidered,
        "companiesRescored" to companiesRescored, "companiesFailed" to companiesFailed)

    fun search(query: RunQuery, generatedAt: Instant): OperationsPage<OperationRun> {
        val filters = mapOf("from" to query.window.from.toString(), "to" to query.window.to.toString(),
            "kind" to (query.kind?.name ?: ""), "status" to (query.status?.name ?: ""))
        val params = mutableMapOf<String, Any?>("from" to Timestamp.from(query.window.from), "to" to Timestamp.from(query.window.to), "limit" to query.page.limit + 1)
        val clauses = mutableListOf("r.started_at >= :from", "r.started_at < :to")
        query.kind?.let { clauses += "r.kind=:kind"; params["kind"] = it.name }
        query.status?.let { clauses += "r.status=:status"; params["status"] = it.name }
        query.page.cursor?.let {
            val position = cursor.decode(it, "runs", filters)
            clauses += "(r.started_at,r.id) < (:cursorAt,:cursorId)"
            params["cursorAt"] = Timestamp.from(position.at); params["cursorId"] = position.id
        }
        val rows = jdbc.query("SELECT r.* FROM operation_runs r WHERE ${clauses.joinToString(" AND ")} ORDER BY r.started_at DESC,r.id DESC LIMIT :limit", params) { rs, _ -> mapRun(rs) }
        val items = rows.take(query.page.limit)
        val next = if (rows.size > query.page.limit) items.last().let { cursor.encode("runs", CursorPosition(it.startedAt, it.id), filters) } else null
        return OperationsPage(generatedAt, query.window, items, query.page.limit, next)
    }

    fun detail(id: UUID, generatedAt: Instant): OperationRunDetail? = jdbc.query(
        """SELECT r.*,
            (SELECT COUNT(*) FROM ingestion_runs i WHERE i.operation_run_id=r.id) AS ingestion_runs,
            (SELECT COUNT(*) FROM document_processing_attempts a WHERE a.operation_run_id=r.id) AS attempts,
            (SELECT COUNT(*) FROM operation_run_issues i WHERE i.operation_run_id=r.id) AS issues
            FROM operation_runs r WHERE r.id=:id""", mapOf("id" to id),
    ) { rs, _ ->
        val run = mapRun(rs)
        val phases = if (run.kind == OperationKind.DAILY_SNAPSHOTS) listOf(timing("SCORING", run.startedAt, run.finishedAt)) else {
            val ingested = rs.getTimestamp("ingestion_finished_at")?.toInstant()
            val processed = rs.getTimestamp("processing_finished_at")?.toInstant()
            listOf(timing("INGESTION", run.startedAt, ingested), timing("PROCESSING", ingested, processed), timing("SCORING", processed, run.finishedAt))
        }
        OperationRunDetail(generatedAt, run, rs.getLong("ingestion_runs"), rs.getLong("attempts"), rs.getLong("issues"), phases)
    }.singleOrNull()

    fun issues(id: UUID, page: PageRequest, generatedAt: Instant): OperationsPage<OperationIssue> {
        val filters = mapOf("runId" to id.toString())
        val params = mutableMapOf<String, Any?>("id" to id, "limit" to page.limit + 1)
        var positionSql = ""
        page.cursor?.let {
            val position = cursor.decode(it, "issues", filters)
            positionSql = "AND (i.created_at,i.id) < (:cursorAt,:cursorId)"
            params["cursorAt"] = Timestamp.from(position.at); params["cursorId"] = position.id
        }
        val rows = jdbc.query(
            """SELECT i.id,i.operation_run_id,i.phase,i.source_document_id,i.company_id,c.ticker,i.error_code,i.created_at
                FROM operation_run_issues i LEFT JOIN companies c ON c.id=i.company_id
                WHERE i.operation_run_id=:id $positionSql ORDER BY i.created_at DESC,i.id DESC LIMIT :limit""", params,
        ) { rs, _ ->
            val code = rs.getString("error_code")
            OperationIssue(rs.getObject("id", UUID::class.java), rs.getObject("operation_run_id", UUID::class.java),
                IssuePhase.valueOf(rs.getString("phase")), rs.getObject("source_document_id", UUID::class.java),
                rs.getObject("company_id", UUID::class.java), rs.getString("ticker"), code, OperationalErrors.message(code), rs.getTimestamp("created_at").toInstant())
        }
        val items = rows.take(page.limit)
        val next = if (rows.size > page.limit) items.last().let { cursor.encode("issues", CursorPosition(it.createdAt, it.id), filters) } else null
        return OperationsPage(generatedAt, null, items, page.limit, next)
    }

    fun ingestion(query: IngestionQuery, generatedAt: Instant): OperationsPage<IngestionRunInspection> {
        require(query.window != null || query.runId != null || query.ingestionRunId != null) { "all-date ingestion feeds require a run ID or exact ingestion ID" }
        val filters = mapOf("from" to (query.window?.from?.toString() ?: ""), "to" to (query.window?.to?.toString() ?: ""),
            "provider" to (query.provider ?: ""), "status" to (query.status?.name ?: ""), "runId" to (query.runId?.toString() ?: ""),
            "ingestionRunId" to (query.ingestionRunId?.toString() ?: ""))
        val params = mutableMapOf<String, Any?>("limit" to query.page.limit + 1)
        val clauses = mutableListOf<String>()
        query.window?.let {
            clauses += "i.started_at >= :from AND i.started_at < :to"
            params["from"] = Timestamp.from(it.from); params["to"] = Timestamp.from(it.to)
        }
        query.provider?.let { clauses += "i.provider=:provider"; params["provider"] = it }
        query.status?.let { clauses += "i.status=:status"; params["status"] = it.name }
        query.runId?.let { clauses += "i.operation_run_id=:runId"; params["runId"] = it }
        query.ingestionRunId?.let { clauses += "i.id=:id"; params["id"] = it }
        query.page.cursor?.let {
            val position = cursor.decode(it, "ingestion", filters)
            clauses += "(i.started_at,i.id) < (:cursorAt,:cursorId)"
            params["cursorAt"] = Timestamp.from(position.at); params["cursorId"] = position.id
        }
        val rows = jdbc.query(
            """SELECT i.id,i.provider,i.status,i.documents_fetched,i.documents_new,i.documents_duplicate,
                i.error_code,i.started_at,i.finished_at,i.operation_run_id FROM ingestion_runs i
                WHERE ${clauses.joinToString(" AND ")} ORDER BY i.started_at DESC,i.id DESC LIMIT :limit""", params,
        ) { rs, _ ->
            val status = IngestionStatus.valueOf(rs.getString("status"))
            val code = rs.getString("error_code") ?: if (status == IngestionStatus.FAILED || status == IngestionStatus.PARTIAL) "UNKNOWN_FAILURE" else null
            val start = rs.getTimestamp("started_at").toInstant()
            val finish = rs.getTimestamp("finished_at")?.toInstant()
            IngestionRunInspection(rs.getObject("id", UUID::class.java), rs.getString("provider"), status,
                rs.getInt("documents_fetched"), rs.getInt("documents_new"), rs.getInt("documents_duplicate"),
                code?.let(OperationalErrors::message), finish, start, duration(start, finish), rs.getObject("operation_run_id", UUID::class.java), code)
        }
        val items = rows.take(query.page.limit)
        val next = if (rows.size > query.page.limit) items.last().let { cursor.encode("ingestion", CursorPosition(it.startedAt, it.id), filters) } else null
        return OperationsPage(generatedAt, query.window, items, query.page.limit, next)
    }

    private fun timing(phase: String, start: Instant?, finish: Instant?) = OperationPhaseTiming(phase, start, finish, duration(start, finish))

    companion object {
        private const val countAssignments = """documents_considered=:documentsConsidered,documents_completed=:documentsCompleted,
            documents_skipped=:documentsSkipped,documents_retry_scheduled=:documentsRetryScheduled,
            documents_terminal_failures=:documentsTerminalFailures,events_inserted=:eventsInserted,events_reused=:eventsReused,
            companies_considered=:companiesConsidered,companies_rescored=:companiesRescored,companies_failed=:companiesFailed"""

        internal fun duration(start: Instant?, finish: Instant?): Long? = if (start != null && finish != null) Duration.between(start, finish).toMillis().coerceAtLeast(0) else null

        internal fun mapRun(rs: ResultSet): OperationRun {
            val status = OperationStatus.valueOf(rs.getString("status"))
            val code = rs.getString("error_code") ?: if (status in setOf(OperationStatus.PARTIAL, OperationStatus.FAILED, OperationStatus.CANCELLED, OperationStatus.INTERRUPTED)) "UNKNOWN_FAILURE" else null
            val start = rs.getTimestamp("started_at").toInstant()
            val finish = rs.getTimestamp("finished_at")?.toInstant()
            return OperationRun(
                rs.getObject("id", UUID::class.java), OperationKind.valueOf(rs.getString("kind")), OperationTrigger.valueOf(rs.getString("trigger_type")),
                status, false, OperationPhase.valueOf(rs.getString("phase")), rs.getTimestamp("as_of").toInstant(), start, finish,
                rs.getTimestamp("updated_at").toInstant(), duration(start, finish), rs.getBoolean("capture_complete"),
                rs.getInt("documents_considered"), rs.getInt("documents_completed"), rs.getInt("documents_skipped"),
                rs.getInt("documents_retry_scheduled"), rs.getInt("documents_terminal_failures"), rs.getInt("events_inserted"),
                rs.getInt("events_reused"), rs.getInt("companies_considered"), rs.getInt("companies_rescored"), rs.getInt("companies_failed"),
                code, code?.let(OperationalErrors::message),
            )
        }
    }
}
