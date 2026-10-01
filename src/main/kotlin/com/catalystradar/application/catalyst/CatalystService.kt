package com.catalystradar.application.catalyst

import com.catalystradar.application.scoring.ScoreCalculator
import com.catalystradar.common.Versions
import com.catalystradar.domain.catalyst.CatalystScore
import com.catalystradar.domain.catalyst.CatalystSnapshot
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.catalyst.ScoreVelocity
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.catalyst.CatalystSnapshotStore
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
fun stateForScore(score: Double): CatalystState = when {
    score < 25.0 -> CatalystState.NORMAL
    score < 45.0 -> CatalystState.WATCH
    score < 65.0 -> CatalystState.BUILDING
    score < 80.0 -> CatalystState.CATALYZED
    else -> CatalystState.HIGH
}

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
) {

    private val log = LoggerFactory.getLogger(CatalystService::class.java)
    private val calculator = ScoreCalculator()

    @Transactional
    fun recalculate(companyId: UUID, asOf: Instant = Instant.now()): CatalystSnapshot {
        val canonical = selector.select(events.findByCompanyId(companyId))
        val calculated = calculator.calculate(canonical, asOf)
        val state = stateForScore(calculated.score.value)
        val history = snapshots.history(companyId)
        val velocity = ScoreVelocity(
            velocity1d = scoreChangeSince(history, asOf.minusSeconds(86_400), calculated.score.value),
            velocity3d = scoreChangeSince(history, asOf.minusSeconds(3L * 86_400), calculated.score.value),
            velocity7d = scoreChangeSince(history, asOf.minusSeconds(7L * 86_400), calculated.score.value),
        )
        val previousState = history.firstOrNull()?.state
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
        if (previousState != null && previousState != state) {
            snapshots.recordTransition(companyId, previousState, state, calculated.score, asOf)
            metrics.stateTransition(previousState.name, state.name)
            log.info("company {} transitioned {} -> {} at score {}", companyId, previousState, state, calculated.score.value)
        } else {
            log.info("company {} recalculated score={} state={}", companyId, calculated.score.value, state)
        }
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
