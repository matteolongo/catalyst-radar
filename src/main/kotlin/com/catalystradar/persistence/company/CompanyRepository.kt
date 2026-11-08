package com.catalystradar.persistence.company

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface CompanyRepository : ListCrudRepository<CompanyRow, UUID> {
    fun findByTicker(ticker: String): CompanyRow?

    fun findByActiveTrue(): List<CompanyRow>

    @Modifying
    @Query(
        """
        UPDATE companies
        SET active = (ticker IN (:tickers)), updated_at = now()
        WHERE active IS DISTINCT FROM (ticker IN (:tickers))
        """,
    )
    fun setActiveUniverse(tickers: Set<String>)
}
