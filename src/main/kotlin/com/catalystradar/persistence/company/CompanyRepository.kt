package com.catalystradar.persistence.company

import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface CompanyRepository : ListCrudRepository<CompanyRow, UUID> {
    fun findByTicker(ticker: String): CompanyRow?
}
