package com.catalystradar.operations

import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class OperationsFixtures(private val jdbc: JdbcClient) {
    fun company(ticker: String): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("INSERT INTO companies(id,ticker,name) VALUES(:id,:ticker,:name)")
            .param("id", id).param("ticker", ticker).param("name", "$ticker Company").update()
        return id
    }

    fun document(at: Instant, title: String = "Source", body: String = "Evidence", provider: String = "polygon"): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("""
            INSERT INTO source_documents(id,provider,provider_document_id,title,body,discovered_at,created_at)
            VALUES(:id,:provider,:external,:title,:body,:at::timestamptz,:at::timestamptz)
        """).param("id", id).param("provider", provider).param("external", id.toString())
            .param("title", title).param("body", body).param("at", at.toString()).update()
        return id
    }

    fun link(documentId: UUID, companyId: UUID) {
        jdbc.sql("INSERT INTO source_document_companies(source_document_id,company_id) VALUES(:document,:company)")
            .param("document", documentId).param("company", companyId).update()
    }

    fun processing(id: UUID, status: String, attempts: Int = 0, next: Instant? = null, updatedAt: Instant) {
        jdbc.sql("""
            INSERT INTO document_processing(source_document_id,status,attempt_count,next_attempt_at,created_at,updated_at)
            VALUES(:id,:status,:attempts,:next::timestamptz,:at::timestamptz,:at::timestamptz)
        """).param("id", id).param("status", status).param("attempts", attempts)
            .param("next", next?.toString()).param("at", updatedAt.toString()).update()
    }

    fun run(at: Instant, status: String = "SUCCESS", kind: String = "PIPELINE"): UUID {
        val id = UUID.randomUUID()
        val finish = if (status == "RUNNING" || status == "INTERRUPTED") null else at.plusSeconds(1).toString()
        jdbc.sql("""
            INSERT INTO operation_runs(id,kind,trigger_type,status,phase,as_of,started_at,finished_at,updated_at,capture_complete)
            VALUES(:id,:kind,'MANUAL',:status,:phase,:at::timestamptz,:at::timestamptz,:finish::timestamptz,:at::timestamptz,:complete)
        """).param("id", id).param("kind", kind).param("status", status)
            .param("phase", if (status == "RUNNING") "PROCESSING" else "FINISHED")
            .param("at", at.toString()).param("finish", finish).param("complete", finish != null).update()
        return id
    }

    fun attempt(document: UUID, run: UUID, number: Int, at: Instant, status: String = "COMPLETED"): UUID {
        val id = UUID.randomUUID()
        val finish = if (status == "RUNNING" || status == "INTERRUPTED") null else at.plusSeconds(1).toString()
        jdbc.sql("""
            INSERT INTO document_processing_attempts(id,source_document_id,operation_run_id,attempt_number,status,started_at,finished_at,updated_at)
            VALUES(:id,:document,:run,:number,:status,:at::timestamptz,:finish::timestamptz,:at::timestamptz)
        """).param("id", id).param("document", document).param("run", run).param("number", number)
            .param("status", status).param("at", at.toString()).param("finish", finish).update()
        return id
    }

    fun model(at: Instant, cost: BigDecimal?, input: Int?, output: Int?, success: Boolean = true,
              operation: String = "extract", document: UUID? = null, attempt: UUID? = null, latency: Long? = 100): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("""
            INSERT INTO model_runs(id,provider,operation,model,source_document_id,processing_attempt_id,
                input_tokens,output_tokens,latency_ms,estimated_cost,success,error_code,created_at)
            VALUES(:id,'openai',:operation,'gpt-4o-mini',:document,:attempt,:input,:output,:latency,:cost,:success,:code,:at::timestamptz)
        """).param("id", id).param("operation", operation).param("document", document).param("attempt", attempt)
            .param("input", input).param("output", output).param("latency", latency).param("cost", cost)
            .param("success", success).param("code", if (success) null else "RATE_LIMITED")
            .param("at", at.toString()).update()
        return id
    }

    fun attemptStatus(id: UUID): String = jdbc.sql("SELECT status FROM document_processing_attempts WHERE id=:id")
        .param("id", id).query(String::class.java).single()

    fun processingStatus(id: UUID): String = jdbc.sql("SELECT status FROM document_processing WHERE source_document_id=:id")
        .param("id", id).query(String::class.java).single()

    fun firstIngestionFor(id: UUID): UUID = jdbc.sql("SELECT id FROM ingestion_runs WHERE operation_run_id=:id ORDER BY started_at,id LIMIT 1")
        .param("id", id).query(UUID::class.java).single()
}
