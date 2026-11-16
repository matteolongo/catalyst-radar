package com.catalystradar.api.dto

import com.catalystradar.persistence.discovery.TransitionFeedPage
import com.catalystradar.persistence.discovery.TransitionFeedRow
import java.time.Instant

data class TransitionFeedEntryResponse(
    val ticker: String,
    val name: String,
    val sector: String?,
    val fromState: String,
    val toState: String,
    val direction: String,
    val score: Double,
    val scoreVersion: String,
    val transitionedAt: Instant,
)

data class TransitionFeedResponse(
    val since: Instant,
    val asOf: Instant,
    val transitions: List<TransitionFeedEntryResponse>,
    val nextCursor: String?,
)

fun TransitionFeedRow.toResponse() = TransitionFeedEntryResponse(
    ticker = ticker,
    name = name,
    sector = sector,
    fromState = from.name,
    toState = to.name,
    direction = direction.name,
    score = score,
    scoreVersion = scoreVersion,
    transitionedAt = transitionedAt,
)

fun TransitionFeedPage.toResponse(since: Instant, asOf: Instant) = TransitionFeedResponse(
    since = since,
    asOf = asOf,
    transitions = transitions.map { it.toResponse() },
    nextCursor = nextCursor,
)
