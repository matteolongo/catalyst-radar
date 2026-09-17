package com.catalystradar.persistence.event

import org.springframework.data.repository.ListCrudRepository
import java.time.Instant
import java.util.UUID

interface EventClusterRepository : ListCrudRepository<EventClusterRow, UUID> {
    fun findByCompanyIdAndEventTypeAndFirstSeenAtAfterOrderByFirstSeenAtDesc(
        companyId: UUID,
        eventType: String,
        since: Instant,
    ): List<EventClusterRow>
}
