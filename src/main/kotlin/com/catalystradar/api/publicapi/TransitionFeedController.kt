package com.catalystradar.api.publicapi

import com.catalystradar.api.dto.TransitionFeedResponse
import com.catalystradar.api.dto.toResponse
import com.catalystradar.common.requireValidInput
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.catalyst.TransitionDirection
import com.catalystradar.persistence.discovery.TransitionFeedQuery
import com.catalystradar.persistence.discovery.TransitionFeedStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

/**
 * "What changed" feed: companies that crossed a catalyst band, newest first.
 * The window defaults to the last 24 hours and never reaches back further
 * than [MAX_WINDOW], so the read stays bounded.
 */
@RestController
@RequestMapping("/v1/discovery/transitions")
class TransitionFeedController(private val transitions: TransitionFeedStore) {

    @GetMapping
    fun search(
        @RequestParam(required = false) since: Instant?,
        @RequestParam(required = false) direction: TransitionDirection?,
        @RequestParam(required = false) state: List<CatalystState>?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): TransitionFeedResponse {
        requireValidInput(limit in 1..100) { "limit must be within 1..100" }
        val asOf = Instant.now()
        val from = since ?: asOf.minus(DEFAULT_WINDOW)
        requireValidInput(!from.isAfter(asOf)) { "since must not be in the future" }
        requireValidInput(!from.isBefore(asOf.minus(MAX_WINDOW))) { "since must be within the last 30 days" }
        val page = transitions.find(
            TransitionFeedQuery(
                since = from,
                asOf = asOf,
                direction = direction,
                toStates = state?.toSet(),
                limit = limit,
                cursor = cursor,
            ),
        )
        return page.toResponse(from, asOf)
    }

    companion object {
        val DEFAULT_WINDOW: Duration = Duration.ofHours(24)
        val MAX_WINDOW: Duration = Duration.ofDays(30)
    }
}
