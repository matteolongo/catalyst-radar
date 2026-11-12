package com.catalystradar.adapters.http

import com.catalystradar.ports.ProviderException
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import java.io.IOException

/**
 * Shared vendor HTTP failure translation. Providers map their statuses
 * through here so retry behavior stays uniform; only rate limits, 5xx and
 * transport failures merit bounded retries.
 */
fun mapHttpClientError(provider: String, e: HttpClientErrorException): ProviderException =
    when (e.statusCode.value()) {
        401, 403 -> ProviderException.AuthenticationFailed(
            "$provider rejected credentials (${e.statusCode})",
            provider,
        )
        429 -> ProviderException.RateLimited(
            e.responseHeaders?.getFirst("Retry-After")?.toLongOrNull(),
            provider,
        )
        else -> ProviderException.PermanentFailure(
            "$provider client error (${e.statusCode})",
            provider,
        )
    }

/**
 * Failures with no usable HTTP status. A timeout, refused connection or
 * other I/O error says nothing about the response, so it is transient, not
 * invalid output. That includes I/O failures while the body is read, which
 * RestClient wraps in a plain RestClientException. Parse failures are not
 * I/O and stay invalid. The exception message embeds the request URL, which
 * can carry an API key as a query parameter, so neither message nor cause
 * is kept.
 */
fun mapTransportError(provider: String, e: RestClientException): ProviderException {
    val io = generateSequence<Throwable>(e) { current -> current.cause?.takeIf { it !== current } }
        .firstOrNull { it is IOException }
    return if (e is ResourceAccessException || io != null) {
        ProviderException.TemporaryUnavailable(
            "$provider: I/O failure (${(io ?: e).javaClass.simpleName})",
            provider = provider,
        )
    } else {
        ProviderException.InvalidResponse("$provider: unusable response (${e.javaClass.simpleName})", provider)
    }
}
