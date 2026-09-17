package com.catalystradar.application.catalyst

import com.catalystradar.domain.event.CatalystEvent
import org.springframework.stereotype.Component

/**
 * Canonical event selection shared by scoring and reads: earliest event
 * per cluster wins ties deterministically; unclustered events always
 * stand alone and never collapse into one contribution.
 */
@Component
class CanonicalEventSelector {

    fun select(events: List<CatalystEvent>): List<CatalystEvent> {
        val (clustered, unclustered) = events.partition { it.clusterId != null }
        val canonical = clustered.groupBy { it.clusterId }.values.map { group ->
            group.minWith(
                compareBy<CatalystEvent> { it.eventTimestamp ?: it.discoveredAt }
                    .thenBy { it.discoveredAt }
                    .thenBy { it.id },
            )
        }
        return canonical + unclustered
    }
}
