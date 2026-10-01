package com.catalystradar.security

import com.catalystradar.persistence.security.ApiClientStore
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class CreatedApiKey(
    val clientId: UUID,
    val name: String,
    val prefix: String,
    /** Shown exactly once at creation; never persisted. */
    val rawKey: String,
)

sealed interface ApiKeyAuthResult {
    data class Authenticated(val clientId: UUID, val name: String) : ApiKeyAuthResult
    data object MalformedKey : ApiKeyAuthResult
    data object UnknownKey : ApiKeyAuthResult
    data object DisabledKey : ApiKeyAuthResult
    data object InvalidSecret : ApiKeyAuthResult
}

/**
 * API-key lifecycle: mint (raw key leaves here once) and authenticate.
 * Secrets are SHA-256 hashed with constant-time comparison; last-used
 * updates are best-effort and never fail authentication.
 */
@Service
class ApiKeyService(private val clients: ApiClientStore) {

    fun create(name: String): CreatedApiKey {
        val generated = ApiKeyGenerator.generate()
        val record = clients.save(
            name = name,
            keyPrefix = generated.prefix,
            keyHash = ApiKeyGenerator.sha256Hex(generated.secret),
        )
        return CreatedApiKey(
            clientId = record.id,
            name = name,
            prefix = generated.prefix,
            rawKey = generated.rawKey,
        )
    }

    fun authenticate(rawKey: String): ApiKeyAuthResult {
        val parsed = ApiKeyGenerator.parse(rawKey) ?: return ApiKeyAuthResult.MalformedKey
        val client = clients.findByPrefix(parsed.prefix) ?: return ApiKeyAuthResult.UnknownKey
        if (!client.active) return ApiKeyAuthResult.DisabledKey
        if (!ApiKeyGenerator.secretsEqual(client.keyHash, parsed.secret)) {
            return ApiKeyAuthResult.InvalidSecret
        }
        runCatching { clients.updateLastUsed(client.id, Instant.now()) }
        return ApiKeyAuthResult.Authenticated(client.id, client.name)
    }
}
