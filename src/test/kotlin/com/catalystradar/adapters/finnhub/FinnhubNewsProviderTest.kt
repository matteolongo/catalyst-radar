package com.catalystradar.adapters.finnhub

import com.catalystradar.ports.NewsFetchRequest
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.web.client.RestClient
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FinnhubNewsProviderTest {

    private val provider = FinnhubNewsProvider(
        RestClient.builder(),
        FinnhubProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
    )

    @Test
    fun `maps company news to raw articles`() = runTest {
        wireMock.stubFor(
            get(urlPathEqualTo("/api/v1/company-news"))
                .withQueryParam("symbol", equalTo("DELL"))
                .withQueryParam("token", equalTo("test-key"))
                .willReturn(okJson(NEWS_ARRAY)),
        )

        val result = provider.fetch(NewsFetchRequest(tickers = listOf("DELL")))

        assertEquals(2, result.articles.size)
        assertEquals("finnhub", result.articles[0].provider)
        assertEquals("123456", result.articles[0].providerArticleId)
        assertEquals("Dell raises forecast", result.articles[0].title)
        assertEquals(listOf("DELL"), result.articles[0].tickers)
        assertEquals(Instant.ofEpochSecond(1758028200L), result.articles[0].publishedAt)
        assertEquals("https://example.com/b", result.articles[1].url)
        assertEquals(listOf("DELL", "HPQ"), result.articles[1].tickers)
        assertNull(result.nextCursor)
    }

    companion object {
        @RegisterExtension
        @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        const val NEWS_ARRAY = """[
  {
    "category": "company",
    "datetime": 1758028200,
    "headline": "Dell raises forecast",
    "id": 123456,
    "image": "",
    "related": "DELL",
    "source": "Reuters",
    "summary": "Dell raised its full-year outlook.",
    "url": "https://example.com/a"
  },
  {
    "category": "company",
    "datetime": 1758028300,
    "headline": "Dell outlook repeat",
    "id": 123457,
    "image": "",
    "related": "DELL,HPQ",
    "source": "Reuters",
    "summary": "Syndicated repeat.",
    "url": "https://example.com/b"
  }
]"""
    }
}
