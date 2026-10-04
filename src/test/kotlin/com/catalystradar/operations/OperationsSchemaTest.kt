package com.catalystradar.operations

import com.catalystradar.persistence.PostgresIntegrationTest
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Transactional
class OperationsSchemaTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var jdbc: JdbcClient
    private lateinit var fixtures: OperationsFixtures
    private val at = Instant.parse("2026-10-04T12:00:00Z")

    @BeforeEach
    fun setUp() { fixtures = OperationsFixtures(jdbc) }

    @Test
    fun `creates operational ledgers without replacing processing state`() {
        val names = jdbc.sql("SELECT tablename FROM pg_tables WHERE schemaname='public'")
            .query(String::class.java).list().toSet()
        assertTrue(names.containsAll(setOf("operation_runs", "document_processing_attempts", "operation_run_issues")))
        assertTrue("document_processing" in names)
    }

    @Test
    fun `upgrades V2 preserving legacy sources processing and known costs`() {
        val upgradeDatabase = PostgreSQLContainer<Nothing>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"),
        ).apply { start() }
        try {
            Flyway.configure().dataSource(upgradeDatabase.jdbcUrl, upgradeDatabase.username, upgradeDatabase.password)
                .target("2").load().migrate()
            val upgradeJdbc = JdbcClient.create(DriverManagerDataSource(
                upgradeDatabase.jdbcUrl, upgradeDatabase.username, upgradeDatabase.password,
            ))
            val upgradeFixtures = OperationsFixtures(upgradeJdbc)
            val doc = upgradeFixtures.document(at, body = "Original source body")
            upgradeFixtures.processing(doc, "PENDING", updatedAt = at)
            upgradeJdbc.sql("""
                INSERT INTO model_runs(id,provider,operation,model,source_document_id,input_tokens,output_tokens,estimated_cost,success)
                VALUES
                ('10000000-0000-0000-0000-000000000001','openai','extract','gpt-4o-mini',:doc,NULL,NULL,0,false),
                ('10000000-0000-0000-0000-000000000002','openai','extract','gpt-4o-mini',:doc,0,0,0,true),
                ('10000000-0000-0000-0000-000000000003','openai','extract','gpt-99',:doc,100,50,0,true),
                ('10000000-0000-0000-0000-000000000004','openai','extract','gpt-99',:doc,100,50,1.25,true),
                ('10000000-0000-0000-0000-000000000005','openai','embed','text-embedding-3-small',:doc,0,NULL,0,true),
                ('10000000-0000-0000-0000-000000000006','openai','extract','gpt-4o-mini',:doc,100,NULL,0,false)
            """).param("doc", doc).update()
            upgradeJdbc.sql("INSERT INTO ingestion_runs(provider,status) VALUES('polygon','SUCCESS')").update()
            Flyway.configure().dataSource(upgradeDatabase.jdbcUrl, upgradeDatabase.username, upgradeDatabase.password)
                .load().migrate()
            assertEquals("Original source body", upgradeJdbc.sql("SELECT body FROM source_documents WHERE id=:id")
                .param("id", doc).query(String::class.java).single())
            assertEquals("PENDING", upgradeFixtures.processingStatus(doc))
            val costs = upgradeJdbc.sql("SELECT id, estimated_cost FROM model_runs ORDER BY id")
                .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getBigDecimal("estimated_cost") }.list().toMap()
            assertEquals(6, costs.size)
            fun cost(number: Int) = costs[UUID.fromString("10000000-0000-0000-0000-${number.toString().padStart(12, '0')}")]
            assertNull(cost(1))
            assertEquals(0, assertNotNull(cost(2)).signum())
            assertNull(cost(3))
            assertEquals(0, assertNotNull(cost(4)).compareTo(java.math.BigDecimal("1.25")))
            assertEquals(0, assertNotNull(cost(5)).signum())
            assertNull(cost(6))
            assertEquals(0L, upgradeJdbc.sql("SELECT count(*) FROM source_documents WHERE first_ingestion_run_id IS NOT NULL")
                .query(Long::class.java).single())
            assertEquals(0L, upgradeJdbc.sql("SELECT count(*) FROM model_runs WHERE processing_attempt_id IS NOT NULL")
                .query(Long::class.java).single())
            assertEquals(0L, upgradeJdbc.sql("SELECT count(*) FROM ingestion_runs WHERE operation_run_id IS NOT NULL")
                .query(Long::class.java).single())
            for (table in listOf("operation_runs", "document_processing_attempts", "operation_run_issues")) {
                assertEquals(0L, upgradeJdbc.sql("SELECT count(*) FROM $table").query(Long::class.java).single())
            }
        } finally { upgradeDatabase.stop() }
    }
    @Test
    fun `model call can correlate only with an attempt of its source document`() {
        val document = fixtures.document(at)
        val attempt = fixtures.attempt(document, fixtures.run(at), 1, at)
        val model = fixtures.model(at, null, null, null, document = document, attempt = attempt)
        assertEquals(attempt, jdbc.sql("SELECT processing_attempt_id FROM model_runs WHERE id=:id")
            .param("id", model).query(UUID::class.java).single())
    }

    @Test
    fun `legacy model calls can omit attempt and source correlations`() {
        fixtures.model(at, null, null, null)
        fixtures.model(at, null, null, null, document = fixtures.document(at))
    }

    @Test
    fun `model call rejects an attempt belonging to another document`() {
        val attempt = fixtures.attempt(fixtures.document(at), fixtures.run(at), 1, at)
        assertFailsWith<DataIntegrityViolationException> {
            fixtures.model(at, null, null, null, document = fixtures.document(at), attempt = attempt)
        }
    }

    @Test
    fun `model call rejects attempt without source document`() {
        val attempt = fixtures.attempt(fixtures.document(at), fixtures.run(at), 1, at)
        assertFailsWith<DataIntegrityViolationException> {
            fixtures.model(at, null, null, null, attempt = attempt)
        }
    }

    @ParameterizedTest(name = "rejects negative run counter {0}")
    @ValueSource(strings = ["documents_considered", "documents_completed", "documents_skipped",
        "documents_retry_scheduled", "documents_terminal_failures", "events_inserted", "events_reused",
        "companies_considered", "companies_rescored", "companies_failed"])
    fun `run counters reject negatives`(counter: String) {
        val run = fixtures.run(at)
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.sql("UPDATE operation_runs SET $counter=-1 WHERE id=:id").param("id", run).update()
        }
    }

    @ParameterizedTest(name = "rejects invalid run field {0}")
    @ValueSource(strings = ["status", "phase", "kind", "trigger_type"])
    fun `run classifications reject unsupported values`(field: String) {
        val run = fixtures.run(at)
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.sql("UPDATE operation_runs SET $field='INVALID' WHERE id=:id").param("id", run).update()
        }
    }

    @ParameterizedTest(name = "rejects invalid attempt field {0}")
    @CsvSource("events_inserted,-1", "events_reused,-1", "attempt_number,0", "attempt_number,-1")
    fun `attempts reject negative counters and nonpositive numbers`(field: String, value: String) {
        val attempt = fixtures.attempt(fixtures.document(at), fixtures.run(at), 1, at)
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.sql("UPDATE document_processing_attempts SET $field=$value WHERE id=:id")
                .param("id", attempt).update()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["operation_runs", "document_processing_attempts"])
    fun `ledgers reject finish before start`(table: String) {
        val run = fixtures.run(at)
        val id = if (table == "operation_runs") run else fixtures.attempt(fixtures.document(at), run, 1, at)
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.sql("UPDATE $table SET finished_at=started_at - INTERVAL '1 second' WHERE id=:id")
                .param("id", id).update()
        }
    }

    @Test
    fun `attempt number is unique within a document`() {
        val document = fixtures.document(at)
        fixtures.attempt(document, fixtures.run(at), 1, at)
        assertFailsWith<DataIntegrityViolationException> {
            fixtures.attempt(document, fixtures.run(at), 1, at)
        }
    }

    @Test
    fun `issue rejects phase outside the operational phases`() {
        val run = fixtures.run(at)
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.sql("""
                INSERT INTO operation_run_issues(id,operation_run_id,phase,error_code,error_message,created_at)
                VALUES(:id,:run,'FINISHED','ERROR','Failed',:at::timestamptz)
            """).param("id", UUID.randomUUID()).param("run", run).param("at", at.toString()).update()
        }
    }

    @Test
    fun `creates indexes for bounded operation and document history feeds`() {
        val names = jdbc.sql("SELECT indexname FROM pg_indexes WHERE schemaname='public'")
            .query(String::class.java).list().toSet()
        assertTrue(names.containsAll(setOf(
            "ix_operation_runs_started_id", "ix_operation_runs_kind_started",
            "ix_processing_attempts_document_started", "ix_processing_attempts_run_started",
            "ix_operation_issues_run_created", "ix_documents_discovered_id", "ix_documents_provider_discovered",
            "ix_documents_first_ingestion", "ix_events_source_document_discovered", "ix_model_runs_created_id",
            "ix_model_runs_document_created", "ix_model_runs_attempt_created", "ix_ingestion_runs_started_id",
            "ix_ingestion_runs_operation",
        )))
    }

    @Test
    fun `attempt status rejects unsupported values`() {
        val document = fixtures.document(at)
        val run = fixtures.run(at)
        assertFailsWith<DataIntegrityViolationException> { fixtures.attempt(document, run, 1, at, "INVALID") }
    }
}
