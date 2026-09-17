package com.catalystradar.application.ingestion

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * The scheduler delegates cycles to the ingestion service. The bean only
 * exists with catalyst.ingestion.enabled=true (off by default).
 */
@SpringBootTest(
    classes = [IngestionScheduler::class],
    properties = ["catalyst.ingestion.enabled=true"],
)
class IngestionSchedulerTest {

    @MockitoBean
    private lateinit var service: IngestionService

    @Autowired
    private lateinit var scheduler: IngestionScheduler

    @Test
    fun `manual trigger runs one ingestion cycle`() = runTest {
        whenever(service.ingestCycle(any())).thenReturn(IngestionCycleResult(emptyList()))

        scheduler.runIngestion()

        verify(service, times(1)).ingestCycle(any())
    }
}
