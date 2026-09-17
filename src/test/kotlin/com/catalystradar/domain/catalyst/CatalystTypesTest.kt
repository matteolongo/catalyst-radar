package com.catalystradar.domain.catalyst

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class CatalystTypesTest {

    @Test
    fun `state uses the closed v1 set`() {
        assertEquals(
            setOf(
                CatalystState.NORMAL,
                CatalystState.WATCH,
                CatalystState.BUILDING,
                CatalystState.CATALYZED,
                CatalystState.HIGH,
            ),
            CatalystState.entries.toSet(),
        )
    }

    @Test
    fun `creates versioned score within zero to one hundred`() {
        val score = CatalystScore(value = 63.5, version = "score-v1")

        assertEquals(63.5, score.value)
        assertEquals("score-v1", score.version)
    }

    @Test
    fun `rejects score below zero`() {
        assertThrows<IllegalArgumentException> {
            CatalystScore(value = -0.1, version = "score-v1")
        }
    }

    @Test
    fun `rejects score above one hundred`() {
        assertThrows<IllegalArgumentException> {
            CatalystScore(value = 100.1, version = "score-v1")
        }
    }

    @Test
    fun `rejects blank score version`() {
        assertThrows<IllegalArgumentException> {
            CatalystScore(value = 50.0, version = "  ")
        }
    }

    @Test
    fun `creates snapshot tying score state velocity and versions`() {
        val companyId = UUID.randomUUID()
        val asOf = Instant.parse("2026-09-16T10:00:00Z")

        val snapshot = CatalystSnapshot(
            companyId = companyId,
            score = CatalystScore(value = 63.5, version = "score-v1"),
            state = CatalystState.BUILDING,
            velocity = ScoreVelocity(velocity1d = 4.0, velocity3d = 11.5, velocity7d = 32.0),
            asOf = asOf,
            taxonomyVersion = "taxonomy-v1",
        )

        assertEquals(companyId, snapshot.companyId)
        assertEquals(CatalystState.BUILDING, snapshot.state)
        assertEquals(32.0, snapshot.velocity.velocity7d)
        assertEquals(asOf, snapshot.asOf)
        assertEquals("score-v1", snapshot.score.version)
    }
}
