package com.catalystradar.api.internal

import com.catalystradar.common.InvalidInputException
import com.catalystradar.common.requireValidInput
import com.catalystradar.api.dto.CatalystResponse
import com.catalystradar.api.dto.CreateApiClientRequest
import com.catalystradar.api.dto.CreatedApiClientResponse
import com.catalystradar.api.dto.InternalReplayRequest
import com.catalystradar.api.dto.PipelineResultResponse
import com.catalystradar.api.dto.ReplayResponse
import com.catalystradar.api.dto.TickerRunRequest
import com.catalystradar.api.dto.toResponse
import com.catalystradar.application.catalyst.CatalystService
import com.catalystradar.application.catalyst.CatalystViewService
import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.company.CompanyService
import com.catalystradar.application.ingestion.IngestionProperties
import com.catalystradar.application.pipeline.PipelineResult
import com.catalystradar.application.pipeline.PipelineService
import com.catalystradar.application.replay.ReplayRequest
import com.catalystradar.application.replay.ReplayService
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.domain.company.normalizeTicker
import com.catalystradar.security.ApiKeyService
import kotlinx.coroutines.runBlocking
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Protected operations. Every route here requires the admin key (see
 * the auth filter); these never participate in public API versioning.
 */
@RestController
@RequestMapping("/internal/ingestion")
class InternalPipelineController(
    private val pipeline: PipelineService,
    private val ingestion: IngestionProperties,
) {

    /**
     * Synchronous by default. With `Prefer: respond-async` (RFC 7240) the run
     * starts in the background and `202` returns its run id immediately;
     * progress is read from the run itself.
     */
    @PostMapping("/runs")
    fun runIngestion(
        @RequestHeader("Prefer", required = false) prefer: String?,
    ): ResponseEntity<PipelineResultResponse> =
        respond(prefer, sync = { pipeline.runCycle() }, async = { pipeline.start() })

    /**
     * Manual per-symbol fetch of one tracked company's news over a bounded
     * window, followed by the same extraction, clustering and scoring as a
     * cycle. `404` when the ticker is not tracked: promote it first.
     */
    @PostMapping("/ticker-runs")
    fun runTickerIngestion(
        @RequestBody request: TickerRunRequest,
        @RequestHeader("Prefer", required = false) prefer: String?,
    ): ResponseEntity<PipelineResultResponse> {
        val ticker = normalizeTicker(request.ticker)
        val lookback = request.lookback?.let(::parseLookback) ?: ingestion.tickerFetchDefaultLookback
        val max = ingestion.tickerFetchMaxLookback
        requireValidInput(!lookback.isNegative && !lookback.isZero && lookback <= max) {
            "lookback must be positive and at most $max"
        }
        val now = Instant.now()
        val from = now.minus(lookback)
        return respond(
            prefer,
            sync = { pipeline.runTickerCycle(ticker, from, now) },
            async = { pipeline.startTickerCycle(ticker, from, now) },
        )
    }

    private fun parseLookback(raw: String): Duration =
        try {
            Duration.parse(raw.trim())
        } catch (e: DateTimeParseException) {
            throw InvalidInputException("lookback must be an ISO-8601 duration such as P7D")
        }

    private fun respond(
        prefer: String?,
        sync: suspend () -> PipelineResult,
        async: () -> PipelineResult,
    ): ResponseEntity<PipelineResultResponse> {
        val respondAsync = prefer.orEmpty().split(',').any { it.trim().equals("respond-async", ignoreCase = true) }
        if (!respondAsync) return ResponseEntity.ok(runBlocking { sync().toResponse() })
        val started = async()
        return if (started.alreadyRunning) {
            ResponseEntity.ok(started.toResponse())
        } else {
            ResponseEntity.accepted().header("Preference-Applied", "respond-async").body(started.toResponse())
        }
    }
}

@RestController
@RequestMapping("/internal/replays")
class InternalReplayController(
    private val replay: ReplayService,
    private val extraction: EventExtractionProvider,
) {

    @PostMapping
    fun replay(@RequestBody request: InternalReplayRequest): ReplayResponse {
        requireValidInput(request.ticker.isNotBlank()) { "ticker must not be blank" }
        requireValidInput(!request.cutoff.isAfter(Instant.now())) { "cutoff must not be in the future" }
        return runBlocking {
            replay.replay(
                ReplayRequest(ticker = request.ticker.trim(), cutoff = request.cutoff),
                extraction,
            ).toResponse()
        }
    }
}

@RestController
@RequestMapping("/internal/companies/{ticker}")
class InternalCompanyController(
    private val companies: CompanyService,
    private val catalyst: CatalystService,
    private val views: CatalystViewService,
) {

    @PostMapping("/recalculate")
    fun recalculate(@PathVariable ticker: String): CatalystResponse {
        val company = companies.findByTicker(ticker) ?: throw CompanyNotFoundException(ticker)
        catalyst.recalculate(company.id)
        return views.view(company.ticker).toResponse()
    }
}

@RestController
@RequestMapping("/internal/api-clients")
class InternalApiClientController(private val keys: ApiKeyService) {

    @PostMapping
    fun create(@RequestBody request: CreateApiClientRequest): ResponseEntity<CreatedApiClientResponse> {
        requireValidInput(request.name.isNotBlank()) { "name must not be blank" }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(keys.create(request.name.trim()).toResponse())
    }
}
