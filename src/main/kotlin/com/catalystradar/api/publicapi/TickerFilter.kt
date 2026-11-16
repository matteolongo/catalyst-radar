package com.catalystradar.api.publicapi

import com.catalystradar.common.requireValidInput
import com.catalystradar.domain.company.normalizeTicker

/** Most tickers one discovery read may be narrowed to, e.g. a followed list. */
const val MAX_TICKER_FILTER = 50

/** Normalizes an optional repeated `ticker` parameter; null means "no ticker filter". */
fun tickerFilter(tickers: List<String>?): Set<String>? {
    if (tickers.isNullOrEmpty()) return null
    requireValidInput(tickers.size <= MAX_TICKER_FILTER) { "at most $MAX_TICKER_FILTER tickers may be requested" }
    return tickers.map(::normalizeTicker).toSet()
}
