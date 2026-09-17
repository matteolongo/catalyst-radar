package com.catalystradar.persistence.event

import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventFamily
import com.catalystradar.domain.event.EventType
import java.time.Instant

data class EventSearch(
    val ticker: String? = null,
    val family: EventFamily? = null,
    val type: EventType? = null,
    val direction: Direction? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val limit: Int = 20,
    val cursor: String? = null,
)

data class EventSearchPage(
    val events: List<EventWithSource>,
    val nextCursor: String?,
)
