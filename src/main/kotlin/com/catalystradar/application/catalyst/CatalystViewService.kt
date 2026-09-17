package com.catalystradar.application.catalyst

import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.company.CompanyService
import com.catalystradar.application.scoring.ScoreCalculator
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.persistence.catalyst.CatalystSnapshotStore
import com.catalystradar.persistence.event.EventStore
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

data class EventDriver(
    val eventId: UUID,
    val type: String,
    val direction: String,
    val contribution: Double,
)

data class CatalystView(
    val ticker: String,
    val score: Double,
    val state: CatalystState,
    val velocity1d: Double,
    val velocity3d: Double,
    val velocity7d: Double,
    val positiveScore: Double,
    val negativeScore: Double,
    val directScore: Double,
    val inferredScore: Double,
    val totalEvents: Int,
    val events7d: Int,
    val topDrivers: List<EventDriver>,
    val scoreVersion: String,
    val taxonomyVersion: String,
    val asOf: Instant,
)

/**
 * Read-only catalyst view. Score, state, and velocity come from the
 * latest persisted snapshot; the breakdown recomputes deterministically
 * over canonical events without writing anything.
 */
@Service
class CatalystViewService(
    private val companies: CompanyService,
    private val events: EventStore,
    private val snapshots: CatalystSnapshotStore,
    private val selector: CanonicalEventSelector,
) {

    private val calculator = ScoreCalculator()

    fun view(ticker: String): CatalystView {
        val company = companies.findByTicker(ticker) ?: throw CompanyNotFoundException(ticker)
        val snapshot = snapshots.latestSnapshot(company.id) ?: throw CatalystNotFoundException(ticker)
        val canonical = selector.select(events.findByCompanyId(company.id))
        val calculated = calculator.calculate(canonical, snapshot.asOf)
        val byId = canonical.associateBy { it.id }
        val rawFor = { predicate: (CatalystEvent) -> Boolean ->
            calculated.contributions
                .filter { byId[it.eventId]?.let(predicate) == true }
                .sumOf { it.value }
        }
        val drivers = calculated.contributions
            .sortedByDescending { kotlin.math.abs(it.value) }
            .take(3)
            .mapNotNull { contribution ->
                byId[contribution.eventId]?.let {
                    EventDriver(contribution.eventId, it.type.name, it.direction.name, contribution.value)
                }
            }
        val weekAgo = snapshot.asOf.minusSeconds(7L * 86_400)
        return CatalystView(
            ticker = company.ticker,
            score = snapshot.score.value,
            state = snapshot.state,
            velocity1d = snapshot.velocity.velocity1d,
            velocity3d = snapshot.velocity.velocity3d,
            velocity7d = snapshot.velocity.velocity7d,
            positiveScore = calculator.scoreForRaw(rawFor { it.direction == Direction.POSITIVE }),
            negativeScore = calculator.scoreForRaw(-rawFor { it.direction == Direction.NEGATIVE }),
            directScore = calculator.scoreForRaw(rawFor { it.directness == Directness.DIRECT }),
            inferredScore = calculator.scoreForRaw(rawFor { it.directness == Directness.INFERRED }),
            totalEvents = canonical.size,
            events7d = canonical.count { (it.eventTimestamp ?: it.discoveredAt) >= weekAgo },
            topDrivers = drivers,
            scoreVersion = snapshot.score.version,
            taxonomyVersion = snapshot.taxonomyVersion,
            asOf = snapshot.asOf,
        )
    }
}
