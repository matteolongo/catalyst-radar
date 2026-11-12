package com.catalystradar.api.internal

import com.catalystradar.api.dto.CatalystResponse
import com.catalystradar.api.dto.CreateApiClientRequest
import com.catalystradar.api.dto.CreatedApiClientResponse
import com.catalystradar.api.dto.InternalReplayRequest
import com.catalystradar.api.dto.PipelineResultResponse
import com.catalystradar.api.dto.ReplayResponse
import com.catalystradar.api.dto.toResponse
import com.catalystradar.application.catalyst.CatalystService
import com.catalystradar.application.catalyst.CatalystViewService
import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.company.CompanyService
import com.catalystradar.application.pipeline.PipelineService
import com.catalystradar.application.replay.ReplayRequest
import com.catalystradar.application.replay.ReplayService
import com.catalystradar.ports.EventExtractionProvider
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
import java.time.Instant

/**
 * Protected operations. Every route here requires the admin key (see
 * the auth filter); these never participate in public API versioning.
 */
@RestController
@RequestMapping("/internal/ingestion")
class InternalPipelineController(private val pipeline: PipelineService) {

    /**
     * Synchronous by default. With `Prefer: respond-async` (RFC 7240) the run
     * starts in the background and `202` returns its run id immediately;
     * progress is read from the run itself.
     */
    @PostMapping("/runs")
    fun runIngestion(
        @RequestHeader("Prefer", required = false) prefer: String?,
    ): ResponseEntity<PipelineResultResponse> {
        val async = prefer.orEmpty().split(',').any { it.trim().equals("respond-async", ignoreCase = true) }
        if (!async) return ResponseEntity.ok(runBlocking { pipeline.runCycle().toResponse() })
        val started = pipeline.start()
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
        require(request.ticker.isNotBlank()) { "ticker must not be blank" }
        require(!request.cutoff.isAfter(Instant.now())) { "cutoff must not be in the future" }
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
        require(request.name.isNotBlank()) { "name must not be blank" }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(keys.create(request.name.trim()).toResponse())
    }
}
