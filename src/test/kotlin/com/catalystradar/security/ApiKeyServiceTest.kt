package com.catalystradar.security

import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.security.ApiClientStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Transactional
class ApiKeyServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var service: ApiKeyService

    @Autowired
    private lateinit var clients: ApiClientStore

    @Test
    fun `creates keys shown once with hashed storage`() {
        val created = service.create("backtester")

        assertTrue(created.rawKey.startsWith("cr_live_"))
        val stored = clients.findByPrefix(created.prefix)
        assertNotNull(stored)
        assertNotEquals(created.rawKey, stored.keyHash)
    }

    @Test
    fun `authenticates a valid key`() {
        val created = service.create("backtester")

        val result = service.authenticate(created.rawKey)

        assertIs<ApiKeyAuthResult.Authenticated>(result)
        assertEquals("backtester", (result as ApiKeyAuthResult.Authenticated).name)
    }

    @Test
    fun `rejects unknown prefix`() {
        assertIs<ApiKeyAuthResult.UnknownKey>(service.authenticate("cr_live_zzzzzzzz_" + "x".repeat(43)))
    }

    @Test
    fun `rejects disabled keys`() {
        val created = service.create("backtester")
        clients.setActive(created.clientId, false)

        assertIs<ApiKeyAuthResult.DisabledKey>(service.authenticate(created.rawKey))
    }

    @Test
    fun `rejects wrong secrets in constant time shape`() {
        val created = service.create("backtester")
        val tampered = created.rawKey.dropLast(1) + if (created.rawKey.last() == 'A') 'B' else 'A'

        assertIs<ApiKeyAuthResult.InvalidSecret>(service.authenticate(tampered))
    }

    @Test
    fun `rejects malformed keys`() {
        assertIs<ApiKeyAuthResult.MalformedKey>(service.authenticate("not-a-key"))
        assertIs<ApiKeyAuthResult.MalformedKey>(service.authenticate("cr_live_short"))
    }

    @Test
    fun `generated keys are unique`() {
        val first = ApiKeyGenerator.generate()
        val second = ApiKeyGenerator.generate()

        assertNotEquals(first.rawKey, second.rawKey)
    }
}
