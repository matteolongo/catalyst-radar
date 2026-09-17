package com.catalystradar.domain.event

/** Expected market impact direction of an event. */
enum class Direction {
    POSITIVE,
    NEGATIVE,
    NEUTRAL,
    MIXED,
}

/**
 * Whether the evidence directly describes the company (DIRECT) or only
 * implies an effect on it, for example peer read-through (INFERRED).
 * Inferred evidence receives a hard contribution cap in scoring.
 */
enum class Directness {
    DIRECT,
    INFERRED,
}

/**
 * Source tier, ordered from strongest to weakest:
 * regulatory/primary filings and wires above tier-1 financial news,
 * above tier-2 outlets, above analyst notes, above everything else.
 */
enum class SourceQuality {
    PRIMARY,
    TIER1_NEWS,
    TIER2_NEWS,
    ANALYST,
    OTHER,
}

/** Horizon over which the event is expected to matter. */
enum class EventHorizon {
    INTRADAY,
    DAYS,
    WEEKS,
    MONTHS,
    STRUCTURAL,
}
