package com.catalystradar.api.dto

import com.catalystradar.persistence.universe.CandidatePage
import com.catalystradar.persistence.universe.CandidateRow
import java.time.Instant

data class CandidateResponse(
    val ticker: String,
    /** Stored documents naming the ticker; syndicated copies each count. Not an event count. */
    val documentsStored: Int,
    /** Documents stored as unresolved, which promotion would queue for extraction. */
    val documentsNeverQueued: Int,
    val firstDiscoveredAt: Instant,
    val lastDiscoveredAt: Instant,
)

data class CandidatesResponse(
    val since: Instant,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val candidates: List<CandidateResponse>,
)

fun CandidateRow.toResponse() = CandidateResponse(
    ticker = ticker,
    documentsStored = documents,
    documentsNeverQueued = neverQueuedDocuments,
    firstDiscoveredAt = firstDiscoveredAt,
    lastDiscoveredAt = lastDiscoveredAt,
)

fun CandidatePage.toResponse(since: Instant, limit: Int, offset: Int) = CandidatesResponse(
    since = since,
    total = total,
    limit = limit,
    offset = offset,
    candidates = candidates.map { it.toResponse() },
)
