package com.catalystradar.application.replay

import com.catalystradar.application.catalyst.scoreChangeSince
import com.catalystradar.application.catalyst.stateForScore
import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.company.CompanyService
import com.catalystradar.application.event.EventNormalizationService
import com.catalystradar.application.extraction.ExtractionValidator
import com.catalystradar.application.scoring.ScoreCalculator
import com.catalystradar.common.Versions
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.catalyst.ScoreVelocity
import com.catalystradar.persistence.catalyst.CatalystSnapshotStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.ports.ExtractionRequest
import org.springframework.stereotype.Service
import java.time.Instant

data class ReplayRequest(
    val ticker: String,
    val cutoff: Instant,
    val taxonomyVersion: String = Versions.TAXONOMY_V1,
    val extractorVersion: String = Versions.EXTRACTOR_V1,
    val scoreVersion: String = Versions.SCORE_V1,
)

data class ReplayResult(
    val ticker: String,
    val asOf: Instant,
    val score: Double,
    val state: CatalystState,
    val velocity1d: Double,
    val velocity3d: Double,
    val velocity7d: Double,
    val eventCount: Int,
    val scoreVersion: String,
    val taxonomyVersion: String,
    val extractorVersion: String,
)

/**
 * Deterministic historical recomputation. Replays stored source
 * documents through extraction, normalization, cluster inheritance,
 * and scoring — writing nothing. Only documents discovered at or
 * before the cutoff participate, and velocity baselines come from
 * pre-cutoff snapshots, so replays can never see the future.
 * Unknown artifact versions fail fast instead of silently mixing.
 */
@Service
class ReplayService(
    private val companies: CompanyService,
    private val documents: SourceDocumentStore,
    private val events: EventStore,
    private val snapshots: CatalystSnapshotStore,
    private val validator: ExtractionValidator,
    private val normalization: EventNormalizationService,
) {

    private val calculator = ScoreCalculator()

    suspend fun replay(request: ReplayRequest, extraction: EventExtractionProvider): ReplayResult {
        require(request.taxonomyVersion == Versions.TAXONOMY_V1) {
            "unsupported taxonomy version: ${request.taxonomyVersion}"
        }
        require(request.extractorVersion == Versions.EXTRACTOR_V1) {
            "unsupported extractor version: ${request.extractorVersion}"
        }
        require(request.scoreVersion == Versions.SCORE_V1) {
            "unsupported score version: ${request.scoreVersion}"
        }
        val company = companies.findByTicker(request.ticker) ?: throw CompanyNotFoundException(request.ticker)
        val live = events.findDetailedByCompanyId(company.id)
        val eligible = live
            .mapNotNull { it.sourceDocumentId }
            .toSet()
            .mapNotNull { documents.findById(it) }
            .filter { !it.discoveredAt.isAfter(request.cutoff) }
            .sortedBy { it.discoveredAt }
        val recomputed = eligible.flatMap { document ->
            val result = validator.validate(
                extraction.extract(ExtractionRequest(document, listOf(company))),
            )
            if (!result.documentRelevant) return@flatMap emptyList()
            result.events.mapNotNull { candidate ->
                val event = normalization.normalize(document, candidate, listOf(company)) ?: return@mapNotNull null
                val cluster = live.firstOrNull { it.event.type == event.type }?.event?.clusterId
                event to cluster
            }
        }
        val canonical = recomputed
            .groupBy { (event, clusterId) -> clusterId ?: event.id }
            .values
            .map { group -> group.minBy { (event, _) -> event.discoveredAt }.first }
        val calculated = calculator.calculate(canonical, request.cutoff)
        val history = snapshots.history(company.id).filter { !it.asOf.isAfter(request.cutoff) }
        val state = stateForScore(calculated.score.value)
        return ReplayResult(
            ticker = company.ticker,
            asOf = request.cutoff,
            score = calculated.score.value,
            state = state,
            velocity1d = scoreChangeSince(history, request.cutoff.minusSeconds(86_400), calculated.score.value),
            velocity3d = scoreChangeSince(history, request.cutoff.minusSeconds(3L * 86_400), calculated.score.value),
            velocity7d = scoreChangeSince(history, request.cutoff.minusSeconds(7L * 86_400), calculated.score.value),
            eventCount = canonical.size,
            scoreVersion = request.scoreVersion,
            taxonomyVersion = request.taxonomyVersion,
            extractorVersion = request.extractorVersion,
        )
    }
}
