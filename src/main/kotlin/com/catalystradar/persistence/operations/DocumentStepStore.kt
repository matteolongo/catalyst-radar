package com.catalystradar.persistence.operations

import com.catalystradar.application.operations.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Repository
class DocumentStepStore(private val jdbc: NamedParameterJdbcTemplate, @Qualifier("operationsClock") private val clock: Clock) {
    private val cursor = OperationsCursor()

    fun now(): Instant = clock.instant()

    fun start(runId: UUID, documentId: UUID, attemptId: UUID?, stage: DocumentStage, startedAt: Instant = now(), inputCount: Int? = null): UUID {
        val id = UUID.randomUUID()
        jdbc.update("""INSERT INTO document_processing_steps(id,operation_run_id,source_document_id,processing_attempt_id,
            stage,sequence,status,started_at,updated_at,input_count) VALUES(:id,:run,:document,:attempt,:stage,:sequence,'RUNNING',:at,:at,:input)""",
            mapOf("id" to id, "run" to runId, "document" to documentId, "attempt" to attemptId, "stage" to stage.name,
                "sequence" to stage.ordinal + 1, "at" to Timestamp.from(startedAt), "input" to inputCount))
        return id
    }

    fun finish(id: UUID, status: StepStatus = StepStatus.SUCCEEDED, outputCount: Int? = null, code: String? = null,
               eventsInserted: Int? = null, eventsReused: Int? = null, finishedAt: Instant = now()) {
        require(status != StepStatus.RUNNING)
        check(jdbc.update("""UPDATE document_processing_steps SET status=:status,finished_at=:finish,updated_at=:now,
            output_count=:output,events_inserted=:inserted,events_reused=:reused,error_code=:code,error_message=:message
            WHERE id=:id AND status='RUNNING'""", mapOf("id" to id, "status" to status.name,
                "finish" to if (status == StepStatus.INTERRUPTED) null else Timestamp.from(finishedAt), "now" to Timestamp.from(finishedAt),
                "output" to outputCount, "inserted" to eventsInserted, "reused" to eventsReused,
                "code" to code, "message" to code?.let(OperationalErrors::message))) == 1) { "step is not running" }
    }

    fun requirePersistenceStep(id: UUID, documentId: UUID, attemptId: UUID?) {
        require(attemptId != null) { "a persistence step requires an attempt" }
        check(jdbc.queryForObject("""SELECT COUNT(*) FROM document_processing_steps WHERE id=:id
            AND source_document_id=:document AND processing_attempt_id=:attempt AND stage='EVENT_PERSISTENCE' AND status='RUNNING'""",
            mapOf("id" to id, "document" to documentId, "attempt" to attemptId), Long::class.java) == 1L) {
            "persistence step does not belong to this document attempt"
        }
    }

    fun search(documentId: UUID, runId: UUID?, attemptId: UUID?, page: PageRequest, generatedAt: Instant): OperationsPage<DocumentStep> {
        val filters = mapOf("documentId" to documentId.toString(), "runId" to (runId?.toString() ?: ""), "attemptId" to (attemptId?.toString() ?: ""))
        val params = mutableMapOf<String, Any?>("document" to documentId, "limit" to page.limit + 1)
        val where = mutableListOf("s.source_document_id=:document")
        runId?.let { where += "s.operation_run_id=:run"; params["run"] = it }
        attemptId?.let { where += "s.processing_attempt_id=:attempt"; params["attempt"] = it }
        page.cursor?.let { val p = cursor.decode(it, "steps", filters); where += "(s.started_at,s.id) < (:at,:id)"; params["at"] = Timestamp.from(p.at); params["id"] = p.id }
        val rows = jdbc.query("""SELECT s.*,a.attempt_number FROM document_processing_steps s
            LEFT JOIN document_processing_attempts a ON a.id=s.processing_attempt_id WHERE ${where.joinToString(" AND ")}
            ORDER BY s.started_at DESC,s.id DESC LIMIT :limit""", params) { rs, _ ->
            val start = rs.getTimestamp("started_at").toInstant()
            val finish = rs.getTimestamp("finished_at")?.toInstant()
            val code = rs.getString("error_code")
            DocumentStep(rs.getObject("id", UUID::class.java), rs.getObject("operation_run_id", UUID::class.java),
                rs.getObject("source_document_id", UUID::class.java), rs.getObject("processing_attempt_id", UUID::class.java),
                rs.getObject("attempt_number", Int::class.javaObjectType), DocumentStage.valueOf(rs.getString("stage")), rs.getInt("sequence"),
                StepStatus.valueOf(rs.getString("status")), start, finish, rs.getTimestamp("updated_at").toInstant(), OperationRunStore.duration(start, finish),
                rs.getObject("input_count", Int::class.javaObjectType), rs.getObject("output_count", Int::class.javaObjectType),
                rs.getObject("events_inserted", Int::class.javaObjectType), rs.getObject("events_reused", Int::class.javaObjectType), code, code?.let(OperationalErrors::message))
        }
        val items = rows.take(page.limit)
        val next = if (rows.size > page.limit) items.last().let { cursor.encode("steps", CursorPosition(it.startedAt, it.id), filters) } else null
        return OperationsPage(generatedAt, null, items, page.limit, next)
    }
}
