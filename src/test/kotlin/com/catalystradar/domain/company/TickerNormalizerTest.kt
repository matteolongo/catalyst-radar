package com.catalystradar.domain.company

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class TickerNormalizerTest {

    @Test
    fun `trims and uppercases tickers`() {
        assertEquals("DELL", normalizeTicker("  dell "))
    }

    @Test
    fun `keeps class share suffixes distinct`() {
        assertEquals("BRK.B", normalizeTicker("brk.b"))
    }

    @Test
    fun `rejects blank tickers`() {
        assertThrows<IllegalArgumentException> {
            normalizeTicker("   ")
        }
    }

    @Test
    fun `rejects tickers with illegal characters`() {
        assertThrows<IllegalArgumentException> {
            normalizeTicker("D3LL!")
        }
    }
}
