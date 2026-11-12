package com.catalystradar.adapters.http

import io.micrometer.common.KeyValue
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.client.observation.ClientHttpObservationDocumentation.HighCardinalityKeyNames
import org.springframework.http.client.observation.ClientHttpObservationDocumentation.LowCardinalityKeyNames
import org.springframework.http.client.observation.ClientRequestObservationContext
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention
import java.time.Duration

/** Explicit connect/read limits: a provider that never answers must not hold a pipeline cycle. */
fun timeouts(connect: Duration, read: Duration): ClientHttpRequestFactory =
    SimpleClientHttpRequestFactory().apply {
        setConnectTimeout(connect)
        setReadTimeout(read)
    }

/**
 * Provider request metrics without secrets or unbounded tags. Polygon and
 * Finnhub authenticate with a query parameter, so URLs are always reduced
 * to scheme, host and path. The `uri` tag is the URI template path when the
 * adapter used one, otherwise the literal path; adapters whose paths embed
 * variables (tickers, dates) must use templates.
 */
class ProviderHttpObservationConvention : DefaultClientRequestObservationConvention() {

    override fun uri(context: ClientRequestObservationContext): KeyValue {
        val path = context.uriTemplate?.let(::templatePath) ?: context.carrier?.uri?.path
        return KeyValue.of(LowCardinalityKeyNames.URI, path?.takeIf { it.isNotEmpty() } ?: "none")
    }

    override fun requestUri(context: ClientRequestObservationContext): KeyValue {
        val uri = context.carrier?.uri ?: return KeyValue.of(HighCardinalityKeyNames.HTTP_URL, "none")
        return KeyValue.of(HighCardinalityKeyNames.HTTP_URL, "${uri.scheme}://${uri.authority}${uri.path}")
    }

    private fun templatePath(template: String): String =
        template.substringBefore('?').replace(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*"), "")
}
