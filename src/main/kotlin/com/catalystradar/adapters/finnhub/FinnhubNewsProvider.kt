package com.catalystradar.adapters.finnhub

import com.catalystradar.adapters.http.mapHttpClientError
import com.catalystradar.ports.NewsFetchRequest
import com.catalystradar.ports.NewsFetchResult
import com.catalystradar.ports.NewsProvider
import com.catalystradar.ports.ProviderException
import com.catalystradar.ports.RawArticle
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Finnhub company-news adapter (secondary v0.1 source). The endpoint is
 * per-symbol with a required date window and no pagination, so each
 * requested ticker costs one call and cursors never continue.
 */
@Component
class FinnhubNewsProvider(
    builder: RestClient.Builder,
    private val properties: FinnhubProperties,
) : NewsProvider {

    private val client = builder.baseUrl(properties.baseUrl).build()

    override suspend fun fetch(request: NewsFetchRequest): NewsFetchResult =
        withContext(Dispatchers.IO) {
            try {
                val to = request.to?.atZone(ZoneOffset.UTC)?.toLocalDate() ?: LocalDate.now(ZoneOffset.UTC)
                val from = request.from?.atZone(ZoneOffset.UTC)?.toLocalDate() ?: to.minusDays(7)
                val articles = request.tickers.flatMap { ticker ->
                    getNews(ticker, from, to).mapNotNull { it.toArticle() }
                }
                NewsFetchResult(articles = articles, nextCursor = null)
            } catch (e: HttpClientErrorException) {
                throw mapHttpClientError("finnhub", e)
            } catch (e: HttpServerErrorException) {
                throw ProviderException.TemporaryUnavailable("finnhub: ${e.statusCode}")
            } catch (e: RestClientException) {
                throw ProviderException.InvalidResponse("finnhub: ${e.message}")
            }
        }

    private fun getNews(ticker: String, from: LocalDate, to: LocalDate): List<FinnhubNewsItem> {
        val response = client.get().uri { uri ->
            uri.path("/api/v1/company-news")
            uri.queryParam("symbol", ticker)
            uri.queryParam("from", from.toString())
            uri.queryParam("to", to.toString())
            uri.queryParam("token", properties.apiKey)
            uri.build()
        }.retrieve()
        return response.body<List<FinnhubNewsItem>>() ?: throw ProviderException.InvalidResponse(
            "finnhub: empty news array",
        )
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class FinnhubNewsItem(
    @JsonProperty("id") val id: Long?,
    @JsonProperty("headline") val headline: String?,
    @JsonProperty("summary") val summary: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("datetime") val datetime: Long?,
    @JsonProperty("related") val related: String?,
) {
    fun toArticle(): RawArticle? {
        val title = headline?.takeIf { it.isNotBlank() } ?: return null
        return RawArticle(
            provider = "finnhub",
            providerArticleId = id?.toString(),
            url = url,
            title = title,
            body = summary.orEmpty(),
            publishedAt = datetime?.let { Instant.ofEpochSecond(it) },
            tickers = related?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        )
    }
}
