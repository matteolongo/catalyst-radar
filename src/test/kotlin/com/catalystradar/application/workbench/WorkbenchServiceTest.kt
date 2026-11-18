package com.catalystradar.application.workbench

import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.ingestion.IngestionService
import com.catalystradar.application.pipeline.PipelineService
import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.company.CompanyStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFailsWith

class WorkbenchServiceTest {

    private val now = Instant.parse("2026-11-17T12:00:00Z")
    private val pipeline = mock<PipelineService>()
    private val ingestion = mock<IngestionService>()
    private val companies = mock<CompanyStore> {
        on { findByTicker("TSN") } doReturn Company(ticker = "TSN", name = "Tyson Foods")
        on { findByTicker("OLD") } doReturn Company(ticker = "OLD", name = "Delisted", active = false)
    }
    private val workbench = WorkbenchService(pipeline, ingestion, companies)

    @Test
    fun `fetch refuses an untracked symbol before any provider call`() = runTest {
        assertFailsWith<CompanyNotFoundException> {
            workbench.fetch(listOf("TSN", "SLS"), now.minus(Duration.ofDays(7)), now)
        }
        verifyNoInteractions(pipeline, ingestion)
    }

    @Test
    fun `fetch refuses an inactive company`() = runTest {
        assertFailsWith<CompanyNotFoundException> { workbench.fetch(listOf("OLD"), now.minus(Duration.ofDays(7)), now) }
        verifyNoInteractions(ingestion)
    }

    @Test
    fun `a busy pipeline makes a step fail instead of doing nothing silently`() = runTest {
        whenever(pipeline.exclusively<Any>(any())).thenReturn(null)
        whenever(pipeline.extractDocuments(any(), any())).thenReturn(null)

        assertFailsWith<PipelineBusyException> { workbench.scan(now.minus(Duration.ofHours(2)), now) }
        assertFailsWith<PipelineBusyException> { workbench.fetch(listOf("TSN"), now.minus(Duration.ofDays(7)), now) }
        assertFailsWith<PipelineBusyException> { workbench.extract(emptyList(), now) }
        verifyNoInteractions(ingestion)
    }
}
