package com.catalystradar.adapters.polygon

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

class PolygonNewsProviderTest {

    private val provider = PolygonNewsProvider(
        RestClient.builder(),
        PolygonProperties(baseUrl = wireMock.baseUrl(), apiKey = "test-key"),
    )

    @Test
    fun `maps news page to raw articles`() = runTest {
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news"))
                .withQueryParam("ticker", equalTo("DELL"))
                .withQueryParam("apiKey", equalTo("test-key"))
                .willReturn(okJson(NEWS_PAGE)),
        )

        val result = provider.fetch(NewsFetchRequest(tickers = listOf("DELL")))

        assertEquals(2, result.articles.size)
        assertEquals("polygon", result.articles[0].provider)
        assertEquals("poly-1", result.articles[0].providerArticleId)
        assertEquals("Dell raises forecast", result.articles[0].title)
        assertEquals(listOf("DELL"), result.articles[0].tickers)
        assertEquals(Instant.parse("2026-09-16T14:30:00Z"), result.articles[0].publishedAt)
        assertEquals("https://example.com/a", result.articles[1].url)
        assertNull(result.nextCursor)
    }

    @Test
    fun `follows provider cursor`() = runTest {
        val nextUrl = "${wireMock.baseUrl()}/v2/reference/news?cursor=abc"
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news"))
                .willReturn(okJson(page(nextUrl))),
        )
        wireMock.stubFor(
            get(urlPathEqualTo("/v2/reference/news"))
                .withQueryParam("cursor", equalTo("abc"))
                .willReturn(okJson(EMPTY_PAGE)),
        )

        val first = provider.fetch(NewsFetchRequest())
        val second = provider.fetch(NewsFetchRequest(cursor = first.nextCursor))

        assertEquals(nextUrl, first.nextCursor)
        assertEquals(0, second.articles.size)
        assertNull(second.nextCursor)
    }

    private fun page(nextUrl: String) = NEWS_PAGE.replace(
        "\"status\": \"OK\"",
        "\"next_url\": \"$nextUrl\", \"status\": \"OK\"",
    )

    companion object {
        @RegisterExtension
        @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        const val EMPTY_PAGE = """{"results": [], "status": "OK", "request_id": "r0", "count": 0}"""

        const val NEWS_PAGE = """{
  "results": [
    {
      "id": "poly-1",
      "publisher": {"name": "Reuters", "homepage_url": "https://reuters.com", "logo_url": null, "favicon_url": null},
      "title": "Dell raises forecast",
      "author": "J. Doe",
      "published_utc": "2026-09-16T14:30:00Z",
      "article_url": "https://example.com/a",
      "tickers": ["DELL"],
      "amp_url": null,
      "image_url": null,
      "description": "Dell raised its full-year outlook.",
      "keywords": ["guidance"],
      "insights": []
    },
    {
      "id": "poly-2",
      "title": "Dell outlook repeat",
      "published_utc": "not-a-timestamp",
      "article_url": "https://example.com/a",
      "tickers": ["DELL"],
      "description": "Syndicated repeat."
    }
  ],
  "status": "OK",
  "request_id": "req-1",
  "count": 2
}"""
    }
}
