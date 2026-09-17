package com.catalystradar.persistence.ingestion

import com.catalystradar.application.ingestion.IngestionStatus
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class IngestionRunStore(
    private val repository: IngestionRunRepository,
    private val template: JdbcAggregateTemplate,
) {

    fun startRun(provider: String): UUID {
        val id = UUID.randomUUID()
        template.insert(
            IngestionRunRow(
                id = id,
                provider = provider,
                status = IngestionStatus.RUNNING.name,
                cursor = null,
                fetched = 0,
                added = 0,
                duplicates = 0,
                error = null,
                startedAt = Instant.now(),
                finishedAt = null,
            ),
        )
        return id
    }

    fun finishRun(
        id: UUID,
        status: IngestionStatus,
        fetched: Int,
        added: Int,
        duplicates: Int,
        error: String?,
    ) {
        val current = repository.findById(id).orElseThrow()
        repository.save(
            current.copy(
                status = status.name,
                fetched = fetched,
                added = added,
                duplicates = duplicates,
                error = error,
                finishedAt = Instant.now(),
            ),
        )
    }

    fun findById(id: UUID): IngestionRunRecord? =
        repository.findById(id).map { it.toRecord() }.orElse(null)
}
