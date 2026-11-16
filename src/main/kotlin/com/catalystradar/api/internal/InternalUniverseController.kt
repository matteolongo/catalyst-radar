package com.catalystradar.api.internal

import com.catalystradar.api.dto.CandidatesResponse
import com.catalystradar.api.dto.PromotionRequest
import com.catalystradar.api.dto.PromotionResponse
import com.catalystradar.application.universe.UniversePromotionService
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import com.catalystradar.api.dto.toResponse
import com.catalystradar.common.requireValidInput
import com.catalystradar.persistence.universe.CandidateQuery
import com.catalystradar.persistence.universe.UnresolvedTickerStore
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

/** Universe expansion reads and actions (ADR 0002). Admin key required by the auth filter. */
@RestController
@RequestMapping("/internal/universe")
class InternalUniverseController(
    private val unresolved: UnresolvedTickerStore,
    private val promotions: UniversePromotionService,
) {

    /**
     * Tracks a candidate ticker. 201 when the company was added, 200 when it
     * already existed (nothing changed). Calls the reference provider once.
     */
    @PostMapping("/promotions")
    fun promote(@RequestBody request: PromotionRequest): ResponseEntity<PromotionResponse> {
        requireValidInput(request.ticker.isNotBlank()) { "ticker must not be blank" }
        val result = runBlocking { promotions.promote(request.ticker) }
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .cacheControl(CacheControl.noStore())
            .body(result.toResponse())
    }

    @GetMapping("/candidates")
    fun candidates(
        @RequestParam(required = false) since: Instant?,
        @RequestParam(defaultValue = "1") minDocuments: Int,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): ResponseEntity<CandidatesResponse> {
        requireValidInput(limit in 1..100) { "limit must be within 1..100" }
        requireValidInput(offset in 0..UnresolvedTickerStore.MAX_OFFSET) { "offset must be within 0..${UnresolvedTickerStore.MAX_OFFSET}" }
        requireValidInput(minDocuments >= 1) { "minDocuments must be at least 1" }
        val now = Instant.now()
        val from = since ?: now.minus(DEFAULT_WINDOW)
        requireValidInput(!from.isAfter(now)) { "since must not be in the future" }
        requireValidInput(!from.isBefore(now.minus(MAX_WINDOW))) { "since must be within the last 90 days" }
        val page = unresolved.candidates(CandidateQuery(since = from, minDocuments = minDocuments, limit = limit, offset = offset))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page.toResponse(from, limit, offset))
    }

    companion object {
        val DEFAULT_WINDOW: Duration = Duration.ofDays(30)
        val MAX_WINDOW: Duration = Duration.ofDays(90)
    }
}
