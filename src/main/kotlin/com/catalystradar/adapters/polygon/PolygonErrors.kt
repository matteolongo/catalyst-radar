package com.catalystradar.adapters.polygon

import com.catalystradar.ports.ProviderException
import org.springframework.web.client.HttpClientErrorException

/**
 * Shared Polygon HTTP failure translation. Vendor statuses become
 * application errors; only rate limits and 5xx merit bounded retries.
 */
internal fun mapPolygonClientError(e: HttpClientErrorException): ProviderException =
    when (e.statusCode.value()) {
        401, 403 -> ProviderException.AuthenticationFailed(
            "polygon rejected credentials (${e.statusCode})",
        )
        429 -> ProviderException.RateLimited(
            e.responseHeaders?.getFirst("Retry-After")?.toLongOrNull(),
        )
        else -> ProviderException.PermanentFailure(
            "polygon client error (${e.statusCode})",
        )
    }
