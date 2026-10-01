package com.catalystradar.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

data class GeneratedKey(val prefix: String, val secret: String, val rawKey: String)

data class ParsedKey(val prefix: String, val secret: String)

/**
 * API key format: cr_live_<8 alnum prefix>_<43-char secret>.
 * The prefix identifies the client row; only the secret's SHA-256
 * persists. Parsing is strict so malformed keys fail closed.
 */
object ApiKeyGenerator {
    const val SCHEME_PREFIX = "cr_live_"
    private val PREFIX_PATTERN = Regex("[A-Za-z0-9]{8}")
    private const val ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    fun generate(): GeneratedKey {
        val prefix = (1..8).map { ALPHANUM[random.nextInt(ALPHANUM.length)] }.joinToString("")
        val secretBytes = ByteArray(32)
        random.nextBytes(secretBytes)
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes)
        return GeneratedKey(prefix, secret, "${SCHEME_PREFIX}${prefix}_${secret}")
    }

    fun parse(rawKey: String): ParsedKey? {
        if (!rawKey.startsWith(SCHEME_PREFIX)) return null
        val rest = rawKey.removePrefix(SCHEME_PREFIX)
        if (rest.length < 10 || rest[8] != '_') return null
        val prefix = rest.substring(0, 8)
        if (!PREFIX_PATTERN.matches(prefix)) return null
        val secret = rest.substring(9)
        if (secret.isBlank()) return null
        return ParsedKey(prefix, secret)
    }

    fun sha256Hex(secret: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return HexFormat.of().formatHex(digest.digest(secret.toByteArray()))
    }

    fun secretsEqual(expectedHashHex: String, secret: String): Boolean =
        MessageDigest.isEqual(
            expectedHashHex.toByteArray(),
            sha256Hex(secret).toByteArray(),
        )
}
