package com.catalystradar.application.company

import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.company.CompanyStore
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

data class UniverseLoadResult(val loaded: Int, val skipped: Int)

/**
 * Loads the version-controlled initial US equity universe
 * (S&P 500 + Nasdaq-100, deduplicated) into the company store.
 * Insert-if-absent: safe to run repeatedly; later reference-data syncs
 * (CR-06) expand and refresh beyond this seed.
 */
@Service
class UsUniverseLoader(
    private val companies: CompanyStore,
    private val objectMapper: ObjectMapper,
    private val resources: ResourceLoader,
) {

    fun load(): UniverseLoadResult {
        val entries = resources.getResource("classpath:universe/us-equities-v1.json").inputStream.use {
            objectMapper.readValue(it, object : TypeReference<List<UniverseEntry>>() {})
        }

        var loaded = 0
        var skipped = 0
        entries.forEach { entry ->
            if (companies.findByTicker(entry.ticker) == null) {
                companies.save(entry.toCompany())
                loaded++
            } else {
                skipped++
            }
        }
        return UniverseLoadResult(loaded = loaded, skipped = skipped)
    }

    private data class UniverseEntry(
        val ticker: String,
        val name: String,
        val exchange: String?,
        val sector: String?,
        val industry: String?,
        val country: String?,
    ) {
        fun toCompany() = Company(
            ticker = ticker,
            name = name,
            exchange = exchange,
            sector = sector,
            industry = industry,
            country = country,
        )
    }
}
