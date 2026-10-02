package com.catalystradar.application.ingestion

import kotlinx.coroutines.test.runTest
import com.catalystradar.application.pipeline.PipelineResult
import com.catalystradar.application.pipeline.PipelineService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * The scheduler delegates cycles to the full pipeline. The bean only
 * exists with catalyst.ingestion.enabled=true (off by default).
 */
@SpringBootTest(
    classes = [IngestionScheduler::class],
    properties = ["catalyst.ingestion.enabled=true"],
)
class IngestionSchedulerTest {

    @MockitoBean
    private lateinit var pipeline: PipelineService

    @Autowired
    private lateinit var scheduler: IngestionScheduler

    @Test
    fun `manual trigger runs one pipeline cycle`() = runTest {
        whenever(pipeline.runCycle(any())).thenReturn(PipelineResult("SUCCESS", 0, 0, 0))

        scheduler.runIngestion()

        verify(pipeline, times(1)).runCycle(any())
    }
}
