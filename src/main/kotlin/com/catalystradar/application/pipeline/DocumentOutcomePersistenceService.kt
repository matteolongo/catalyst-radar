package com.catalystradar.application.pipeline

import com.catalystradar.application.clustering.EventClusterPlan
import com.catalystradar.application.ingestion.DocumentProcessingStatus
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.EventCluster
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.document.DocumentProcessingStore
import com.catalystradar.persistence.event.EventClusterStore
import com.catalystradar.persistence.event.EventStore
import com.pgvector.PGvector
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/** What one document contributed and how it ended. */
data class DocumentOutcome(
    val status: DocumentProcessingStatus,
    val eventsInserted: Int = 0,
    val eventsReused: Int = 0,
    val affectedCompanies: Set<UUID> = emptySet(),
    val error: String? = null,
) {
    /** Events this document resolved to, new rows and reused rows alike. */
    val eventsStored: Int get() = eventsInserted + eventsReused
}

/**
 * Commits one document's outcome: its events, their cluster assignments,
 * and its completed or skipped status land together or not at all.
 *
 * The transaction is deliberately narrow and synchronous. Extraction,
 * validation, normalization, and every embedding call happen before
 * [persist] is entered, so a failure here rolls the document back whole
 * and leaves the pipeline free to record a retryable or terminal status
 * on its own.
 */
@Service
class DocumentOutcomePersistenceService(
    private val events: EventStore,
    private val clusters: EventClusterStore,
    private val processing: DocumentProcessingStore,
    private val metrics: CatalystMetrics,
    transactionManager: PlatformTransactionManager,
) {

    private val transaction = TransactionTemplate(transactionManager)

    fun persist(
        plan: DocumentPersistencePlan,
        finalStatus: DocumentProcessingStatus,
        now: Instant = Instant.now(),
    ): DocumentOutcome {
        require(finalStatus == DocumentProcessingStatus.COMPLETED || finalStatus == DocumentProcessingStatus.SKIPPED) {
            "a document can only be finalized as COMPLETED or SKIPPED, not $finalStatus"
        }
        val committed = requireNotNull(transaction.execute { storeOutcome(plan, finalStatus, now) }) {
            "document persistence produced no outcome"
        }
        // Counted only once the commit succeeded: a rolled back document
        // left no row, so it must not appear as an extraction either.
        committed.insertedEvents.forEach { event ->
            metrics.eventExtracted(event.family.name, event.type.name, event.direction.name)
        }
        return committed.outcome
    }

    private fun storeOutcome(
        plan: DocumentPersistencePlan,
        finalStatus: DocumentProcessingStatus,
        now: Instant,
    ): CommittedDocument {
        var eventsInserted = 0
        var eventsReused = 0
        val insertedEvents = mutableListOf<CatalystEvent>()
        val affectedCompanies = linkedSetOf<UUID>()
        for (planned in plan.events) {
            val stored = events.saveIfAbsent(planned.event, plan.sourceDocumentId, planned.fingerprint)
            if (stored.inserted) {
                eventsInserted++
                insertedEvents += stored.event
            } else {
                eventsReused++
            }
            if (stored.event.clusterId == null) {
                events.assignCluster(stored.event.id, clusterIdFor(planned))
            }
            affectedCompanies += stored.event.companyId
        }
        when (finalStatus) {
            DocumentProcessingStatus.COMPLETED -> processing.markCompleted(plan.sourceDocumentId, now)
            else -> processing.markSkipped(plan.sourceDocumentId, now)
        }
        return CommittedDocument(
            outcome = DocumentOutcome(
                status = finalStatus,
                eventsInserted = eventsInserted,
                eventsReused = eventsReused,
                affectedCompanies = affectedCompanies,
            ),
            insertedEvents = insertedEvents,
        )
    }

    private data class CommittedDocument(
        val outcome: DocumentOutcome,
        val insertedEvents: List<CatalystEvent>,
    )

    /**
     * A concurrent run may already have clustered the stored row, so a
     * prepared plan never overrides an assignment the database holds.
     */
    private fun clusterIdFor(planned: PlannedEvent): UUID = when (val cluster = planned.cluster) {
        is EventClusterPlan.JoinCluster -> cluster.clusterId
        is EventClusterPlan.OpenCluster -> clusters.save(
            EventCluster(
                companyId = planned.event.companyId,
                eventType = planned.event.type,
                firstSeenAt = requireNotNull(planned.event.eventTimestamp) {
                    "event has no clustering timestamp"
                },
            ),
            embedding = PGvector(cluster.embedding.values.toFloatArray()),
            embeddingModel = cluster.embedding.model,
        ).id
    }
}
