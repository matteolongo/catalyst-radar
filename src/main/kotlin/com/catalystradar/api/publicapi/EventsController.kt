package com.catalystradar.api.publicapi

import com.catalystradar.api.dto.EventsFeedResponse
import com.catalystradar.api.dto.toResponse
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventFamily
import com.catalystradar.domain.event.EventType
import com.catalystradar.persistence.event.EventSearch
import com.catalystradar.persistence.event.EventStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Filterable event feed across companies. Every page is bounded and
 * keyed by an opaque cursor; unknown tickers simply match nothing.
 */
@RestController
@RequestMapping("/v1/events")
class EventsController(private val events: EventStore) {

    @GetMapping
    fun search(
        @RequestParam(required = false) ticker: String?,
        @RequestParam(required = false) family: EventFamily?,
        @RequestParam(required = false) type: EventType?,
        @RequestParam(required = false) direction: Direction?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): EventsFeedResponse {
        val page = events.searchEvents(
            EventSearch(
                ticker = ticker,
                family = family,
                type = type,
                direction = direction,
                from = from,
                to = to,
                limit = limit,
                cursor = cursor,
            ),
        )
        return EventsFeedResponse(
            events = page.events.map { it.toResponse() },
            nextCursor = page.nextCursor,
        )
    }
}
