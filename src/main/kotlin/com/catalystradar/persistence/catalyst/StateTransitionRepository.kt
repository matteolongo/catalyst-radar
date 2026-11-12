package com.catalystradar.persistence.catalyst

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository
import java.time.Instant
import java.util.UUID

interface StateTransitionRepository : ListCrudRepository<StateTransitionRow, UUID> {
    fun findByCompanyIdOrderByTransitionedAt(companyId: UUID): List<StateTransitionRow>

    @Query(
        """
        SELECT * FROM state_transitions
        WHERE company_id = :companyId
          AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR transitioned_at >= :from)
          AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR transitioned_at <= :to)
        ORDER BY transitioned_at DESC, id DESC
        LIMIT :limit
        """,
    )
    fun findWindow(companyId: UUID, from: Instant?, to: Instant?, limit: Int): List<StateTransitionRow>
}
