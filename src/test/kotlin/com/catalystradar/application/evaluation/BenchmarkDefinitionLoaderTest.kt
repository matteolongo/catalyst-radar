package com.catalystradar.application.evaluation

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class BenchmarkDefinitionLoaderTest {

    @Test
    fun `loads cases with matched controls`() {
        val definition = BenchmarkDefinitionLoader.load(SAMPLE)

        assertEquals(1, definition.cases.size)
        assertEquals("dell-2026-09", definition.cases[0].id)
        assertEquals("DELL", definition.cases[0].ticker)
        assertEquals(LocalDate.parse("2026-09-10"), definition.cases[0].t0)
        assertEquals(listOf("HPQ", "HPE"), definition.controls["dell-2026-09"])
        assertEquals(listOf(10, 7, 5, 3, 1), definition.lookbacks)
        assertEquals(listOf(1, 3, 5, 10), definition.horizons)
    }

    companion object {
        const val SAMPLE = """{
  "cases": [{"id": "dell-2026-09", "ticker": "DELL", "t0": "2026-09-10"}],
  "controls": {"dell-2026-09": ["HPQ", "HPE"]},
  "lookbacks": [10, 7, 5, 3, 1],
  "horizons": [1, 3, 5, 10]
}"""
    }
}
