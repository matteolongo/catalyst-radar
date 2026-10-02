package com.catalystradar.persistence.document

import com.catalystradar.persistence.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentProcessingSchemaTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `migration provides durable processing provenance and event evidence`() {
        assertEquals(
            "source_document_companies",
            jdbc.queryForObject("SELECT to_regclass('source_document_companies')::text", String::class.java),
        )
        assertEquals(
            "document_processing",
            jdbc.queryForObject("SELECT to_regclass('document_processing')::text", String::class.java),
        )

        val eventColumns = jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'events'",
            String::class.java,
        )

        assertTrue("evidence" in eventColumns)
        assertTrue("event_fingerprint" in eventColumns)
    }
}
