package com.catalystradar.persistence.event

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository
import java.time.Instant
import java.util.UUID

interface EventRepository : ListCrudRepository<EventRow, UUID> {
    fun findByCompanyId(companyId: UUID): List<EventRow>

    fun findByClusterId(clusterId: UUID): List<EventRow>

    @Query(
        """
        SELECT * FROM events
        WHERE (CAST(:ticker AS VARCHAR) IS NULL OR company_id = (SELECT id FROM companies WHERE ticker = :ticker))
          AND (CAST(:family AS VARCHAR) IS NULL OR family = :family)
          AND (CAST(:type AS VARCHAR) IS NULL OR event_type = :type)
          AND (CAST(:direction AS VARCHAR) IS NULL OR direction = :direction)
          AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR discovered_at >= :from)
          AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR discovered_at <= :to)
          AND (CAST(:cursorTs AS TIMESTAMPTZ) IS NULL
            OR (discovered_at, id) < (CAST(:cursorTs AS TIMESTAMPTZ), CAST(:cursorId AS UUID)))
        ORDER BY discovered_at DESC, id DESC
        LIMIT :limit
        """,
    )
    fun search(
        ticker: String?,
        family: String?,
        type: String?,
        direction: String?,
        from: Instant?,
        to: Instant?,
        cursorTs: Instant?,
        cursorId: UUID?,
        limit: Int,
    ): List<EventRow>
}
