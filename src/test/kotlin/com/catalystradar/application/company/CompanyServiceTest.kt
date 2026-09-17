package com.catalystradar.application.company

import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Transactional
class CompanyServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var service: CompanyService

    @Autowired
    private lateinit var companies: CompanyStore

    @Test
    fun `finds company case insensitively`() {
        companies.save(Company(ticker = "DELL", name = "Dell Technologies Inc. Class C"))

        val found = service.findByTicker("dell")

        assertNotNull(found)
        assertEquals("DELL", found.ticker)
    }

    @Test
    fun `returns null for unsupported ticker`() {
        assertNull(service.findByTicker("NOPE"))
    }
}
