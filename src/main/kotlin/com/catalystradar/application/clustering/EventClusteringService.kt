package com.catalystradar.application.clustering

import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.event.EventClusterStore
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.domain.event.EventCluster
import com.catalystradar.ports.EmbeddingProvider
import com.pgvector.PGvector
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Assigns persisted events to canonical clusters so repeated reporting
 * of one real-world fact contributes a single score downstream.
 * A candidate joins a cluster on same company, same type, a bounded
 * time window, and embedding similarity at or above threshold;
 * otherwise a new canonical cluster opens with the candidate's vector.
 */
@Service
class EventClusteringService(
    private val embeddings: EmbeddingProvider,
    private val clusters: EventClusterStore,
    private val events: EventStore,
    private val companies: CompanyStore,
    private val properties: DedupProperties,
) {

    private val log = LoggerFactory.getLogger(EventClusteringService::class.java)

    suspend fun clusterEvent(eventId: UUID): UUID {
        require(!properties.window.isNegative && !properties.window.isZero) {
            "deduplication window must be positive"
        }
        require(properties.maxCandidates > 0) { "deduplication candidate limit must be positive" }
        require(properties.similarityThreshold in -1.0..1.0) {
            "deduplication similarity threshold must be within -1..1"
        }
        val event = events.findById(eventId)
            ?: throw IllegalArgumentException("unknown event: $eventId")
        event.clusterId?.let { return it }
        val at = requireNotNull(event.eventTimestamp) { "event has no timestamp: $eventId" }
        val ticker = requireNotNull(companies.findById(event.companyId)?.ticker) {
            "unknown company: ${event.companyId}"
        }
        val candidate = embeddings.embed(candidateText(ticker, event))
        val since = at.minus(properties.window)
        // Event timestamps are the clustering clock. A late-arriving document
        // must not join a cluster first observed after this candidate occurred.
        val match = clusters.findWithinWindow(event.companyId, event.type, since, at, properties.maxCandidates)
            .firstNotNullOfOrNull { cluster ->
                val stored = clusters.findEmbedding(cluster.id) ?: return@firstNotNullOfOrNull null
                if (stored.model != candidate.model) return@firstNotNullOfOrNull null
                if (cosineSimilarity(candidate.values, stored.values) >= properties.similarityThreshold) {
                    cluster.id
                } else {
                    null
                }
            }
        if (match != null) {
            events.assignCluster(eventId, match)
            log.info("event {} joined cluster {}", eventId, match)
            return match
        }
        val created = clusters.save(
            EventCluster(companyId = event.companyId, eventType = event.type, firstSeenAt = at),
            embedding = PGvector(candidate.values.toFloatArray()),
            embeddingModel = candidate.model,
        )
        events.assignCluster(eventId, created.id)
        log.info("event {} opened cluster {}", eventId, created.id)
        return created.id
    }
}
