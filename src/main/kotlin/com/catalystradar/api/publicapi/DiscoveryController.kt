package com.catalystradar.api.publicapi

import com.catalystradar.api.dto.DiscoveryResponse
import com.catalystradar.api.dto.toResponse
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.persistence.discovery.DiscoveryQuery
import com.catalystradar.persistence.discovery.DiscoverySort
import com.catalystradar.persistence.discovery.DiscoveryStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Primary discovery endpoint: companies whose catalyst profile is
 * strengthening, ranked by score or velocity. Bounded pages only.
 */
@RestController
@RequestMapping("/v1/discovery/catalyzed")
class DiscoveryController(private val discovery: DiscoveryStore) {

    @GetMapping
    fun search(
        @RequestParam(required = false) state: List<CatalystState>?,
        @RequestParam(required = false) minScore: Double?,
        @RequestParam(required = false) minVelocity7d: Double?,
        @RequestParam(required = false) sector: String?,
        @RequestParam(required = false) sort: DiscoverySort?,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): DiscoveryResponse {
        require(limit in 1..100) { "limit must be within 1..100" }
        require(offset >= 0) { "offset must be non-negative" }
        val page = discovery.discover(
            DiscoveryQuery(
                states = state?.toSet(),
                minScore = minScore,
                minVelocity7d = minVelocity7d,
                sector = sector,
                sort = sort ?: DiscoverySort.SCORE,
                limit = limit,
                offset = offset,
            ),
        )
        return page.toResponse(limit, offset, Instant.now())
    }
}
