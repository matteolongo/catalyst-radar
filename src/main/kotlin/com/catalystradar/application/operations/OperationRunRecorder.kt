package com.catalystradar.application.operations

import com.catalystradar.persistence.operations.OperationRunStore
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
@DependsOnDatabaseInitialization
class OperationRunRecorder(
    private val store: OperationRunStore,
    @Qualifier("operationsClock") private val clock: Clock,
) {
    private val current = ConcurrentHashMap<OperationKind, UUID>()

    // This single-instance application has no live run owners during bean initialization.
    @PostConstruct
    fun recoverUnfinishedRuns() = store.recoverUnfinished(clock.instant())

    // Callers acquire their existing execution guard before opening a cycle.
    fun begin(kind: OperationKind, trigger: OperationTrigger, asOf: Instant): UUID {
        val id = UUID.randomUUID()
        try {
            store.begin(id, kind, trigger, asOf, clock.instant())
            current[kind] = id
            return id
        } catch (error: Throwable) {
            current.remove(kind)
            throw error
        }
    }

    fun phase(id: UUID, phase: OperationPhase) = store.phase(id, phase, clock.instant())

    fun progress(id: UUID, counts: OperationCounts) = store.progress(id, counts, clock.instant())

    fun finish(id: UUID, status: OperationStatus, counts: OperationCounts?, errorCode: String?, captureComplete: Boolean) {
        require(status != OperationStatus.RUNNING) { "finish requires a terminal status" }
        require(!captureComplete || counts != null) { "complete capture requires final counts" }
        try {
            store.finish(id, status, counts, errorCode, captureComplete, clock.instant())
        } finally {
            current.entries.removeIf { it.value == id }
        }
    }

    fun issue(
        id: UUID,
        phase: OperationPhase,
        code: String,
        documentId: UUID? = null,
        companyId: UUID? = null,
        provider: String? = null,
    ) = store.issue(id, phase, code, documentId, companyId, clock.instant(), provider)

    fun activeIds(): Set<UUID> = current.values.toSet()

    fun isActive(id: UUID): Boolean = current.containsValue(id)
}
