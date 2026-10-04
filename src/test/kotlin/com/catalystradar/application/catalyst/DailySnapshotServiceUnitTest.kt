package com.catalystradar.application.catalyst

import com.catalystradar.domain.company.Company
import com.catalystradar.application.operations.*
import com.catalystradar.persistence.company.CompanyStore
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DailySnapshotServiceUnitTest {

    private val companies = mock<CompanyStore>()
    private val catalyst = mock<CatalystService>()
    private val runId = UUID.randomUUID()
    private val recorder = mock<OperationRunRecorder> {
        on { begin(any(), any(), any()) }.thenReturn(runId)
    }
    private val service = DailySnapshotService(companies, catalyst, recorder)
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

    @Test
    fun `company cancellation propagates instead of becoming a partial cycle`() {
        val company = Company(ticker = "AAA", name = "Cancelled")
        whenever(companies.findAllActive()).thenReturn(listOf(company))
        whenever(catalyst.recalculate(eq(company.id), any())).thenThrow(CancellationException("cancelled"))

        assertFailsWith<CancellationException> { service.recalculateActive(asOf) }
        verify(recorder).finish(runId, OperationStatus.CANCELLED, null, "CANCELLED", false)
    }

    @Test
    fun `company lookup failure closes the cycle without complete capture`() {
        whenever(companies.findAllActive()).thenThrow(IllegalStateException("database unavailable"))

        assertFailsWith<IllegalStateException> { service.recalculateActive(asOf) }

        verify(recorder).finish(runId, OperationStatus.FAILED, null, "SCORING_FAILURE", false)
    }
}
