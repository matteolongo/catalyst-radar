package com.catalystradar.api.dto

import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.persistence.discovery.DiscoveryPage
import com.catalystradar.persistence.discovery.DiscoveryRow
import java.time.Instant

data class DiscoveryEntryResponse(
    val ticker: String,
    val name: String,
    val sector: String?,
    val score: Double,
    val state: String,
    val velocity7d: Double,
    val events7d: Int,
    val scoreVersion: String,
    val taxonomyVersion: String,
    val asOf: Instant,
)

data class DiscoveryResponse(
    val asOf: Instant,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val results: List<DiscoveryEntryResponse>,
)

fun DiscoveryRow.toResponse() = DiscoveryEntryResponse(
    ticker = ticker,
    name = name,
    sector = sector,
    score = score,
    state = state.name,
    velocity7d = velocity7d,
    events7d = events7d,
    scoreVersion = scoreVersion,
    taxonomyVersion = taxonomyVersion,
    asOf = asOf,
)

fun DiscoveryPage.toResponse(limit: Int, offset: Int, asOf: Instant) = DiscoveryResponse(
    asOf = asOf,
    total = total,
    limit = limit,
    offset = offset,
    results = results.map { it.toResponse() },
)
