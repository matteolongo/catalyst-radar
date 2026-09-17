package com.catalystradar.ports

/**
 * Application-level provider failures. Adapters translate HTTP/SDK errors
 * into this hierarchy so application code never sees vendor exceptions.
 * Only rate limits and temporary outages merit bounded retries.
 */
sealed class ProviderException(message: String?, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    class RateLimited(val retryAfterSeconds: Long?) : ProviderException(
        "provider rate limit exceeded",
    )

    class AuthenticationFailed(detail: String) : ProviderException(
        "provider authentication failed",
    )

    class TemporaryUnavailable(detail: String, cause: Throwable? = null) : ProviderException(
        "provider temporarily unavailable: $detail",
        cause,
    )

    class InvalidResponse(detail: String) : ProviderException(
        "provider returned an unusable response: $detail",
    )

    class PermanentFailure(detail: String) : ProviderException(
        "provider request failed permanently: $detail",
    )

    /** Seconds to wait before retrying, or null when retry is pointless. */
    fun retryableAfterSeconds(): Long? = when (this) {
        is RateLimited -> retryAfterSeconds
        is TemporaryUnavailable -> 60L
        else -> null
    }
}
