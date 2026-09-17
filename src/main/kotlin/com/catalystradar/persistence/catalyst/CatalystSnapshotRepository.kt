package com.catalystradar.persistence.catalyst

import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface CatalystSnapshotRepository : ListCrudRepository<CatalystSnapshotRow, UUID> {
    fun findFirstByCompanyIdOrderByAsOfDesc(companyId: UUID): CatalystSnapshotRow?

    fun findByCompanyIdOrderByAsOfDesc(companyId: UUID): List<CatalystSnapshotRow>
}
