package com.catalystradar.persistence.security

import com.catalystradar.persistence.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class ApiClientStoreTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var clients: ApiClientStore

    @Test
    fun `persists client with key hash and prefix`() {
        val saved = clients.save("backtester", "a1b2c3d4", "deadbeef".repeat(8))

        assertEquals("backtester", saved.name)
        assertEquals("a1b2c3d4", saved.keyPrefix)
        assertTrue(saved.active)
    }

    @Test
    fun `finds candidates by prefix`() {
        clients.save("backtester", "a1b2c3d4", "hash")

        val found = clients.findByPrefix("a1b2c3d4")

        assertNotNull(found)
        assertEquals("backtester", found.name)
    }

    @Test
    fun `unknown prefix returns null`() {
        assertNull(clients.findByPrefix("zzzzzzzz"))
    }

    @Test
    fun `disabling rejects later use`() {
        val saved = clients.save("backtester", "a1b2c3d4", "hash")
        clients.setActive(saved.id, false)

        assertEquals(false, clients.findByPrefix("a1b2c3d4")?.active)
    }

    @Test
    fun `last used tracks authentication`() {
        val saved = clients.save("backtester", "a1b2c3d4", "hash")
        assertNull(clients.findByPrefix("a1b2c3d4")?.lastUsedAt)

        clients.updateLastUsed(saved.id, java.time.Instant.parse("2026-09-16T10:00:00Z"))

        assertNotNull(clients.findByPrefix("a1b2c3d4")?.lastUsedAt)
    }
}
