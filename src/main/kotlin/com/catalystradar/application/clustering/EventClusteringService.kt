package com.catalystradar.application.clustering

import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.event.EventClusterStore
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.ports.EmbeddingProvider
import com.catalystradar.ports.EmbeddingRequest
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Decides how persisted events map onto canonical clusters so repeated
 * reporting of one real-world fact contributes a single score downstream.
 * A candidate joins a cluster on same company, same type, a bounded
 * time window, and embedding similarity at or above threshold;
 * otherwise a new canonical cluster opens with the candidate's vector.
 *
 * This stage only decides. Embedding calls and cluster reads happen here,
 * outside the document's write transaction; the pipeline applies the
 * returned [EventClusterPlan] while persisting that document.
 */
@Service
class EventClusteringService(
    private val embeddings: EmbeddingProvider,
    private val clusters: EventClusterStore,
    private val events: EventStore,
    private val companies: CompanyStore,
    private val properties: DedupProperties,
) {

    suspend fun prepareClustering(
        sourceDocumentId: UUID,
        eventFingerprint: String,
        event: CatalystEvent,
        processingAttemptId: UUID? = null,
    ): EventClusterPlan {
        require(!properties.window.isNegative && !properties.window.isZero) {
            "deduplication window must be positive"
        }
        require(properties.maxCandidates > 0) { "deduplication candidate limit must be positive" }
        require(properties.similarityThreshold in -1.0..1.0) {
            "deduplication similarity threshold must be within -1..1"
        }
        // A fact this document already stored keeps its cluster, so a
        // reprocess needs no further embedding call.
        events.findFingerprinted(sourceDocumentId, eventFingerprint)?.clusterId?.let {
            return EventClusterPlan.JoinCluster(it)
        }
        val at = requireNotNull(event.eventTimestamp) { "event has no clustering timestamp" }
        val ticker = requireNotNull(companies.findById(event.companyId)?.ticker) {
            "unknown company: ${event.companyId}"
        }
        val candidate = embeddings.embed(EmbeddingRequest(candidateText(ticker, event), sourceDocumentId, processingAttemptId))
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
        return match?.let { EventClusterPlan.JoinCluster(it) }
            ?: EventClusterPlan.OpenCluster(candidate)
    }
}
