package com.catalystradar.ports

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReferenceContractsTest {

    @Test
    fun `reference provider pages the equity universe`() = runTest {
        val provider = object : CompanyReferenceProvider {
            override suspend fun listUsEquities(cursor: String?) = CompanyPage(
                values = listOf(
                    CompanyReference(ticker = "DELL", name = "Dell Technologies Inc. Class C"),
                ),
                nextCursor = null,
            )
        }

        val page = provider.listUsEquities()

        assertEquals(1, page.values.size)
        assertEquals("DELL", page.values[0].ticker)
        assertNull(page.nextCursor)
    }

    @Test
    fun `market data provider returns daily bars`() = runTest {
        val provider = object : MarketDataProvider {
            override suspend fun dailyBars(ticker: String, from: LocalDate, to: LocalDate) = listOf(
                DailyBar(
                    ticker = ticker,
                    date = from,
                    open = 100.0,
                    high = 105.0,
                    low = 99.0,
                    close = 104.0,
                    volume = 1_000_000L,
                ),
            )
        }

        val bars = provider.dailyBars("DELL", LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-16"))

        assertEquals(1, bars.size)
        assertEquals(104.0, bars[0].close)
    }
}
