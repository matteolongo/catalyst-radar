package com.catalystradar.application.company

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.company.normalizeTicker
import com.catalystradar.persistence.company.CompanyStore
import org.springframework.stereotype.Service

/**
 * Company lookup over the supported universe. Input tickers are
 * normalized; unknown tickers yield null instead of an exception so
 * callers (ingestion filters, API) decide how to handle gaps.
 */
@Service
class CompanyService(private val companies: CompanyStore) {

    fun findByTicker(rawTicker: String): Company? =
        companies.findByTicker(normalizeTicker(rawTicker))
}
