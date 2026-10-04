package com.catalystradar.persistence.ingestion

import com.catalystradar.application.ingestion.IngestionStatus
import com.catalystradar.operations.OperationsFixtures
import com.catalystradar.persistence.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class IngestionRunStoreTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var runs: IngestionRunStore

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Test
    fun `starts runs as running`() {
        val id = runs.startRun("polygon")

        val run = runs.findById(id)

        assertNotNull(run)
        assertEquals(IngestionStatus.RUNNING, run.status)
        assertNull(run.finishedAt)
    }

    @Test
    fun `finishes runs with counters`() {
        val id = runs.startRun("polygon")

        runs.finishRun(
            id = id,
            status = IngestionStatus.SUCCESS,
            fetched = 10,
            added = 7,
            duplicates = 3,
            error = null,
        )

        val run = runs.findById(id)

        assertNotNull(run)
        assertEquals(IngestionStatus.SUCCESS, run.status)
        assertEquals(10, run.fetched)
        assertEquals(7, run.added)
        assertEquals(3, run.duplicates)
        assertNotNull(run.finishedAt)
    }

    @Test
    fun `legacy run projections retain persisted operation and error codes`() {
        val at = Instant.parse("2099-10-04T10:00:00Z")
        val operationRunId = OperationsFixtures(jdbc).run(at)
        val ingestionRunId = UUID.randomUUID()
        jdbc.sql("""INSERT INTO ingestion_runs(id,provider,status,operation_run_id,error_code,started_at,finished_at)
            VALUES(:id,'polygon','FAILED',:runId,'RATE_LIMITED',:started::timestamptz,:finished::timestamptz)""")
            .param("id", ingestionRunId).param("runId", operationRunId)
            .param("started", at.toString()).param("finished", at.plusSeconds(5).toString()).update()

        val run = requireNotNull(runs.findById(ingestionRunId))

        assertEquals(operationRunId, run.runId)
        assertEquals("RATE_LIMITED", run.errorCode)
    }

    @Test
    fun `lists recent runs newest first`() {
        val first = runs.startRun("polygon")
        Thread.sleep(5)
        val second = runs.startRun("finnhub")

        // Other suites share this container and may leave committed rows
        // behind, so assert relative order, not exact contents.
        val ids = runs.listRecent(100).map { it.id }

        assertTrue(ids.indexOf(second) < ids.indexOf(first))
    }
}
