package com.catalystradar.application.catalyst

import com.catalystradar.application.scoring.ScoreCalculator
import com.catalystradar.common.Versions
import com.catalystradar.domain.catalyst.CatalystScore
import com.catalystradar.domain.catalyst.CatalystSnapshot
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.catalyst.ScoreVelocity
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.catalyst.CatalystSnapshotStore
import com.catalystradar.persistence.operations.CompanyValuationStore
import org.slf4j.LoggerFactory
import com.catalystradar.persistence.event.EventStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Initial score bands. Provisional calibration defaults, not truths:
 * BUILDING/CATALYZED separation comes from benchmark data in CR-18.
 */
data class StateBand(val state: CatalystState, val minScore: Double, val maxScore: Double)

private val stateBands = listOf(
    StateBand(CatalystState.NORMAL, 0.0, 25.0),
    StateBand(CatalystState.WATCH, 25.0, 45.0),
    StateBand(CatalystState.BUILDING, 45.0, 65.0),
    StateBand(CatalystState.CATALYZED, 65.0, 80.0),
    StateBand(CatalystState.HIGH, 80.0, 100.0),
)

fun stateBandForScore(score: Double): StateBand =
    stateBands.firstOrNull { score < it.maxScore } ?: stateBands.last()

fun stateForScore(score: Double): CatalystState = stateBandForScore(score).state

/**
 * Scoring/state application service: the only writer of catalyst
 * snapshots and transitions. Controllers, providers, and model output
 * never touch score or state directly.
 *
 * Recalculation folds one canonical event per cluster (earliest wins
 * ties deterministically), scores them, derives state, measures
 * velocity against pre-existing snapshots, persists the snapshot, and
 * records a transition when the state moved.
 */
@Service
class CatalystService(
    private val events: EventStore,
    private val snapshots: CatalystSnapshotStore,
    private val selector: CanonicalEventSelector,
    private val metrics: CatalystMetrics,
    private val valuations: CompanyValuationStore,
) {

    private val log = LoggerFactory.getLogger(CatalystService::class.java)
    private val calculator = ScoreCalculator()

    @Transactional
    fun recalculate(companyId: UUID, asOf: Instant = Instant.now(), operationRunId: UUID? = null): CatalystSnapshot {
        snapshots.lockCompany(companyId)
        val canonical = selector.select(events.findByCompanyId(companyId))
        val calculated = calculator.calculate(canonical, asOf)
        val state = stateForScore(calculated.score.value)
        val history = snapshots.history(companyId)
        val velocity = ScoreVelocity(
            velocity1d = scoreChangeSince(history, asOf.minusSeconds(86_400), calculated.score.value),
            velocity3d = scoreChangeSince(history, asOf.minusSeconds(3L * 86_400), calculated.score.value),
            velocity7d = scoreChangeSince(history, asOf.minusSeconds(7L * 86_400), calculated.score.value),
        )
        val previous = snapshots.latestAtOrBefore(companyId, asOf)
        val previousState = previous?.state
        val snapshot = snapshots.save(
            CatalystSnapshot(
                companyId = companyId,
                score = calculated.score,
                state = state,
                velocity = velocity,
                asOf = asOf,
                taxonomyVersion = Versions.TAXONOMY_V1,
            ),
        )
        var transitionId: UUID? = null
        if (previousState != null && previousState != state) {
            transitionId = snapshots.recordTransition(companyId, previousState, state, calculated.score, asOf)
            metrics.stateTransition(previousState.name, state.name)
            log.info("company {} transitioned {} -> {} at score {}", companyId, previousState, state, calculated.score.value)
        } else {
            log.info("company {} recalculated score={} state={}", companyId, calculated.score.value, state)
        }
        operationRunId?.let { valuations.save(it, snapshot, previous, calculated, transitionId, canonical.associate { event -> event.id to event.clusterId }) }
        return snapshot
    }
}

/**
 * Score change since a cutoff against stored history: current minus the
 * latest snapshot at or before the cutoff, or zero when the window has
 * no history. Shared by live recalculation and historical replay.
 */
fun scoreChangeSince(
    history: List<CatalystSnapshot>,
    cutoff: Instant,
    current: Double,
): Double {
    val baseline = history.filter { !it.asOf.isAfter(cutoff) }.maxByOrNull { it.asOf }
        ?: return 0.0
    return current - baseline.score.value
}
