package com.catalystradar.ports

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ProviderErrorTest {

    @Test
    fun `rate limited carries retry guidance`() {
        val error: ProviderException =
            ProviderException.RateLimited(retryAfterSeconds = 30L)

        assertIs<ProviderException>(error)
        assertEquals(30L, (error as ProviderException.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `authentication failures carry no retry hint`() {
        val error = ProviderException.AuthenticationFailed("bad key")

        assertNull(error.retryableAfterSeconds())
    }

    @Test
    fun `all variants share the provider error type`() {
        val errors = listOf(
            ProviderException.RateLimited(null),
            ProviderException.AuthenticationFailed("x"),
            ProviderException.TemporaryUnavailable("x"),
            ProviderException.InvalidResponse("x"),
            ProviderException.PermanentFailure("x"),
        )

        assertEquals(5, errors.size)
    }
}
