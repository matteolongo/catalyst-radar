package com.catalystradar.application.pipeline

import com.catalystradar.application.clustering.EventClusterPlan
import com.catalystradar.application.ingestion.DocumentProcessingStatus
import com.catalystradar.domain.event.CatalystEvent
import java.time.Instant
import java.util.UUID

/**
 * Everything one document will write, prepared before the transaction
 * opens: no provider call is left to run while the transaction is open.
 */
data class DocumentPersistencePlan(
    val sourceDocumentId: UUID,
    val events: List<PlannedEvent>,
)

/** One normalized event, its identity hash, and where it clusters. */
data class PlannedEvent(
    val event: CatalystEvent,
    val fingerprint: String,
    val cluster: EventClusterPlan,
)
