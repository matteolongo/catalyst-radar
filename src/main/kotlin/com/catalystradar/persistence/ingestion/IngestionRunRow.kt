package com.catalystradar.persistence.ingestion

import com.catalystradar.application.ingestion.IngestionStatus
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ingestion_runs")
data class IngestionRunRow(
    @Id val id: UUID,
    val provider: String,
    val status: String,
    val cursor: String?,
    @Column("documents_fetched") val fetched: Int,
    @Column("documents_new") val added: Int,
    @Column("documents_duplicate") val duplicates: Int,
    @Column("error_summary") val error: String?,
    @Column("started_at") val startedAt: Instant,
    @Column("finished_at") val finishedAt: Instant?,
)

data class IngestionRunRecord(
    val id: UUID,
    val provider: String,
    val status: IngestionStatus,
    val fetched: Int,
    val added: Int,
    val duplicates: Int,
    val error: String?,
    val finishedAt: Instant?,
)

fun IngestionRunRow.toRecord() = IngestionRunRecord(
    id = id,
    provider = provider,
    status = IngestionStatus.valueOf(status),
    fetched = fetched,
    added = added,
    duplicates = duplicates,
    error = error,
    finishedAt = finishedAt,
)
