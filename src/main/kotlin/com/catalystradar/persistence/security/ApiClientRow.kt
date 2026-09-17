package com.catalystradar.persistence.security

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("api_clients")
data class ApiClientRow(
    @Id val id: UUID,
    val name: String,
    @Column("key_prefix") val keyPrefix: String,
    @Column("key_hash") val keyHash: String,
    val active: Boolean,
    @Column("last_used_at") val lastUsedAt: Instant?,
)

data class ApiClientRecord(
    val id: UUID,
    val name: String,
    val keyPrefix: String,
    val keyHash: String,
    val active: Boolean,
    val lastUsedAt: Instant?,
)

fun ApiClientRow.toRecord() = ApiClientRecord(
    id = id,
    name = name,
    keyPrefix = keyPrefix,
    keyHash = keyHash,
    active = active,
    lastUsedAt = lastUsedAt,
)
