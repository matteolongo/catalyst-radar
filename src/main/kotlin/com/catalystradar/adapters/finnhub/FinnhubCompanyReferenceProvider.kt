package com.catalystradar.adapters.finnhub

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
 * Finnhub symbol-list reference adapter. The endpoint returns the whole
 * US board in one array, so paging happens client-side on an opaque
 * numeric offset cursor. Only common stock survives: ETFs, warrants and
 * funds never enter the universe through this path.
 */
@Component
class FinnhubCompanyReferenceProvider(
    builder: RestClient.Builder,
    private val properties: FinnhubProperties,
) : CompanyReferenceProvider {

    private val client = builder.baseUrl(properties.baseUrl).build()

    override suspend fun listUsEquities(cursor: String?): CompanyPage =
        withContext(Dispatchers.IO) {
            try {
                val symbols = getSymbols()
                    .filter { it.type == "Common Stock" }
                    .mapNotNull { it.toReference() }
                val offset = cursor?.toIntOrNull() ?: 0
                val page = symbols.drop(offset).take(PAGE_SIZE)
                val next = (offset + page.size).takeIf { offset + page.size < symbols.size }?.toString()
                CompanyPage(values = page, nextCursor = next)
            } catch (e: HttpClientErrorException) {
                throw mapHttpClientError("finnhub", e)
            } catch (e: HttpServerErrorException) {
                throw ProviderException.TemporaryUnavailable("finnhub: ${e.statusCode}")
            } catch (e: RestClientException) {
                throw ProviderException.InvalidResponse("finnhub: ${e.message}")
            }
        }

    private fun getSymbols(): List<FinnhubSymbol> {
        val response = client.get().uri { uri ->
            uri.path("/api/v1/stock/symbol")
            uri.queryParam("exchange", "US")
            uri.queryParam("token", properties.apiKey)
            uri.build()
        }.retrieve()
        return response.body<List<FinnhubSymbol>>()
            ?: throw ProviderException.InvalidResponse("finnhub: empty symbol list")
    }

    companion object {
        const val PAGE_SIZE = 1000
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class FinnhubSymbol(
    @JsonProperty("symbol") val symbol: String?,
    @JsonProperty("description") val description: String?,
    @JsonProperty("mic") val mic: String?,
    @JsonProperty("type") val type: String?,
) {
    fun toReference(): CompanyReference? {
        val ticker = symbol?.takeIf { it.isNotBlank() } ?: return null
        val name = description?.takeIf { it.isNotBlank() } ?: return null
        return CompanyReference(
            ticker = ticker,
            name = name,
            exchange = mic?.let { MIC_EXCHANGES[it] ?: it },
            sector = null,
            industry = null,
            country = null,
        )
    }
}

private val MIC_EXCHANGES = mapOf(
    "XNYS" to "NYSE",
    "XNGS" to "Nasdaq",
    "XNMS" to "Nasdaq",
    "ARCX" to "NYSE Arca",
    "BATS" to "Cboe",
)
