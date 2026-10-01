package com.catalystradar.application.evaluation

import com.catalystradar.ports.DailyBar
import java.time.LocalDate

/**
 * Session-counted forward math over trading-day bars. Sessions count by
 * bar index (weekends never appear), the base is the last bar at or
 * before T0, and gaps yield null instead of fabricated prices.
 */
fun forwardReturn(bars: List<DailyBar>, t0: LocalDate, sessions: Int): Double? {
    val window = window(bars, t0, sessions) ?: return null
    return window.second.close / window.first.close - 1
}

/** Max favorable / adverse excursion over the horizon window. */
fun excursions(bars: List<DailyBar>, t0: LocalDate, sessions: Int): Pair<Double?, Double?> {
    val ordered = bars.sortedBy { it.date }
    val baseIdx = ordered.indexOfLast { !it.date.isAfter(t0) }
    if (baseIdx < 0 || baseIdx + sessions >= ordered.size) return null to null
    val base = ordered[baseIdx].close
    val slice = ordered.subList(baseIdx + 1, baseIdx + sessions + 1)
    return (slice.maxOf { it.high } / base - 1) to (slice.minOf { it.low } / base - 1)
}

private fun window(bars: List<DailyBar>, t0: LocalDate, sessions: Int): Pair<DailyBar, DailyBar>? {
    val ordered = bars.sortedBy { it.date }
    val baseIdx = ordered.indexOfLast { !it.date.isAfter(t0) }
    if (baseIdx < 0 || baseIdx + sessions >= ordered.size) return null
    return ordered[baseIdx] to ordered[baseIdx + sessions]
}
