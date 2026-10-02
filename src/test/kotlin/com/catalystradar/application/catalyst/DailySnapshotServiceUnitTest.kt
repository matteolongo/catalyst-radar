package com.catalystradar.application.catalyst

import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.company.CompanyStore
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import kotlin.test.assertEquals

class DailySnapshotServiceUnitTest {

    private val companies = mock<CompanyStore>()
    private val catalyst = mock<CatalystService>()
    private val service = DailySnapshotService(companies, catalyst)
    private val asOf = Instant.parse("2026-09-23T10:00:00Z")

    @Test
    fun `continues after one company recalculation fails`() {
        val failing = Company(ticker = "AAA", name = "Failing")
        val succeeding = Company(ticker = "ZZZ", name = "Succeeding")
        whenever(companies.findAllActive()).thenReturn(listOf(succeeding, failing))
        whenever(catalyst.recalculate(eq(failing.id), any())).thenThrow(IllegalStateException("database timeout"))

        val result = service.recalculateActive(asOf)

        assertEquals(DailySnapshotStatus.PARTIAL, result.status)
        assertEquals(2, result.companiesConsidered)
        assertEquals(1, result.companiesRecalculated)
        assertEquals(1, result.failures)
        verify(catalyst).recalculate(succeeding.id, asOf)
    }
}
