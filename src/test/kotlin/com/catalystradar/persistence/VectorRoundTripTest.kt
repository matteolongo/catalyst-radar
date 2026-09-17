package com.catalystradar.persistence

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.EventCluster
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.SourceDocumentRepository
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.event.EventClusterRepository
import com.catalystradar.persistence.event.EventClusterStore
import com.pgvector.PGvector
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals

/**
 * Proves pgvector integration: 1536-dim embeddings round-trip through
 * rows and the cosine-distance operator answers inside the database.
 */
@Transactional
class VectorRoundTripTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Autowired
    private lateinit var documentRows: SourceDocumentRepository

    @Autowired
    private lateinit var clusters: EventClusterStore

    @Autowired
    private lateinit var clusterRows: EventClusterRepository

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Test
    fun `document embedding round trips`() {
        val embedding = PGvector(FloatArray(1536) { index -> if (index == 0) 1.0f else 0.0f })
        val saved = documents.save(newDocument(), embedding = embedding)

        val row = documentRows.findById(saved.id).orElseThrow()

        assertEquals(embedding.toString(), row.embedding.toString())
    }

    @Test
    fun `cosine distance is computed in the database`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell Technologies"))
        val first = PGvector(FloatArray(1536) { index -> if (index == 0) 1.0f else 0.0f })
        val orthogonal = PGvector(FloatArray(1536) { index -> if (index == 1) 1.0f else 0.0f })
        val saved = clusters.save(
            EventCluster(
                companyId = company.id,
                eventType = EventType.GUIDANCE_RAISE,
                firstSeenAt = Instant.parse("2026-09-16T10:00:00Z"),
            ),
            embedding = first,
        )

        val literal = orthogonal.toString()
        val distance = jdbc.sql(
            "SELECT embedding <=> '$literal'::vector FROM event_clusters WHERE id = :id",
        ).param("id", saved.id).query(Double::class.java).single()

        assertEquals(1.0, distance, 1e-6)
    }

    private fun newDocument() = SourceDocument(
        provider = "polygon",
        title = "Dell raises guidance",
        body = "Dell raised its full-year outlook.",
        discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
    )
}
