package com.catalystradar.api.publicapi

import com.catalystradar.api.dto.CatalystResponse
import com.catalystradar.api.dto.TimelineResponse
import com.catalystradar.api.dto.toResponse
import com.catalystradar.application.catalyst.CatalystViewService
import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.company.CompanyService
import com.catalystradar.persistence.catalyst.CatalystSnapshotStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Company catalyst reads: current intelligence and its history.
 * Both are pure reads over persisted snapshots; nothing here
 * recomputes or writes.
 */
@RestController
@RequestMapping("/v1/companies/{ticker}")
class CatalystController(
    private val views: CatalystViewService,
    private val companies: CompanyService,
    private val snapshots: CatalystSnapshotStore,
) {

    @GetMapping("/catalyst")
    fun catalyst(@PathVariable ticker: String): CatalystResponse =
        views.view(ticker).toResponse()

    @GetMapping("/timeline")
    fun timeline(
        @PathVariable ticker: String,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): TimelineResponse {
        require(limit in 1..200) { "limit must be within 1..200" }
        val company = companies.findByTicker(ticker) ?: throw CompanyNotFoundException(ticker)
        val inWindow = { at: Instant ->
            (from == null || !at.isBefore(from)) && (to == null || !at.isAfter(to))
        }
        val history = snapshots.history(company.id)
            .filter { inWindow(it.asOf) }
            .take(limit)
        val transitions = snapshots.findTransitions(company.id)
            .filter { inWindow(it.at) }
            .take(limit)
        return TimelineResponse(
            ticker = company.ticker,
            snapshots = history.map { it.toResponse() },
            transitions = transitions.map { it.toResponse() },
        )
    }
}
