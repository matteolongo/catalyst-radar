package com.catalystradar.application.operations

import java.time.Duration
import java.time.Instant

class OperationsStatusPolicy(private val properties: OperationsProperties) {
    init { properties.validate() }

    fun freshness(enabled: Boolean, interval: Duration, lastSuccessAt: Instant?, now: Instant): Freshness {
        require(!interval.isNegative && !interval.isZero) { "interval must be positive" }
        val staleAfter = interval.multipliedBy(properties.freshnessMultiplier)
        val age = lastSuccessAt?.let { Duration.between(it, now).coerceAtLeast(Duration.ZERO) }
        val status = when {
            !enabled -> FreshnessStatus.OFF
            lastSuccessAt == null -> FreshnessStatus.NEVER
            age!! > staleAfter -> FreshnessStatus.OVERDUE
            else -> FreshnessStatus.CURRENT
        }
        return Freshness(status, lastSuccessAt, age?.seconds, interval.seconds, staleAfter.seconds)
    }

    fun dependency(configured: Boolean, observation: ProviderObservation, now: Instant): DependencyObservation {
        val observedSince = now.minus(properties.dependencyWindow)
        val recent = observation.lastObservedAt?.let { it >= observedSince && it < now } == true
        val status = when {
            !configured -> DependencyStatus.NOT_CONFIGURED
            !recent -> DependencyStatus.UNOBSERVED
            observation.failedObservations > 0 || observation.latestSucceeded == false -> DependencyStatus.DEGRADED
            else -> DependencyStatus.OK
        }
        return DependencyObservation(
            observation.provider, configured, status, observedSince, observation.lastObservedAt,
            observation.lastSuccessAt, observation.failedObservations, observation.lastErrorCode,
        )
    }

    fun signals(
        inputs: OverviewInputs, ingestion: Freshness, snapshots: Freshness,
        dependencies: List<DependencyObservation>, now: Instant,
    ): List<OperationalSignal> = buildList {
        if (inputs.queue.terminal > 0) add(OperationalSignal("TERMINAL_DOCUMENTS", SignalSeverity.ERROR, inputs.queue.terminal))
        val authProviders = dependencies.filter { it.lastErrorCode == "AUTHENTICATION_FAILED" && it.lastObservedAt?.let { at -> at >= now.minus(properties.dependencyWindow) && at < now } == true }
        if (authProviders.isNotEmpty()) add(OperationalSignal("AUTHENTICATION_FAILURE", SignalSeverity.ERROR, authProviders.size.toLong(), provider = authProviders.minBy { providerOrder(it.provider) }.provider))
        if (ingestion.status in setOf(FreshnessStatus.OVERDUE, FreshnessStatus.NEVER)) add(OperationalSignal("INGESTION_OVERDUE", SignalSeverity.WARNING))
        if (snapshots.status in setOf(FreshnessStatus.OVERDUE, FreshnessStatus.NEVER)) add(OperationalSignal("SNAPSHOTS_OVERDUE", SignalSeverity.WARNING))
        val attempt = inputs.oldestActiveAttempt
        if (attempt != null && Duration.between(attempt.startedAt, now) > properties.processingStallAfter) {
            add(OperationalSignal("PROCESSING_STALLED", SignalSeverity.WARNING, documentId = attempt.documentId, runId = attempt.runId))
        }
        if (inputs.queue.due > 0) add(OperationalSignal("DUE_DOCUMENTS", SignalSeverity.INFO, inputs.queue.due, documentId = inputs.queue.oldestDueDocumentId))
    }

    private fun providerOrder(provider: String): Int = when (provider) { "polygon" -> 0; "finnhub" -> 1; "openai" -> 2; else -> 3 }
}
