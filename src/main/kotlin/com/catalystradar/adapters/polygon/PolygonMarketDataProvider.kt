package com.catalystradar.adapters.polygon

import com.catalystradar.adapters.http.mapHttpClientError
import com.catalystradar.adapters.http.mapTransportError
import com.catalystradar.adapters.http.timeouts
import com.catalystradar.ports.DailyBar
import com.catalystradar.ports.MarketDataProvider
import com.catalystradar.ports.ProviderException
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
 * Polygon aggregates adapter for evaluation-time market data.
 * Prices exist only for benchmark forward returns; they never
 * feed catalyst score-v1.
 */
@Component
class PolygonMarketDataProvider(
    builder: RestClient.Builder,
    private val properties: PolygonProperties,
) : MarketDataProvider {

    private val client = builder.baseUrl(properties.baseUrl)
        .requestFactory(timeouts(properties.connectTimeout, properties.readTimeout))
        .build()

    override suspend fun dailyBars(ticker: String, from: LocalDate, to: LocalDate): List<DailyBar> =
        withContext(Dispatchers.IO) {
            try {
                // A template keeps ticker and dates out of the request metric's uri tag.
                val response = client.get().uri(
                    "/v2/aggs/ticker/{ticker}/range/1/day/{from}/{to}?adjusted=true&sort=asc&limit=5000&apiKey={apiKey}",
                    ticker, from, to, properties.apiKey,
                ).retrieve().body<PolygonAggregates>()
                    ?: throw ProviderException.InvalidResponse("polygon: empty aggregates", provider = "polygon")
                response.results.orEmpty().mapNotNull { it.toBar(ticker) }
            } catch (e: HttpClientErrorException) {
                throw mapHttpClientError("polygon", e)
            } catch (e: HttpServerErrorException) {
                throw ProviderException.TemporaryUnavailable("polygon: ${e.statusCode}", provider = "polygon")
            } catch (e: RestClientException) {
                throw mapTransportError("polygon", e)
            }
        }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PolygonAggregates(
    @JsonProperty("results") val results: List<PolygonBar>?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PolygonBar(
    @JsonProperty("o") val open: Double?,
    @JsonProperty("h") val high: Double?,
    @JsonProperty("l") val low: Double?,
    @JsonProperty("c") val close: Double?,
    @JsonProperty("v") val volume: Double?,
    @JsonProperty("t") val timestampMillis: Long?,
) {
    fun toBar(ticker: String): DailyBar? {
        val open = open ?: return null
        val high = high ?: return null
        val low = low ?: return null
        val close = close ?: return null
        val at = timestampMillis ?: return null
        return DailyBar(
            ticker = ticker,
            date = Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate(),
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume?.toLong(),
        )
    }
}
