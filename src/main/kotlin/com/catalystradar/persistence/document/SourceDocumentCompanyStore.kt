package com.catalystradar.persistence.document

import com.catalystradar.domain.event.SourceDocument
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
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

    /**
     * Replay inputs are selected through the durable company-document link,
     * not through live extracted events. Discovery time is the availability
     * boundary, so a later-published or backdated document cannot leak in.
     */
    fun findDocumentsAvailableByCutoff(companyId: UUID, cutoff: Instant): List<SourceDocument> =
        jdbc.query(
            """
            SELECT sd.id, sd.provider, sd.provider_document_id, sd.canonical_url,
                   sd.title, sd.body, sd.published_at, sd.discovered_at, sd.content_hash
            FROM source_documents sd
            JOIN source_document_companies sdc ON sdc.source_document_id = sd.id
            WHERE sdc.company_id = ?
              AND sd.discovered_at <= ?
            ORDER BY sd.discovered_at ASC, sd.id ASC
            """.trimIndent(),
            { resultSet, _ ->
                SourceDocument(
                    id = resultSet.getObject("id", UUID::class.java),
                    provider = resultSet.getString("provider"),
                    providerDocumentId = resultSet.getString("provider_document_id"),
                    canonicalUrl = resultSet.getString("canonical_url"),
                    title = resultSet.getString("title"),
                    body = resultSet.getString("body"),
                    publishedAt = resultSet.getTimestamp("published_at")?.toInstant(),
                    discoveredAt = resultSet.getTimestamp("discovered_at").toInstant(),
                    contentHash = resultSet.getString("content_hash"),
                )
            },
            companyId,
            java.sql.Timestamp.from(cutoff),
        )
}
