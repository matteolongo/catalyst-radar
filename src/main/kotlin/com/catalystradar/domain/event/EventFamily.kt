package com.catalystradar.domain.event

/**
 * Closed v1 classification of catalyst event families.
 *
 * Only events with a plausible path to changing forward expectations or
 * repricing belong here. Social sentiment, generic buzz, rumors, options
 * flow, and technical breakouts are explicitly excluded from v1.
 */
enum class EventFamily {
    EARNINGS,
    GUIDANCE,
    COMMERCIAL,
    PRODUCT_TECH,
    CORPORATE_ACTION,
    CAPITAL_ALLOCATION,
    ANALYST,
    REGULATORY,
    LEGAL,
    MANAGEMENT_OWNERSHIP,
    INDUSTRY_EXTERNAL,
}
