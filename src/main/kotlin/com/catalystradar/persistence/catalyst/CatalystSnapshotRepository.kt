package com.catalystradar.persistence.catalyst

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository
import java.time.Instant
import java.util.UUID

interface CatalystSnapshotRepository : ListCrudRepository<CatalystSnapshotRow, UUID> {
    fun findFirstByCompanyIdOrderByAsOfDesc(companyId: UUID): CatalystSnapshotRow?

    fun findByCompanyIdOrderByAsOfDesc(companyId: UUID): List<CatalystSnapshotRow>

    @Query(
        """
        SELECT * FROM catalyst_snapshots
        WHERE company_id = :companyId
          AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR as_of >= :from)
          AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR as_of <= :to)
        ORDER BY as_of DESC, created_at DESC, id DESC
        LIMIT :limit
        """,
    )
    fun findWindow(companyId: UUID, from: Instant?, to: Instant?, limit: Int): List<CatalystSnapshotRow>
}
