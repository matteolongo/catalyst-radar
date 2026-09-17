package com.catalystradar.persistence.company

import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Transactional
class CompanyStoreTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var store: CompanyStore

    @Test
    fun `persists and reloads company by ticker`() {
        val saved = store.save(
            Company(
                ticker = "DELL",
                name = "Dell Technologies",
                exchange = "NYSE",
                sector = "Technology",
                industry = "Computer Hardware",
                country = "US",
            ),
        )

        val reloaded = store.findByTicker("DELL")

        assertNotNull(reloaded)
        assertEquals(saved.id, reloaded.id)
        assertEquals("Dell Technologies", reloaded.name)
        assertEquals("NYSE", reloaded.exchange)
        assertEquals("Technology", reloaded.sector)
        assertEquals("US", reloaded.country)
    }

    @Test
    fun `rejects duplicate ticker`() {
        store.save(Company(ticker = "DELL", name = "Dell Technologies"))

        assertThrows<DataIntegrityViolationException> {
            store.save(Company(ticker = "DELL", name = "Dell Inc."))
        }
    }

    @Test
    fun `returns null for unknown ticker`() {
        assertNull(store.findByTicker("NOPE"))
    }
}
