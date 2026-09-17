package com.catalystradar.persistence.event

import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.EventCluster
import com.pgvector.PGvector
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class EventStore(
    private val repository: EventRepository,
    private val template: JdbcAggregateTemplate,
) {

    fun save(
        event: CatalystEvent,
        sourceDocumentId: UUID? = null,
    ): CatalystEvent =
        template.insert(event.toRow(sourceDocumentId)).toDomain()

    fun findById(id: UUID): CatalystEvent? =
        repository.findById(id).map { it.toDomain() }.orElse(null)

    fun findByCompanyId(companyId: UUID): List<CatalystEvent> =
        repository.findByCompanyId(companyId).map { it.toDomain() }

    fun findByClusterId(clusterId: UUID): List<CatalystEvent> =
        repository.findByClusterId(clusterId).map { it.toDomain() }
}

@Repository
class EventClusterStore(
    private val repository: EventClusterRepository,
    private val template: JdbcAggregateTemplate,
) {

    fun save(cluster: EventCluster, embedding: PGvector? = null): EventCluster =
        template.insert(cluster.toRow(embedding)).toDomain()

    fun findById(id: UUID): EventCluster? =
        repository.findById(id).map { it.toDomain() }.orElse(null)
}
