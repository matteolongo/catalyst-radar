package com.catalystradar.persistence.security

import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface ApiClientRepository : ListCrudRepository<ApiClientRow, UUID> {
    fun findByKeyPrefix(keyPrefix: String): ApiClientRow?
}
