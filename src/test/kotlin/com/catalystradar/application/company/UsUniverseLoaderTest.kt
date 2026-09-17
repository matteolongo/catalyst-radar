package com.catalystradar.application.company

import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
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
}
