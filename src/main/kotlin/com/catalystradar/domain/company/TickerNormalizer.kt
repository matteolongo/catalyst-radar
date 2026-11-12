package com.catalystradar.domain.company

import com.catalystradar.common.requireValidInput

private val TICKER_PATTERN = Regex("^[A-Z0-9][A-Z0-9.\\-]{0,15}$")

/**
 * Canonical ticker form: trimmed and uppercased. Class-share suffixes
 * (BRK.B vs BRK/B provider variants) stay distinct values; provider
 * adapters reconcile variants at the boundary in CR-06/CR-07.
 */
fun normalizeTicker(raw: String): String {
    val normalized = raw.trim().uppercase()
    requireValidInput(normalized.isNotBlank()) { "ticker must not be blank" }
    requireValidInput(TICKER_PATTERN.matches(normalized)) { "invalid ticker" }
    return normalized
}
