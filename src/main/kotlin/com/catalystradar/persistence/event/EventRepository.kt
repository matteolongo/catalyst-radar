package com.catalystradar.persistence.event

import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface EventRepository : ListCrudRepository<EventRow, UUID> {
    fun findByCompanyId(companyId: UUID): List<EventRow>

    fun findByClusterId(clusterId: UUID): List<EventRow>
}
