package com.catalystradar.persistence.operations

import com.catalystradar.application.operations.*
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class ProcessingAttemptStore(private val jdbc: NamedParameterJdbcTemplate) {
    private val cursor = OperationsCursor()

    /** Serialize source attempt numbering even when two callers recover the same source. */
    fun lockDocument(documentId: UUID) {
        check(jdbc.queryForList("SELECT source_document_id FROM document_processing WHERE source_document_id=:id FOR UPDATE", mapOf("id" to documentId)).size == 1) {
            "processing record not found"
        }
    }

    fun requireDocument(id: UUID, documentId: UUID) {
        check(jdbc.queryForObject("SELECT COUNT(*) FROM document_processing_attempts WHERE id=:id AND source_document_id=:document", mapOf("id" to id, "document" to documentId), Long::class.java) == 1L) {
            "attempt does not belong to source document"
        }
    }

    fun start(id: UUID, documentId: UUID, runId: UUID, number: Int, now: Instant) {
        require(number > 0)
        check(jdbc.update("""INSERT INTO document_processing_attempts
            (id,source_document_id,operation_run_id,attempt_number,status,started_at,updated_at)
            VALUES(:id,:document,:run,:number,'RUNNING',:now,:now)""",
            mapOf("id" to id, "document" to documentId, "run" to runId, "number" to number, "now" to Timestamp.from(now))) == 1)
    }

    fun finish(id: UUID, status: AttemptStatus, inserted: Int, reused: Int, code: String?, nextAttemptAt: Instant?, now: Instant) {
        require(status in setOf(AttemptStatus.COMPLETED, AttemptStatus.SKIPPED, AttemptStatus.RETRYABLE_ERROR, AttemptStatus.TERMINAL_ERROR))
        require(inserted >= 0 && reused >= 0)
        require((status == AttemptStatus.RETRYABLE_ERROR) == (nextAttemptAt != null))
        check(jdbc.update("""UPDATE document_processing_attempts SET status=:status,events_inserted=:inserted,events_reused=:reused,
            error_code=:code,error_message=:message,next_attempt_at=:next,finished_at=:now,updated_at=:now
            WHERE id=:id AND status='RUNNING'""", mapOf("id" to id, "status" to status.name, "inserted" to inserted, "reused" to reused,
            "code" to code, "message" to code?.let(OperationalErrors::message), "next" to nextAttemptAt?.let(Timestamp::from), "now" to Timestamp.from(now))) == 1) {
            "attempt is not running"
        }
    }

    fun interrupt(id: UUID, code: String, now: Instant) {
        check(jdbc.update("""UPDATE document_processing_attempts SET status='INTERRUPTED',error_code=:code,error_message=:message,
            finished_at=NULL,next_attempt_at=NULL,updated_at=:now WHERE id=:id AND status='RUNNING'""",
            mapOf("id" to id, "code" to code, "message" to OperationalErrors.message(code), "now" to Timestamp.from(now))) == 1) {
            "attempt is not running"
        }
    }

    fun interruptRunningForDocument(documentId: UUID, code: String, now: Instant) {
        jdbc.update("""UPDATE document_processing_attempts SET status='INTERRUPTED',error_code=:code,error_message=:message,
            finished_at=NULL,next_attempt_at=NULL,updated_at=:now WHERE source_document_id=:id AND status='RUNNING'""",
            mapOf("id" to documentId, "code" to code, "message" to OperationalErrors.message(code), "now" to Timestamp.from(now)))
    }

    fun search(documentId: UUID, page: PageRequest, generatedAt: Instant): OperationsPage<ProcessingAttempt> {
        val filters = mapOf("documentId" to documentId.toString())
        val params = mutableMapOf<String, Any?>("documentId" to documentId, "limit" to page.limit + 1)
        var positionSql = ""
        page.cursor?.let {
            val position = cursor.decode(it, "attempts", filters)
            positionSql = "AND (a.started_at,a.id) < (:cursorAt,:cursorId)"
            params["cursorAt"] = Timestamp.from(position.at); params["cursorId"] = position.id
        }
        val rows = jdbc.query(
            """SELECT a.id,a.source_document_id,a.operation_run_id,a.attempt_number,a.status,a.started_at,a.finished_at,a.updated_at,
                a.next_attempt_at,a.events_inserted,a.events_reused,a.error_code FROM document_processing_attempts a
                WHERE a.source_document_id=:documentId $positionSql ORDER BY a.started_at DESC,a.id DESC LIMIT :limit""", params,
        ) { rs, _ ->
            val start = rs.getTimestamp("started_at").toInstant()
            val finish = rs.getTimestamp("finished_at")?.toInstant()
            val status = AttemptStatus.valueOf(rs.getString("status"))
            val code = rs.getString("error_code") ?: if (status in setOf(AttemptStatus.RETRYABLE_ERROR, AttemptStatus.TERMINAL_ERROR, AttemptStatus.INTERRUPTED)) "UNKNOWN_FAILURE" else null
            ProcessingAttempt(rs.getObject("id", UUID::class.java), rs.getObject("source_document_id", UUID::class.java),
                rs.getObject("operation_run_id", UUID::class.java), rs.getInt("attempt_number"), status, start, finish,
                rs.getTimestamp("updated_at").toInstant(), OperationRunStore.duration(start, finish), rs.getTimestamp("next_attempt_at")?.toInstant(),
                rs.getInt("events_inserted"), rs.getInt("events_reused"), code, code?.let(OperationalErrors::message), emptyList(), 0, false)
        }
        val items = rows.take(page.limit)
        val calls = if (items.isEmpty()) emptyMap() else jdbc.query(
            """SELECT processing_attempt_id,id,total FROM (
                SELECT processing_attempt_id,id,created_at,
                    ROW_NUMBER() OVER (PARTITION BY processing_attempt_id ORDER BY created_at ASC,id ASC) AS position,
                    COUNT(*) OVER (PARTITION BY processing_attempt_id) AS total
                FROM model_runs WHERE processing_attempt_id IN (:attemptIds)
                ) ranked WHERE position <= 100 ORDER BY processing_attempt_id,created_at ASC,id ASC""", mapOf("attemptIds" to items.map { it.id }),
        ) { rs, _ -> rs.getObject("processing_attempt_id", UUID::class.java) to (rs.getObject("id", UUID::class.java) to rs.getLong("total")) }
            .groupBy({ it.first }, { it.second })
        val enriched = items.map {
            val linked = calls[it.id].orEmpty()
            val total = linked.firstOrNull()?.second ?: 0
            it.copy(modelCallIds = linked.map { call -> call.first }, modelCallsTotal = total, modelCallsTruncated = total > 100)
        }
        val next = if (rows.size > page.limit) items.last().let { cursor.encode("attempts", CursorPosition(it.startedAt, it.id), filters) } else null
        return OperationsPage(generatedAt, null, enriched, page.limit, next)
    }
}
