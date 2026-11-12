package com.catalystradar.persistence.extraction

import com.catalystradar.ports.ExtractionResult
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Repository
class ExtractionResultStore(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {

    fun find(sourceDocumentId: UUID, extractorVersion: String, model: String): ExtractionResult? =
        jdbc.queryForList(
            "SELECT result::text FROM extraction_results WHERE source_document_id=? AND extractor_version=? AND model=?",
            String::class.java, sourceDocumentId, extractorVersion, model,
        ).firstOrNull()?.let { mapper.readValue(it, ExtractionResult::class.java) }

    fun save(sourceDocumentId: UUID, extractorVersion: String, model: String, result: ExtractionResult) {
        jdbc.update(
            """
            INSERT INTO extraction_results(source_document_id, extractor_version, model, result)
            VALUES (?, ?, ?, ?::jsonb)
            ON CONFLICT (source_document_id, extractor_version, model) DO NOTHING
            """.trimIndent(),
            sourceDocumentId, extractorVersion, model, mapper.writeValueAsString(result),
        )
    }
}
