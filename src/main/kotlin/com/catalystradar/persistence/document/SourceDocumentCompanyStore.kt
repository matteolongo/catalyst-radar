package com.catalystradar.persistence.document

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class SourceDocumentCompanyStore(
    private val jdbc: JdbcTemplate,
) {

    fun link(sourceDocumentId: UUID, companyId: UUID) {
        jdbc.update(
            """
            INSERT INTO source_document_companies (source_document_id, company_id)
            VALUES (?, ?)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            sourceDocumentId,
            companyId,
        )
    }

    fun link(sourceDocumentId: UUID, companyIds: Collection<UUID>) {
        companyIds.distinct().forEach { link(sourceDocumentId, it) }
    }

    fun findCompanyIds(sourceDocumentId: UUID): List<UUID> =
        jdbc.query(
            """
            SELECT company_id
            FROM source_document_companies
            WHERE source_document_id = ?
            ORDER BY company_id
            """.trimIndent(),
            { resultSet, _ -> resultSet.getObject("company_id", UUID::class.java) },
            sourceDocumentId,
        )
}
