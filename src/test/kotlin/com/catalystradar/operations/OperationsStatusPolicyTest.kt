package com.catalystradar.operations

import com.catalystradar.application.operations.*
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class OperationsStatusPolicyTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val policy = OperationsStatusPolicy(OperationsProperties())

    @Test
    fun `freshness distinguishes disabled never exact current and overdue`() {
        val interval = Duration.ofMinutes(30)
        assertEquals(FreshnessStatus.OFF, policy.freshness(false, interval, null, now).status)
        assertEquals(FreshnessStatus.NEVER, policy.freshness(true, interval, null, now).status)
        assertEquals(FreshnessStatus.CURRENT, policy.freshness(true, interval, now.minusSeconds(3600), now).status)
        assertEquals(FreshnessStatus.OVERDUE, policy.freshness(true, interval, now.minusSeconds(3600).minusNanos(1_000), now).status)
    }

    @Test
    fun `unconfigured and unused dependencies remain distinct`() {
        val unknown = ProviderObservation("finnhub", null, null, null, 0, null)
        assertEquals(DependencyStatus.NOT_CONFIGURED, policy.dependency(false, unknown, now).status)
        assertEquals(DependencyStatus.UNOBSERVED, policy.dependency(true, unknown, now).status)
    }

    @Test
    fun `signals use fixed order and skipped is never terminal`() {
        val inputs = OverviewInputs(
            activeCompanies = 1,
            queue = QueueSummary(0, 0, 0, 2, 0, 0, 0, 3, now.minusSeconds(5), UUID.randomUUID()),
            activity = ActivitySummary(0, 0, 0, 0, 0, null, null, false),
            models = ModelUsageSummary(
                calls = 0, successfulCalls = 0, failedCalls = 0, inputTokens = 0,
                inputTokensKnownCalls = 0, outputTokens = 0, outputTokensExpectedCalls = 0,
                outputTokensKnownCalls = 0, estimatedCostUsd = java.math.BigDecimal.ZERO,
                costKnownCalls = 0, latencyKnownCalls = 0, p50LatencyMs = null, p95LatencyMs = null,
            ),
            lastIngestionSuccessAt = null, lastSnapshotSuccessAt = null,
            dependencyObservations = emptyList(), oldestActiveAttempt = ActiveAttemptObservation(UUID.randomUUID(), UUID.randomUUID(), now.minus(Duration.ofMinutes(16))),
            activeRuns = emptyList(), historyStartedAt = null,
        )
        val dependencies = listOf(
            DependencyObservation("openai", true, DependencyStatus.DEGRADED, now.minus(Duration.ofHours(1)), now.minusSeconds(1), null, 1, "AUTHENTICATION_FAILED"),
            DependencyObservation("polygon", true, DependencyStatus.DEGRADED, now.minus(Duration.ofHours(1)), now.minusSeconds(2), null, 1, "AUTHENTICATION_FAILED"),
        )
        val output = policy.signals(
            inputs, policy.freshness(true, Duration.ofMinutes(1), null, now),
            policy.freshness(true, Duration.ofMinutes(1), null, now), dependencies, now,
        )
        assertEquals(listOf("TERMINAL_DOCUMENTS", "AUTHENTICATION_FAILURE", "INGESTION_OVERDUE", "SNAPSHOTS_OVERDUE", "PROCESSING_STALLED", "DUE_DOCUMENTS"), output.map { it.code })
        assertEquals(SignalSeverity.ERROR, output.first().severity)
        assertEquals("polygon", output[1].provider)
        assertEquals(3, output.last().count)
    }
}
