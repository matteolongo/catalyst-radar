package com.catalystradar.persistence.company

import com.catalystradar.domain.company.Company
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class CompanyStore(
    private val repository: CompanyRepository,
    private val template: JdbcAggregateTemplate,
) {

    fun save(company: Company): Company =
        template.insert(company.toRow()).toDomain()

    fun findById(id: UUID): Company? =
        repository.findById(id).map { it.toDomain() }.orElse(null)

    fun findByTicker(ticker: String): Company? =
        repository.findByTicker(ticker)?.toDomain()

    fun findAllActive(): List<Company> =
        repository.findByActiveTrue().map { it.toDomain() }
}
