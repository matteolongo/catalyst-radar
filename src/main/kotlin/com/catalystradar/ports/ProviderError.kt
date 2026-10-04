package com.catalystradar.ports

/**
 * Application-level provider failures. Adapters translate HTTP/SDK errors
 * into this hierarchy so application code never sees vendor exceptions.
 * Only rate limits and temporary outages merit bounded retries.
 */
sealed class ProviderException(message: String?, cause: Throwable? = null, val provider: String? = null) :
    RuntimeException(message, cause) {

    class RateLimited(val retryAfterSeconds: Long?, provider: String? = null) : ProviderException(
        "provider rate limit exceeded",
        provider = provider,
    )

    class AuthenticationFailed(detail: String, provider: String? = null) : ProviderException(
        "provider authentication failed",
        provider = provider,
    )

    class TemporaryUnavailable(detail: String, cause: Throwable? = null, provider: String? = null) : ProviderException(
        "provider temporarily unavailable: $detail",
        cause,
        provider,
    )

    class InvalidResponse(detail: String, provider: String? = null) : ProviderException(
        "provider returned an unusable response: $detail",
        provider = provider,
    )

    class PermanentFailure(detail: String, provider: String? = null) : ProviderException(
        "provider request failed permanently: $detail",
        provider = provider,
    )

    /** Seconds to wait before retrying, or null when retry is pointless. */
    fun retryableAfterSeconds(): Long? = when (this) {
        is RateLimited -> retryAfterSeconds
        is TemporaryUnavailable -> 60L
        else -> null
    }
}
