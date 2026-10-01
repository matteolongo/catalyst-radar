package com.catalystradar.application.evaluation

import com.catalystradar.ports.DailyBar
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForwardReturnsTest {

    private val t0 = LocalDate.parse("2026-09-01")

    @Test
    fun `computes session-counted forward returns`() {
        val bars = listOf(
            bar("2026-08-31", 99.0, 100.0, 98.0, 99.5),
            bar("2026-09-01", 100.0, 101.0, 99.0, 100.0),
            bar("2026-09-02", 105.0, 106.0, 104.0, 105.0),
            bar("2026-09-03", 111.0, 113.0, 110.0, 112.0),
        )

        assertEquals(0.05, forwardReturn(bars, t0, 1)!!, 1e-9)
        assertEquals(0.12, forwardReturn(bars, t0, 2)!!, 1e-9)
    }

    @Test
    fun `weekends never count as sessions`() {
        // Bars carry trading days only; sessions count by index.
        val bars = listOf(
            bar("2026-08-28", 90.0, 91.0, 89.0, 90.0),
            bar("2026-08-31", 100.0, 101.0, 99.0, 100.0),
            bar("2026-09-01", 110.0, 111.0, 109.0, 110.0),
        )

        assertEquals(0.10, forwardReturn(bars, LocalDate.parse("2026-08-31"), 1)!!, 1e-9)
    }

    @Test
    fun `missing base or horizon yields null`() {
        val bars = listOf(bar("2026-09-01", 100.0, 101.0, 99.0, 100.0))

        assertNull(forwardReturn(bars, LocalDate.parse("2026-08-01"), 1))
        assertNull(forwardReturn(bars, t0, 5))
        assertNull(forwardReturn(emptyList(), t0, 1))
    }

    @Test
    fun `base falls back to the last bar at or before t0`() {
        val bars = listOf(
            bar("2026-08-28", 90.0, 91.0, 89.0, 90.0),
            bar("2026-09-02", 99.0, 100.0, 98.0, 99.0),
        )

        // T0 is a Sunday; base is Friday's close.
        assertEquals(99.0 / 90.0 - 1, forwardReturn(bars, LocalDate.parse("2026-08-30"), 1)!!, 1e-9)
    }

    @Test
    fun `excursions span the horizon window`() {
        val bars = listOf(
            bar("2026-09-01", 100.0, 101.0, 99.0, 100.0),
            bar("2026-09-02", 105.0, 106.0, 104.0, 105.0),
            bar("2026-09-03", 111.0, 113.0, 110.0, 112.0),
        )

        val (mfe, mae) = excursions(bars, t0, 2)

        assertEquals(0.13, mfe!!, 1e-9)
        assertEquals(0.04, mae!!, 1e-9)
    }

    private fun bar(date: String, open: Double, high: Double, low: Double, close: Double) =
        DailyBar("DELL", LocalDate.parse(date), open, high, low, close, 1_000_000L)
}
