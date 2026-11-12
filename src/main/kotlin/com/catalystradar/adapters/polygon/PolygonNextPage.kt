package com.catalystradar.adapters.polygon

import com.catalystradar.ports.ProviderException
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * Polygon's `next_url` omits the API key. It is followed only on the
 * configured Polygon host, so a response can never redirect the key
 * elsewhere, and returned as a URI so it is never recorded as a template.
 */
internal fun polygonNextPage(nextUrl: String, properties: PolygonProperties): URI {
    val next = runCatching { URI.create(nextUrl) }.getOrNull()
    val base = URI.create(properties.baseUrl)
    if (next == null || !next.scheme.equals(base.scheme, ignoreCase = true) || !next.authority.equals(base.authority, ignoreCase = true)) {
        throw ProviderException.InvalidResponse("polygon: next page outside the configured host", provider = "polygon")
    }
    return UriComponentsBuilder.fromUri(next)
        .replaceQueryParam("apiKey", UriUtils.encodeQueryParam(properties.apiKey, StandardCharsets.UTF_8))
        .build(true)
        .toUri()
}
