package com.catalystradar.application.evaluation

import com.catalystradar.application.replay.ReplayRequest
import com.catalystradar.application.replay.ReplayService
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.ports.MarketDataProvider
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CancellationException

enum class PriceDataStatus {
    AVAILABLE,
    INCOMPLETE,
    UNAVAILABLE,
}

data class CaseEvaluation(
    val caseId: String?,
    val ticker: String,
    val detected: Boolean,
    val leadTimeDays: Int?,
    val statesByLookback: Map<Int, CatalystState>,
    val returns: Map<Int, Double?>,
    val mfe: Double?,
    val mae: Double?,
    val priced: Boolean,
    val priceDataStatus: PriceDataStatus,
)

data class BenchmarkReport(
    val precision: Double?,
    val recall: Double,
    val falsePositiveRate: Double,
    val medianLeadTimeDays: Double?,
    val coverage: Double,
    val avgPositiveReturns: Map<Int, Double?>,
    val avgControlReturns: Map<Int, Double?>,
    val positives: List<CaseEvaluation>,
    val controls: List<CaseEvaluation>,
)

/**
 * Benchmark harness: replays catalyst state at pre-move lookbacks for
 * positive cases and matched controls, then scores detection against
 * forward market moves. The central hypothesis under test: companies
 * entering BUILDING or better show a materially higher probability of
 * large forward moves than matched controls.
 */
@Service
class BenchmarkRunner(
    private val replay: ReplayService,
    private val marketData: MarketDataProvider,
) {

    suspend fun run(
        definition: BenchmarkDefinition,
        extraction: EventExtractionProvider,
    ): BenchmarkReport {
        val positives = definition.cases.map { case ->
            evaluate(case.ticker, case.id, case.t0, definition, extraction)
        }
        val controls = definition.controls.flatMap { (caseId, tickers) ->
            val t0 = definition.cases.first { it.id == caseId }.t0
            tickers.map { evaluate(it, caseId, t0, definition, extraction) }
        }
        return summarize(positives, controls, definition.horizons)
    }

    private suspend fun evaluate(
        ticker: String,
        caseId: String?,
        t0: LocalDate,
        definition: BenchmarkDefinition,
        extraction: EventExtractionProvider,
    ): CaseEvaluation {
        val states = definition.lookbacks.associateWith { lookback ->
            val cutoff = t0.atStartOfDay(ZoneOffset.UTC).minusDays(lookback.toLong()).toInstant()
            replay.replay(ReplayRequest(ticker, cutoff), extraction).state
        }
        val detectedLookbacks = states.filterValues { it >= CatalystState.BUILDING }.keys
        val fetchedBars = try {
            marketData.dailyBars(ticker, t0.minusDays(15), t0.plusDays(30))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        val bars = fetchedBars.orEmpty()
        val returns = definition.horizons.associateWith { horizon -> forwardReturn(bars, t0, horizon) }
        val excursionsPair = excursions(bars, t0, definition.horizons.maxOrNull() ?: 10)
        val priceDataStatus = when {
            fetchedBars == null -> PriceDataStatus.UNAVAILABLE
            returns.values.all { it != null } -> PriceDataStatus.AVAILABLE
            else -> PriceDataStatus.INCOMPLETE
        }
        return CaseEvaluation(
            caseId = caseId,
            ticker = ticker,
            detected = detectedLookbacks.isNotEmpty(),
            leadTimeDays = detectedLookbacks.maxOrNull(),
            statesByLookback = states,
            returns = returns,
            mfe = excursionsPair.first,
            mae = excursionsPair.second,
            priced = priceDataStatus == PriceDataStatus.AVAILABLE,
            priceDataStatus = priceDataStatus,
        )
    }

    private fun summarize(
        positives: List<CaseEvaluation>,
        controls: List<CaseEvaluation>,
        horizons: List<Int>,
    ): BenchmarkReport {
        val truePositives = positives.count { it.detected }
        val falsePositives = controls.count { it.detected }
        val flagged = truePositives + falsePositives
        val leads = positives.mapNotNull { it.leadTimeDays }.sorted()
        return BenchmarkReport(
            precision = if (flagged == 0) null else truePositives.toDouble() / flagged,
            recall = if (positives.isEmpty()) 0.0 else truePositives.toDouble() / positives.size,
            falsePositiveRate = if (controls.isEmpty()) 0.0 else falsePositives.toDouble() / controls.size,
            medianLeadTimeDays = leads.medianOrNull(),
            coverage = if (positives.isEmpty()) 0.0 else positives.count { it.priced }.toDouble() / positives.size,
            avgPositiveReturns = horizons.associateWith { h -> positives.mapNotNull { it.returns[h] }.averageOrNull() },
            avgControlReturns = horizons.associateWith { h -> controls.mapNotNull { it.returns[h] }.averageOrNull() },
            positives = positives,
            controls = controls,
        )
    }

    private fun List<Int>.medianOrNull(): Double? {
        if (isEmpty()) return null
        return if (size % 2 == 1) {
            this[size / 2].toDouble()
        } else {
            (this[size / 2 - 1] + this[size / 2]) / 2.0
        }
    }

    private fun List<Double>.averageOrNull(): Double? =
        if (isEmpty()) null else average()
}
