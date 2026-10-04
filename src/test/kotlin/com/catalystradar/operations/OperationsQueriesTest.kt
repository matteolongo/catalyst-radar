package com.catalystradar.operations

import com.catalystradar.application.operations.ActivityWindowResolver
import com.catalystradar.application.operations.CursorPosition
import com.catalystradar.application.operations.OperationsCursor
import com.catalystradar.application.operations.WindowPreset
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OperationsQueriesTest {
    @Test
    fun `activity upper boundary is stable and exclusive`() {
        val now = Instant.parse("2026-10-04T12:00:00Z")
        val window = ActivityWindowResolver().resolve(WindowPreset.HOURS_24, null, null, now)
        assertEquals(Instant.parse("2026-10-03T12:00:00Z"), window.from)
        assertEquals(now, window.to)
        assertFailsWith<IllegalArgumentException> {
            ActivityWindowResolver().resolve(null, now, now, now)
        }
        assertFailsWith<IllegalArgumentException> {
            ActivityWindowResolver().resolve(WindowPreset.HOURS_24, window.from, window.to, now)
        }
    }

    @Test
    fun `cursor preserves tied UUID and rejects another filter`() {
        val codec = OperationsCursor()
        val at = Instant.parse("2026-10-04T12:00:00.123456Z")
        val position = CursorPosition(at, UUID.fromString("10000000-0000-0000-0000-000000000001"))
        val filters = mapOf("status" to "RETRYABLE_ERROR", "sort" to "discovered_desc")
        val token = codec.encode("documents", position, filters)
        assertEquals(position, codec.decode(token, "documents", filters))
        assertFailsWith<IllegalArgumentException> {
            codec.decode(token, "documents", filters + ("status" to "TERMINAL_ERROR"))
        }
        assertFailsWith<IllegalArgumentException> { codec.decode(token, "models", filters) }
        assertFailsWith<IllegalArgumentException> { codec.decode("bad", "documents", filters) }
    }
}
