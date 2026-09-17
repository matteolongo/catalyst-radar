package com.catalystradar.persistence.catalyst

import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface StateTransitionRepository : ListCrudRepository<StateTransitionRow, UUID> {
    fun findByCompanyIdOrderByTransitionedAt(companyId: UUID): List<StateTransitionRow>
}
