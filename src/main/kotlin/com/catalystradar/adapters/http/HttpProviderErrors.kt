package com.catalystradar.adapters.http

import com.catalystradar.ports.ProviderException
import org.springframework.web.client.HttpClientErrorException

/**
 * Shared vendor HTTP failure translation. Providers map their statuses
 * through here so retry behavior stays uniform; only rate limits and
 * 5xx merit bounded retries.
 */
fun mapHttpClientError(provider: String, e: HttpClientErrorException): ProviderException =
    when (e.statusCode.value()) {
        401, 403 -> ProviderException.AuthenticationFailed(
            "$provider rejected credentials (${e.statusCode})",
        )
        429 -> ProviderException.RateLimited(
            e.responseHeaders?.getFirst("Retry-After")?.toLongOrNull(),
        )
        else -> ProviderException.PermanentFailure(
            "$provider client error (${e.statusCode})",
        )
    }
