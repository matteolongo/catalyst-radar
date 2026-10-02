package com.catalystradar.application.replay

import com.catalystradar.application.catalyst.CanonicalEventSelector
import com.catalystradar.application.catalyst.scoreChangeSince
import com.catalystradar.application.catalyst.stateForScore
import com.catalystradar.application.clustering.DedupProperties
import com.catalystradar.application.clustering.candidateText
import com.catalystradar.application.clustering.cosineSimilarity
import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.company.CompanyService
import com.catalystradar.application.event.EventNormalizationService
import com.catalystradar.application.extraction.ExtractionValidator
import com.catalystradar.application.scoring.ScoreCalculator
import com.catalystradar.common.Versions
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.EventType
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.catalyst.CatalystSnapshotStore
import com.catalystradar.persistence.document.SourceDocumentCompanyStore
import com.catalystradar.ports.Embedding
import com.catalystradar.ports.EmbeddingProvider
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.ports.ExtractionRequest
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import java.util.UUID

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
    val documentsConsidered: Int = 0,
    val documentsSkipped: Int = 0,
    val candidatesAccepted: Int = 0,
)

/**
 * Deterministic historical recomputation. Inputs are only source documents
 * durably linked to the company and discovered by the cutoff. Candidate
 * clusters are rebuilt in memory, so persisted events or clusters from the
 * future cannot alter historical scores. Replay writes no domain records.
 */
@Service
class ReplayService(
    private val companies: CompanyService,
    private val documentCompanies: SourceDocumentCompanyStore,
    private val snapshots: CatalystSnapshotStore,
    private val validator: ExtractionValidator,
    private val normalization: EventNormalizationService,
    private val selector: CanonicalEventSelector,
    private val embeddings: EmbeddingProvider,
    private val dedup: DedupProperties,
    private val metrics: CatalystMetrics,
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
        validateDedupProperties()
        val company = companies.findByTicker(request.ticker) ?: throw CompanyNotFoundException(request.ticker)
        val documents = documentCompanies.findDocumentsAvailableByCutoff(company.id, request.cutoff)
        val clusterer = InMemoryReplayClusterer(company.ticker, embeddings, dedup)
        var documentsSkipped = 0
        val accepted = mutableListOf<CatalystEvent>()
        for (document in documents) {
            val result = validator.validate(
                extraction.extract(ExtractionRequest(document, listOf(company))),
            )
            if (!result.documentRelevant) {
                documentsSkipped++
                continue
            }
            val normalized = result.events
                .mapNotNull { normalization.normalize(document, it, listOf(company)) }
                .sortedWith(replayEventOrder)
            if (normalized.isEmpty()) {
                documentsSkipped++
                continue
            }
            for (event in normalized) {
                accepted += clusterer.assign(event)
            }
        }
        val canonical = selector.select(accepted)
        val calculated = calculator.calculate(canonical, request.cutoff)
        val history = snapshots.history(company.id).filter { !it.asOf.isAfter(request.cutoff) }
        val state = stateForScore(calculated.score.value)
        metrics.replayInputs(
            documentsConsidered = documents.size,
            documentsSkipped = documentsSkipped,
            candidatesAccepted = accepted.size,
        )
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
            documentsConsidered = documents.size,
            documentsSkipped = documentsSkipped,
            candidatesAccepted = accepted.size,
        )
    }

    private fun validateDedupProperties() {
        require(!dedup.window.isNegative && !dedup.window.isZero) {
            "deduplication window must be positive"
        }
        require(dedup.maxCandidates > 0) { "deduplication candidate limit must be positive" }
        require(dedup.similarityThreshold in -1.0..1.0) {
            "deduplication similarity threshold must be within -1..1"
        }
    }

    private class InMemoryReplayClusterer(
        private val ticker: String,
        private val embeddings: EmbeddingProvider,
        private val properties: DedupProperties,
    ) {

        private val clusters = mutableListOf<ReplayCluster>()

        suspend fun assign(event: CatalystEvent): CatalystEvent {
            val at = event.eventTimestamp ?: event.discoveredAt
            val candidate = embeddings.embed(candidateText(ticker, event))
            val since = at.minus(properties.window)
            val match = clusters.asSequence()
                .filter {
                    it.type == event.type &&
                        !it.firstSeenAt.isBefore(since) &&
                        !it.firstSeenAt.isAfter(at) &&
                        it.embedding.model == candidate.model
                }
                .sortedWith(compareByDescending<ReplayCluster> { it.firstSeenAt }.thenByDescending { it.id })
                .take(properties.maxCandidates)
                .firstOrNull { cosineSimilarity(candidate.values, it.embedding.values) >= properties.similarityThreshold }
            val cluster = match ?: ReplayCluster(
                id = UUID.nameUUIDFromBytes(
                    "replay-cluster-${clusters.size}".toByteArray(StandardCharsets.UTF_8),
                ),
                type = event.type,
                firstSeenAt = at,
                embedding = candidate,
            ).also(clusters::add)
            return event.copy(clusterId = cluster.id)
        }
    }

    private data class ReplayCluster(
        val id: UUID,
        val type: EventType,
        val firstSeenAt: Instant,
        val embedding: Embedding,
    )

    private companion object {
        val replayEventOrder = compareBy<CatalystEvent> { it.eventTimestamp ?: it.discoveredAt }
            .thenBy { it.type.name }
            .thenBy { it.direction.name }
            .thenBy {
                it.evidence
                    .map { evidence -> evidence.quoteOrFact.trim().lowercase(Locale.ROOT) }
                    .sorted()
                    .joinToString("\u001f")
            }
    }
}
