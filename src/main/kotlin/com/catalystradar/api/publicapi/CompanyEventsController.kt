package com.catalystradar.api.publicapi

import com.catalystradar.api.dto.EventsFeedResponse
import com.catalystradar.api.dto.toResponse
import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.company.CompanyService
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.persistence.event.EventWithSource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Company-scoped canonical event feed. Newest first with cursor
 * pagination; every page is bounded. Raw article bodies never leave
 * the service — only the source document reference.
 */
@RestController
@RequestMapping("/v1/companies/{ticker}/events")
class CompanyEventsController(
    private val companies: CompanyService,
    private val events: EventStore,
) {

    @GetMapping
    fun list(
        @PathVariable ticker: String,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): EventsFeedResponse {
        require(limit in 1..100) { "limit must be within 1..100" }
        val company = companies.findByTicker(ticker) ?: throw CompanyNotFoundException(ticker)
        val ordered = events.findDetailedByCompanyId(company.id)
            .sortedWith(compareByDescending<EventWithSource> { it.event.discoveredAt }.thenBy { it.event.id })
        val afterCursor = if (cursor == null) {
            ordered
        } else {
            val id = try {
                UUID.fromString(cursor)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("invalid cursor: $cursor")
            }
            ordered.dropWhile { it.event.id != id }.drop(1)
        }
        val page = afterCursor.take(limit)
        val next = if (afterCursor.size > limit) page.last().event.id.toString() else null
        return EventsFeedResponse(events = page.map { it.toResponse() }, nextCursor = next)
    }
}
