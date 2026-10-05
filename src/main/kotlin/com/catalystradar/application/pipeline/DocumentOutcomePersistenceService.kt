package com.catalystradar.application.pipeline

import com.catalystradar.application.clustering.EventClusterPlan
import com.catalystradar.application.ingestion.DocumentProcessingStatus
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.EventCluster
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.document.DocumentProcessingStore
import com.catalystradar.persistence.event.EventClusterStore
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.persistence.operations.ProcessingAttemptStore
import com.catalystradar.application.operations.AttemptStatus
import com.catalystradar.persistence.operations.DocumentStepStore
import com.pgvector.PGvector
import org.springframework.stereotype.Service
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.Clock
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
    private val attempts: ProcessingAttemptStore,
    private val steps: DocumentStepStore,
    @Qualifier("operationsClock") private val clock: Clock,
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
        plan.persistenceStepId?.let { steps.requirePersistenceStep(it, plan.sourceDocumentId, plan.processingAttemptId) }
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
        plan.processingAttemptId?.let { id ->
            attempts.requireDocument(id, plan.sourceDocumentId)
            attempts.finish(id, AttemptStatus.valueOf(finalStatus.name), eventsInserted, eventsReused, null, null, clock.instant())
        }
        when (finalStatus) {
            DocumentProcessingStatus.COMPLETED -> processing.markCompleted(plan.sourceDocumentId, now)
            else -> processing.markSkipped(plan.sourceDocumentId, now)
        }
        plan.persistenceStepId?.let { steps.finish(it, outputCount = eventsInserted + eventsReused,
            eventsInserted = eventsInserted, eventsReused = eventsReused) }
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
