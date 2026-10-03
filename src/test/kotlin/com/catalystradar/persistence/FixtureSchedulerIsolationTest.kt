package com.catalystradar.persistence

import com.catalystradar.application.catalyst.DailySnapshotScheduler
import com.catalystradar.application.ingestion.IngestionScheduler
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.core.env.ConfigurableEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Fixture contexts must behave identically on every developer machine,
 * CI runner, and shell. `CATALYST_INGESTION_ENABLED=true` in the process
 * environment binds to `catalyst.ingestion.enabled` and would otherwise
 * activate a real scheduler against real provider accounts, racing the
 * explicit triggers these tests own.
 *
 * The scheduler-specific tests (IngestionSchedulerTest,
 * DailySnapshotSchedulerTest) opt in explicitly and do not use this base.
 */
class FixtureSchedulerIsolationTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var environment: ConfigurableEnvironment

    @Test
    fun `fixture context pins scheduler flags above inherited environment settings`() {
        assertPinnedToFalse(INGESTION_ENABLED)
        assertPinnedToFalse(SNAPSHOTS_ENABLED)
        assertFalse(
            environment.getProperty(INGESTION_ENABLED, Boolean::class.java) ?: true,
            "ingestion scheduler must be disabled in a fixture context",
        )
        assertFalse(
            environment.getProperty(SNAPSHOTS_ENABLED, Boolean::class.java) ?: true,
            "daily snapshot scheduler must be disabled in a fixture context",
        )
    }

    @Test
    fun `fixture context registers no background schedulers`() {
        assertEquals(emptyList(), context.getBeanNamesForType(IngestionScheduler::class.java).toList())
        assertEquals(emptyList(), context.getBeanNamesForType(DailySnapshotScheduler::class.java).toList())
    }

    /**
     * Reads the highest-precedence property source that defines the flag
     * instead of only the resolved value, so the assertion still holds on
     * a shell where neither variable happens to be exported.
     */
    private fun assertPinnedToFalse(name: String) {
        val source = environment.propertySources.firstOrNull { it.containsProperty(name) }
        assertNotNull(source, "$name must be pinned by the fixture context, not left to the environment")
        assertEquals("false", source.getProperty(name))
    }

    private companion object {
        const val INGESTION_ENABLED = "catalyst.ingestion.enabled"
        const val SNAPSHOTS_ENABLED = "catalyst.snapshots.enabled"
    }
}
