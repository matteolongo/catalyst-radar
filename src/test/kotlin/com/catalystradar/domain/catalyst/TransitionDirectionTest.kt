package com.catalystradar.domain.catalyst

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class TransitionDirectionTest {

    @Test
    fun `moving to a higher band is an upgrade`() {
        assertEquals(TransitionDirection.UPGRADE, TransitionDirection.of(CatalystState.NORMAL, CatalystState.WATCH))
        assertEquals(TransitionDirection.UPGRADE, TransitionDirection.of(CatalystState.WATCH, CatalystState.HIGH))
    }

    @Test
    fun `moving to a lower band is a downgrade`() {
        assertEquals(TransitionDirection.DOWNGRADE, TransitionDirection.of(CatalystState.CATALYZED, CatalystState.BUILDING))
        assertEquals(TransitionDirection.DOWNGRADE, TransitionDirection.of(CatalystState.HIGH, CatalystState.NORMAL))
    }

    @Test
    fun `an unchanged state is not a transition`() {
        assertThrows<IllegalArgumentException> { TransitionDirection.of(CatalystState.WATCH, CatalystState.WATCH) }
    }
}
