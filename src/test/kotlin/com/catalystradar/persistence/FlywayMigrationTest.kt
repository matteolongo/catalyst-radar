package com.catalystradar.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlywayMigrationTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var flyway: Flyway

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Test
    fun `applies initial schema from empty database`() {
        val applied = flyway.info().applied()

        assertTrue(applied.any { it.version.version == "1" }, "V1 migration not applied: $applied")
    }

    @Test
    fun `creates expected v1 tables`() {
        val tables = jdbc.sql(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public'",
        ).query(String::class.java).list().toSet()

        assertEquals(
            setOf(
                "companies",
                "company_aliases",
                "source_documents",
                "event_clusters",
                "events",
                "catalyst_snapshots",
                "state_transitions",
                "model_runs",
                "ingestion_runs",
                "api_clients",
                "score_versions",
                "flyway_schema_history",
            ),
            tables,
        )
    }

    @Test
    fun `enables vector extension`() {
        val extensions = jdbc.sql(
            "SELECT extname FROM pg_extension WHERE extname IN ('vector', 'pgcrypto')",
        ).query(String::class.java).list().toSet()

        assertEquals(setOf("vector", "pgcrypto"), extensions)
    }
}
