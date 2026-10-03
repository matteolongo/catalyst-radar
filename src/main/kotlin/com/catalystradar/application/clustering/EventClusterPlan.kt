package com.catalystradar.application.clustering

import com.catalystradar.ports.Embedding
import java.util.UUID

/**
 * Where one event belongs in a canonical cluster, decided before the
 * document is persisted. The decision needs an embedding provider call,
 * so it is made ahead of the write transaction and applied inside it.
 */
sealed interface EventClusterPlan {

    /** A canonical cluster already covers this fact. */
    data class JoinCluster(val clusterId: UUID) : EventClusterPlan

    /** Nothing matched, so the document's transaction opens a cluster. */
    data class OpenCluster(val embedding: Embedding) : EventClusterPlan
}
