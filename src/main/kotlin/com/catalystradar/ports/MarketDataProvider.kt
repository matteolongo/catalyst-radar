package com.catalystradar.ports

import java.time.LocalDate

/**
 * Daily market prices for benchmark/forward-return evaluation only.
 * Prices never feed catalyst score-v1.
 */
interface MarketDataProvider {
    suspend fun dailyBars(ticker: String, from: LocalDate, to: LocalDate): List<DailyBar>
}

data class DailyBar(
    val ticker: String,
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long?,
)
