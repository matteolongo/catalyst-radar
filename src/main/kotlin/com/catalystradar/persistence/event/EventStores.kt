package com.catalystradar.persistence.event

import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.EventCluster
import com.catalystradar.domain.event.EventType
import com.pgvector.PGvector
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class EventStore(
    private val repository: EventRepository,
    private val template: JdbcAggregateTemplate,
    private val jdbc: NamedParameterJdbcTemplate,
) {

    fun save(
        event: CatalystEvent,
        sourceDocumentId: UUID? = null,
    ): CatalystEvent =
        template.insert(event.toRow(sourceDocumentId)).toDomain()

    fun saveIfAbsent(
        event: CatalystEvent,
        sourceDocumentId: UUID,
        eventFingerprint: String,
    ): StoredEvent {
        require(eventFingerprint.length == 64) { "eventFingerprint must be a SHA-256 hex value" }
        findFingerprinted(sourceDocumentId, eventFingerprint)?.let {
            return StoredEvent(it, inserted = false)
        }
        return try {
            StoredEvent(
                event = template.insert(event.toRow(sourceDocumentId, eventFingerprint)).toDomain(),
                inserted = true,
            )
        } catch (e: DataIntegrityViolationException) {
            val existing = findFingerprinted(sourceDocumentId, eventFingerprint)
            if (existing != null) StoredEvent(existing, inserted = false) else throw e
        }
    }

    /**
     * The event this exact fact is already stored under for one document,
     * or null when the document has not produced it yet. Callers use this
     * before deciding how much work a reprocess still needs.
     */
    fun findFingerprinted(sourceDocumentId: UUID, eventFingerprint: String): CatalystEvent? =
        repository.findBySourceDocumentIdAndEventFingerprint(sourceDocumentId, eventFingerprint)
            ?.toDomain()

    fun findById(id: UUID): CatalystEvent? =
        repository.findById(id).map { it.toDomain() }.orElse(null)

    fun findByCompanyId(companyId: UUID): List<CatalystEvent> =
        repository.findByCompanyId(companyId).map { it.toDomain() }

    fun findByClusterId(clusterId: UUID): List<CatalystEvent> =
        repository.findByClusterId(clusterId).map { it.toDomain() }

    /**
     * Bounded filtered feed with keyset pagination. The cursor encodes
     * the last seen (discoveredAt, id); one extra row is read to know
     * whether a next page exists.
     */
    fun searchEvents(query: EventSearch): EventSearchPage {
        require(query.limit in 1..100) { "limit must be within 1..100" }
        val decoded = query.cursor?.let { decodeCursor(it) }
        val rows = repository.search(
            ticker = query.ticker,
            family = query.family?.name,
            type = query.type?.name,
            direction = query.direction?.name,
            sourceDocumentId = query.sourceDocumentId,
            from = query.from,
            to = query.to,
            cursorTs = decoded?.first,
            cursorId = decoded?.second,
            limit = query.limit + 1,
        )
        val page = rows.take(query.limit)
        val next = if (rows.size > query.limit) {
            val last = page.last()
            "${last.discoveredAt}|${last.id}"
        } else {
            null
        }
        return EventSearchPage(
            events = detailed(page),
            nextCursor = next,
        )
    }

    private fun decodeCursor(cursor: String): Pair<Instant, UUID> {
        val parts = cursor.split("|")
        if (parts.size != 2) throw IllegalArgumentException("invalid cursor: $cursor")
        try {
            return Instant.parse(parts[0]) to UUID.fromString(parts[1])
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("invalid cursor: $cursor")
        }
    }

    fun findDetailedByCompanyId(companyId: UUID): List<EventWithSource> =
        detailed(repository.findByCompanyId(companyId))

    fun findDetailedAvailableByCompanyId(companyId: UUID, snapshotCreatedAt: Instant): List<EventWithSource> =
        detailed(repository.findAvailableByCompanyId(companyId, snapshotCreatedAt))

    private fun detailed(rows: List<EventRow>): List<EventWithSource> {
        if (rows.isEmpty()) return emptyList()
        val metadata = jdbc.query(
            """
            SELECT e.id, c.ticker, c.name AS company_name,
                   d.id AS document_id, d.title AS source_title, d.provider AS source_provider,
                   d.published_at AS source_published_at, d.canonical_url AS source_canonical_url
            FROM events e
            JOIN companies c ON c.id = e.company_id
            LEFT JOIN source_documents d ON d.id = e.source_document_id
            WHERE e.id IN (:ids)
            """,
            mapOf("ids" to rows.map { it.id }),
        ) { rs, _ ->
            val documentId = rs.getObject("document_id", UUID::class.java)
            val source = documentId?.let {
                SafeSourceMetadata(
                    sourceDocumentId = it,
                    title = rs.getString("source_title"),
                    provider = rs.getString("source_provider"),
                    publishedAt = rs.getTimestamp("source_published_at")?.toInstant(),
                    canonicalUrl = rs.getString("source_canonical_url"),
                )
            }
            rs.getObject("id", UUID::class.java) to Triple(rs.getString("ticker"), rs.getString("company_name"), source)
        }.toMap()
        return rows.map { row ->
            val (ticker, companyName, source) = requireNotNull(metadata[row.id])
            EventWithSource(row.toDomain(), row.sourceDocumentId, ticker, companyName, source)
        }
    }

    fun assignCluster(eventId: UUID, clusterId: UUID) {
        val row = repository.findById(eventId).orElseThrow()
        repository.save(row.copy(clusterId = clusterId))
    }
}

data class StoredEvent(
    val event: CatalystEvent,
    val inserted: Boolean,
)

@Repository
class EventClusterStore(
    private val repository: EventClusterRepository,
    private val template: JdbcAggregateTemplate,
) {

    fun save(
        cluster: EventCluster,
        embedding: PGvector? = null,
        embeddingModel: String? = null,
    ): EventCluster =
        template.insert(cluster.toRow(embedding, embeddingModel)).toDomain()

    fun findById(id: UUID): EventCluster? =
        repository.findById(id).map { it.toDomain() }.orElse(null)

    fun findWithinWindow(
        companyId: UUID,
        type: EventType,
        from: Instant,
        through: Instant,
        limit: Int,
    ): List<EventCluster> {
        require(!from.isAfter(through)) { "cluster window start must not be after its end" }
        require(limit > 0) { "cluster candidate limit must be positive" }
        return repository
            .findWithinWindow(
                companyId,
                type.name,
                from,
                through,
                limit,
            )
            .map { it.toDomain() }
    }

    fun findEmbedding(clusterId: UUID): StoredClusterEmbedding? {
        val row = repository.findById(clusterId).orElse(null) ?: return null
        val vector = row.embedding ?: return null
        return StoredClusterEmbedding(vector.toArray().toList(), row.embeddingModel)
    }
}

data class StoredClusterEmbedding(
    val values: List<Float>,
    val model: String?,
)
