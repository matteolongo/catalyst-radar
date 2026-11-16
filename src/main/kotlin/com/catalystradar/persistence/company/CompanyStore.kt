package com.catalystradar.persistence.company

import com.catalystradar.domain.company.Company
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class CompanyStore(
    private val repository: CompanyRepository,
    private val template: JdbcAggregateTemplate,
    private val jdbc: JdbcClient,
) {

    fun save(company: Company): Company =
        template.insert(company.toRow()).toDomain()

    fun findById(id: UUID): Company? =
        repository.findById(id).map { it.toDomain() }.orElse(null)

    fun findByTicker(ticker: String): Company? =
        repository.findByTicker(ticker)?.toDomain()

    fun findAllActive(): List<Company> =
        repository.findByActiveTrue().map { it.toDomain() }

    fun countActive(): Int =
        jdbc.sql("SELECT COUNT(*) FROM companies WHERE active").query { rs, _ -> rs.getInt(1) }.single()

    /** Marks a company as promoted into the universe at [at] (ADR 0002). */
    fun markPromoted(companyId: UUID, at: Instant) {
        jdbc.sql("UPDATE companies SET origin = 'PROMOTED', tracked_since = :at WHERE id = :id")
            .param("at", Timestamp.from(at))
            .param("id", companyId)
            .update()
    }

    /** When a promoted company started being tracked; null for seeded companies. */
    fun findTrackedSince(companyId: UUID): Instant? =
        jdbc.sql("SELECT tracked_since FROM companies WHERE id = :id")
            .param("id", companyId)
            .query { rs, _ -> rs.getTimestamp("tracked_since")?.toInstant() }
            .list()
            .firstOrNull()
}
