package com.catalystradar.persistence.security

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository
import java.time.OffsetDateTime
import java.util.UUID

interface ApiClientRepository : ListCrudRepository<ApiClientRow, UUID> {
    fun findByKeyPrefix(keyPrefix: String): ApiClientRow?

    @Modifying
    @Query("UPDATE api_clients SET active = :active WHERE id = :id")
    fun setActive(id: UUID, active: Boolean)

    @Modifying
    @Query("UPDATE api_clients SET last_used_at = :at WHERE id = :id AND active AND (last_used_at IS NULL OR last_used_at < :at)")
    fun updateLastUsed(id: UUID, at: OffsetDateTime)
}
