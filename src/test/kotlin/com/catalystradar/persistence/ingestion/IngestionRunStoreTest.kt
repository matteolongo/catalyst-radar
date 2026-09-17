package com.catalystradar.persistence.ingestion

import com.catalystradar.application.ingestion.IngestionStatus
import com.catalystradar.persistence.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Transactional
class IngestionRunStoreTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var runs: IngestionRunStore

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
}
