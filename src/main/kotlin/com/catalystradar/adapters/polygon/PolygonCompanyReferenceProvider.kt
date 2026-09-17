package com.catalystradar.adapters.polygon

import com.catalystradar.adapters.http.mapHttpClientError
import com.catalystradar.ports.CompanyPage
import com.catalystradar.ports.CompanyReference
import com.catalystradar.ports.CompanyReferenceProvider
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

/**
 * Polygon tickers reference adapter. Polygon tickers carry no GICS
 * sector, so sector stays null here; the SIC description maps to
 * industry and the locale to the country. Exchange MICs map to display
 * names (XNYS/XNAS); unknown MICs pass through raw.
 */
@Component
class PolygonCompanyReferenceProvider(
    builder: RestClient.Builder,
    private val properties: PolygonProperties,
) : CompanyReferenceProvider {

    private val client = builder.baseUrl(properties.baseUrl).build()

    override suspend fun listUsEquities(cursor: String?): CompanyPage =
        withContext(Dispatchers.IO) {
            try {
                val page = getPage(cursor)
                CompanyPage(
                    values = page.results.orEmpty().mapNotNull { it.toReference() },
                    nextCursor = page.nextUrl,
                )
            } catch (e: HttpClientErrorException) {
                throw mapHttpClientError("polygon", e)
            } catch (e: HttpServerErrorException) {
                throw ProviderException.TemporaryUnavailable("polygon: ${e.statusCode}")
            } catch (e: RestClientException) {
                throw ProviderException.InvalidResponse("polygon: ${e.message}")
            }
        }

    private fun getPage(cursor: String?): PolygonTickersPage {
        if (cursor != null && cursor.startsWith("http")) {
            return get(cursor)
        }
        val response = client.get().uri { uri ->
            uri.path("/v3/reference/tickers")
            uri.queryParam("market", "stocks")
            uri.queryParam("active", true)
            uri.queryParam("limit", 1000)
            uri.queryParam("sort", "ticker")
            uri.queryParam("order", "asc")
            cursor?.let { uri.queryParam("cursor", it) }
            uri.queryParam("apiKey", properties.apiKey)
            uri.build()
        }.retrieve()
        return response.body<PolygonTickersPage>()
            ?: throw ProviderException.InvalidResponse("polygon: empty tickers page")
    }

    private fun get(url: String): PolygonTickersPage =
        client.get().uri(url).retrieve().body<PolygonTickersPage>()
            ?: throw ProviderException.InvalidResponse("polygon: empty tickers page")
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PolygonTickersPage(
    @JsonProperty("results") val results: List<PolygonTicker>?,
    @JsonProperty("next_url") val nextUrl: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PolygonTicker(
    @JsonProperty("ticker") val ticker: String?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("primary_exchange") val primaryExchange: String?,
    @JsonProperty("locale") val locale: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("active") val active: Boolean?,
    @JsonProperty("sic_description") val sicDescription: String?,
) {
    fun toReference(): CompanyReference? {
        val symbol = ticker?.takeIf { it.isNotBlank() } ?: return null
        val companyName = name?.takeIf { it.isNotBlank() } ?: return null
        return CompanyReference(
            ticker = symbol,
            name = companyName,
            exchange = primaryExchange?.let { MIC_EXCHANGES[it] ?: it },
            sector = null,
            industry = sicDescription,
            country = locale?.uppercase(),
            active = active ?: true,
        )
    }
}

private val MIC_EXCHANGES = mapOf(
    "XNYS" to "NYSE",
    "XNAS" to "Nasdaq",
    "ARCX" to "NYSE Arca",
    "BATS" to "Cboe",
)
