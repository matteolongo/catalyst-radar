package com.catalystradar.adapters.polygon

import com.catalystradar.adapters.http.mapHttpClientError
import com.catalystradar.ports.NewsFetchRequest
import com.catalystradar.ports.NewsFetchResult
import com.catalystradar.ports.NewsProvider
import com.catalystradar.ports.ProviderException
import com.catalystradar.ports.RawArticle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * Polygon news adapter (primary v0.1 source). Maps provider pages to
 * neutral articles; provider pagination cursors pass through opaquely.
 * Blocking HTTP stays on Dispatchers.IO out of coroutine workers.
 */
@Component
class PolygonNewsProvider(
    builder: RestClient.Builder,
    private val properties: PolygonProperties,
) : NewsProvider {

    private val client = builder.baseUrl(properties.baseUrl).build()

    override val name: String = "polygon"

    override suspend fun fetch(request: NewsFetchRequest): NewsFetchResult =
        withContext(Dispatchers.IO) {
            try {
                val page = getPage(request)
                NewsFetchResult(
                    articles = page.results.orEmpty().mapNotNull { it.toArticle() },
                    nextCursor = page.nextUrl,
                )
            } catch (e: HttpClientErrorException) {
                throw mapHttpClientError("polygon", e)
            } catch (e: HttpServerErrorException) {
                throw ProviderException.TemporaryUnavailable("polygon: ${e.statusCode}", provider = "polygon")
            } catch (_: ResourceAccessException) {
                throw ProviderException.TemporaryUnavailable("polygon: connection failed or timed out", provider = "polygon")
            } catch (_: RestClientException) {
                throw ProviderException.InvalidResponse("polygon: invalid news response", provider = "polygon")
            }
        }

    private fun getPage(request: NewsFetchRequest): PolygonNewsPage {
        // Provider continuation URLs are only a source of cursor tokens.
        val cursor = request.cursor?.let(::cursorToken)
        val response = client.get().uri { uri ->
            uri.path("/v2/reference/news")
            if (cursor != null) return@uri uri.queryParam("cursor", "{cursor}").build(cursor)
            if (request.tickers.isNotEmpty()) {
                uri.queryParam("ticker", request.tickers.joinToString(","))
            }
            request.from?.let { uri.queryParam("published_utc.gte", it.toString()) }
            request.to?.let { uri.queryParam("published_utc.lte", it.toString()) }
            uri.queryParam("limit", request.pageSize)
            uri.queryParam("order", "descending")
            uri.queryParam("sort", "published_utc")
            uri.build()
        }.headers { it.setBearerAuth(properties.apiKey) }.retrieve()
        return response.body<PolygonNewsPage>()
            ?: throw ProviderException.InvalidResponse("polygon: empty news page", provider = "polygon")
    }

    private fun cursorToken(cursor: String): String {
        if (!cursor.startsWith("http")) return cursor
        return try {
            val token = UriComponentsBuilder.fromUriString(cursor).build().queryParams.getFirst("cursor")
            UriUtils.decode(requireNotNull(token), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            throw ProviderException.InvalidResponse("polygon: invalid news cursor", provider = "polygon")
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PolygonNewsPage(
    @JsonProperty("results") val results: List<PolygonNewsArticle>?,
    @JsonProperty("next_url") val nextUrl: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PolygonNewsArticle(
    @JsonProperty("id") val id: String?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("article_url") val articleUrl: String?,
    @JsonProperty("published_utc") val publishedUtc: String?,
    @JsonProperty("tickers") val tickers: List<String>?,
) {
    /** Titleless items carry no usable evidence and are dropped. */
    fun toArticle(): RawArticle? {
        val headline = title?.takeIf { it.isNotBlank() } ?: return null
        return RawArticle(
            provider = "polygon",
            providerArticleId = id,
            url = articleUrl,
            title = headline,
            body = description.orEmpty(),
            publishedAt = publishedUtc?.let { runCatching { Instant.parse(it) }.getOrNull() },
            tickers = tickers.orEmpty(),
        )
    }
}
