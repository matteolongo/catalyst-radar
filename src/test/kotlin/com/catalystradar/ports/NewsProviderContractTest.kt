package com.catalystradar.ports

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewsProviderContractTest {

    @Test
    fun `contract supports paged fetch through a stub`() = runTest {
        val provider = object : NewsProvider {
            override val name: String = "stub"

            override suspend fun fetch(request: NewsFetchRequest) = NewsFetchResult(
                articles = listOf(
                    RawArticle(
                        provider = "stub",
                        providerArticleId = "1",
                        url = null,
                        title = "Dell raises guidance",
                        body = "Dell raised its outlook.",
                        publishedAt = null,
                    ),
                ),
                nextCursor = "next",
            )
        }

        val result = provider.fetch(NewsFetchRequest(tickers = listOf("DELL"), pageSize = 10))

        assertEquals(1, result.articles.size)
        assertEquals("next", result.nextCursor)
    }

    @Test
    fun `request carries sane defaults`() {
        val request = NewsFetchRequest()

        assertEquals(emptyList(), request.tickers)
        assertEquals(50, request.pageSize)
        assertNull(request.cursor)
    }
}
