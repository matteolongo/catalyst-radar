package com.catalystradar.application.clustering

import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.event.EventClusterStore
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.domain.event.EventCluster
import com.catalystradar.ports.EmbeddingProvider
import com.pgvector.PGvector
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

    suspend fun clusterEvent(eventId: UUID): UUID {
        val event = events.findById(eventId)
            ?: throw IllegalArgumentException("unknown event: $eventId")
        event.clusterId?.let { return it }
        val at = requireNotNull(event.eventTimestamp) { "event has no timestamp: $eventId" }
        val ticker = requireNotNull(companies.findById(event.companyId)?.ticker) {
            "unknown company: ${event.companyId}"
        }
        val candidate = embeddings.embed(candidateText(ticker, event))
        val since = at.minus(properties.window)
        val match = clusters.findRecent(event.companyId, event.type, since, properties.maxCandidates)
            .firstNotNullOfOrNull { cluster ->
                val vector = clusters.findEmbedding(cluster.id) ?: return@firstNotNullOfOrNull null
                if (cosineSimilarity(candidate.values, vector) >= properties.similarityThreshold) {
                    cluster.id
                } else {
                    null
                }
            }
        if (match != null) {
            events.assignCluster(eventId, match)
            return match
        }
        val created = clusters.save(
            EventCluster(companyId = event.companyId, eventType = event.type, firstSeenAt = at),
            embedding = PGvector(candidate.values.toFloatArray()),
        )
        events.assignCluster(eventId, created.id)
        return created.id
    }
}
