package com.catalystradar.persistence.security

import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class ApiClientStore(
    private val repository: ApiClientRepository,
    private val template: JdbcAggregateTemplate,
) {

    fun save(name: String, keyPrefix: String, keyHash: String): ApiClientRecord =
        template.insert(
            ApiClientRow(
                id = UUID.randomUUID(),
                name = name,
                keyPrefix = keyPrefix,
                keyHash = keyHash,
                active = true,
                lastUsedAt = null,
            ),
        ).toRecord()

    fun findByPrefix(keyPrefix: String): ApiClientRecord? =
        repository.findByKeyPrefix(keyPrefix)?.toRecord()

    fun setActive(id: UUID, active: Boolean) {
        val row = repository.findById(id).orElseThrow()
        repository.save(row.copy(active = active))
    }

    fun updateLastUsed(id: UUID, at: Instant) {
        val row = repository.findById(id).orElseThrow()
        repository.save(row.copy(lastUsedAt = at))
    }
}
