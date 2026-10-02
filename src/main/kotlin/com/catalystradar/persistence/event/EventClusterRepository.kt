package com.catalystradar.persistence.event

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository
import java.time.Instant
import java.util.UUID

interface EventClusterRepository : ListCrudRepository<EventClusterRow, UUID> {
    @Query(
        """
        SELECT * FROM event_clusters
        WHERE company_id = :companyId
          AND event_type = :eventType
          AND first_seen_at >= :from
          AND first_seen_at <= :through
        ORDER BY first_seen_at DESC, id DESC
        LIMIT :limit
        """,
    )
    fun findWithinWindow(
        companyId: UUID,
        eventType: String,
        from: Instant,
        through: Instant,
        limit: Int,
    ): List<EventClusterRow>
}
