package com.catalystradar.persistence.event

import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.EventCluster
import com.catalystradar.domain.event.EventType
import com.pgvector.PGvector
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
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

    /**
     * Bounded filtered feed with keyset pagination. The cursor encodes
     * the last seen (discoveredAt, id); one extra row is read to know
     * whether a next page exists.
     */
    fun searchEvents(query: EventSearch): EventSearchPage {
        require(query.limit in 1..100) { "limit must be within 1..100" }
        val decoded = query.cursor?.let { decodeCursor(it) }
        val rows = repository.search(
            ticker = query.ticker,
            family = query.family?.name,
            type = query.type?.name,
            direction = query.direction?.name,
            from = query.from,
            to = query.to,
            cursorTs = decoded?.first,
            cursorId = decoded?.second,
            limit = query.limit + 1,
        )
        val page = rows.take(query.limit)
        val next = if (rows.size > query.limit) {
            val last = page.last()
            "${last.discoveredAt}|${last.id}"
        } else {
            null
        }
        return EventSearchPage(
            events = page.map { EventWithSource(it.toDomain(), it.sourceDocumentId) },
            nextCursor = next,
        )
    }

    private fun decodeCursor(cursor: String): Pair<Instant, UUID> {
        val parts = cursor.split("|")
        if (parts.size != 2) throw IllegalArgumentException("invalid cursor: $cursor")
        try {
            return Instant.parse(parts[0]) to UUID.fromString(parts[1])
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("invalid cursor: $cursor")
        }
    }

    fun findDetailedByCompanyId(companyId: UUID): List<EventWithSource> =
        repository.findByCompanyId(companyId).map { EventWithSource(it.toDomain(), it.sourceDocumentId) }

    fun assignCluster(eventId: UUID, clusterId: UUID) {
        val row = repository.findById(eventId).orElseThrow()
        repository.save(row.copy(clusterId = clusterId))
    }
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

    fun findRecent(
        companyId: UUID,
        type: EventType,
        since: Instant,
        limit: Int,
    ): List<EventCluster> =
        repository
            .findByCompanyIdAndEventTypeAndFirstSeenAtAfterOrderByFirstSeenAtDesc(
                companyId,
                type.name,
                since,
            )
            .take(limit)
            .map { it.toDomain() }

    fun findEmbedding(clusterId: UUID): List<Float>? =
        repository.findById(clusterId)
            .map { it.embedding?.toArray()?.toList() }
            .orElse(null)
}
