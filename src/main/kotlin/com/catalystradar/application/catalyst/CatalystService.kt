package com.catalystradar.application.catalyst

import com.catalystradar.application.scoring.ScoreCalculator
import com.catalystradar.application.scoring.ScoreConfig
import com.catalystradar.application.scoring.ScoreV1
import com.catalystradar.application.scoring.knownAt
import com.catalystradar.common.Versions
import com.catalystradar.domain.catalyst.CatalystScore
import com.catalystradar.domain.catalyst.CatalystSnapshot
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.catalyst.ScoreVelocity
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.operations.CompanyValuationStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

typealias StateBand = com.catalystradar.application.scoring.StateBand

fun stateBandForScore(score: Double, config: ScoreConfig = ScoreV1.config): StateBand =
    config.stateBandForScore(score)

fun stateForScore(score: Double, config: ScoreConfig = ScoreV1.config): CatalystState =
    stateBandForScore(score, config).state

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
    private val events: CompanyEventSource,
    private val snapshots: SnapshotLedger,
    private val selector: CanonicalEventSelector,
    private val metrics: CatalystMetrics,
    private val valuations: CompanyValuationStore,
) {

    private val log = LoggerFactory.getLogger(CatalystService::class.java)
    private val config = ScoreV1.config
    private val calculator = ScoreCalculator(config)
    private val horizon = Duration.ofSeconds((config.decayHorizonDays() * 86_400).toLong())

    @Transactional
    fun recalculate(companyId: UUID, asOf: Instant = Instant.now(), operationRunId: UUID? = null): CatalystSnapshot {
        snapshots.lockCompany(companyId)
        val known = knownAt(events.findScoringInputs(companyId, asOf.minus(horizon), asOf), asOf)
        val canonical = selector.select(known)
        val calculated = calculator.calculate(canonical, asOf)
        val state = stateForScore(calculated.score.value, config)
        val velocity = ScoreVelocity(
            velocity1d = scoreChangeSince(snapshots, companyId, asOf.minusSeconds(86_400), calculated.score.value),
            velocity3d = scoreChangeSince(snapshots, companyId, asOf.minusSeconds(3L * 86_400), calculated.score.value),
            velocity7d = scoreChangeSince(snapshots, companyId, asOf.minusSeconds(7L * 86_400), calculated.score.value),
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
                downsideScore = calculator.scoreForRaw(calculated.negativeRaw),
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
    snapshots: SnapshotLedger,
    companyId: UUID,
    cutoff: Instant,
    current: Double,
): Double {
    val baseline = snapshots.latestAtOrBefore(companyId, cutoff) ?: return 0.0
    return current - baseline.score.value
}
