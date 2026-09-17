package com.catalystradar.domain.company

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompanyTest {

    @Test
    fun `creates company with required fields`() {
        val company = Company(ticker = "DELL", name = "Dell Technologies")

        assertEquals("DELL", company.ticker)
        assertEquals("Dell Technologies", company.name)
        assertTrue(company.active)
    }

    @Test
    fun `rejects blank ticker`() {
        assertThrows<IllegalArgumentException> {
            Company(ticker = "  ", name = "Dell Technologies")
        }
    }

    @Test
    fun `rejects blank name`() {
        assertThrows<IllegalArgumentException> {
            Company(ticker = "DELL", name = "")
        }
    }
}
