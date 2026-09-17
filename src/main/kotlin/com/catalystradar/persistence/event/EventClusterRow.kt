package com.catalystradar.persistence.event

import com.catalystradar.domain.event.EventCluster
import com.catalystradar.domain.event.EventType
import com.pgvector.PGvector
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * event_clusters row: the canonical identity of one real-world fact.
 * The embedding supports semantic matching in CR-11; it is
 * persistence-level data, not part of the domain model.
 */
@Table("event_clusters")
data class EventClusterRow(
    @Id val id: UUID,
    @Column("company_id") val companyId: UUID,
    @Column("event_type") val eventType: String,
    @Column("first_seen_at") val firstSeenAt: Instant,
    val embedding: PGvector?,
)

fun EventClusterRow.toDomain() = EventCluster(
    id = id,
    companyId = companyId,
    eventType = EventType.valueOf(eventType),
    firstSeenAt = firstSeenAt,
)

fun EventCluster.toRow(embedding: PGvector? = null) = EventClusterRow(
    id = id,
    companyId = companyId,
    eventType = eventType.name,
    firstSeenAt = firstSeenAt,
    embedding = embedding,
)
