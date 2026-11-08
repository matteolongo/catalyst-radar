package com.catalystradar.application.company

import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Transactional
class UsUniverseLoaderTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var loader: UsUniverseLoader

    @Autowired
    private lateinit var companies: CompanyStore

    @Test
    fun `loads the versioned US universe`() {
        val result = loader.load()

        assertTrue(result.loaded >= 500, "expected S&P 500 + Nasdaq-100 union, got ${result.loaded}")
        assertEquals(0, result.skipped)
    }

    @Test
    fun `second load inserts nothing new`() {
        loader.load()
        val second = loader.load()

        assertEquals(0, second.loaded)
        assertTrue(second.skipped >= 500)
    }

    @Test
    fun `spot checks known constituents`() {
        loader.load()

        val dell = companies.findByTicker("DELL")
        assertNotNull(dell)
        assertEquals("Dell Technologies Inc. Class C", dell.name)

        val nvidia = companies.findByTicker("NVDA")
        assertNotNull(nvidia)
        assertEquals("Nasdaq", nvidia.exchange)
    }

    @Test
    fun `watchlist changes preserve company identity and restrict active companies`() {
        val existing = companies.save(Company(ticker = "DELL", name = "Dell"))
        loader.loadWatchlist(setOf("DELL", "NVDA"))
        val nvidia = assertNotNull(companies.findByTicker("NVDA"))

        loader.loadWatchlist(setOf("DELL"))
        assertEquals(listOf(existing.id), companies.findAllActive().map { it.id })
        assertFalse(assertNotNull(companies.findById(nvidia.id)).active)

        loader.loadWatchlist(setOf("NVDA"))
        assertEquals(listOf(nvidia.id), companies.findAllActive().map { it.id })
    }

    @Test
    fun `unknown watchlist ticker leaves the active companies intact`() {
        val existing = companies.save(Company(ticker = "DELL", name = "Dell"))

        assertFailsWith<IllegalArgumentException> {
            loader.loadWatchlist(setOf("NVDA", "NOT-A-LISTED-STOCK"))
        }
        assertEquals(listOf(existing.id), companies.findAllActive().map { it.id })
    }
}
