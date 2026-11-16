package com.catalystradar.ports

/**
 * Looks up one ticker in a provider's reference data. Separate from
 * [CompanyReferenceProvider] because only providers with an exact-ticker
 * query implement it; a full-list scan per lookup would waste quota.
 */
interface CompanyLookupProvider {
    /** The ticker's reference data if it is an active US common stock, otherwise null. */
    suspend fun findUsEquity(ticker: String): CompanyReference?
}
