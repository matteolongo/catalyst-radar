package com.catalystradar.persistence.company

import com.catalystradar.domain.company.Company
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * companies row. Ids are assigned in application code; [CompanyStore] uses
 * insert semantics explicitly so a set id never turns a create into a
 * silent no-op update.
 */
@Table("companies")
data class CompanyRow(
    @Id val id: UUID,
    val ticker: String,
    val name: String,
    val exchange: String?,
    val sector: String?,
    val industry: String?,
    val country: String?,
    val active: Boolean,
)

fun CompanyRow.toDomain() = Company(
    id = id,
    ticker = ticker,
    name = name,
    exchange = exchange,
    sector = sector,
    industry = industry,
    country = country,
    active = active,
)

fun Company.toRow() = CompanyRow(
    id = id,
    ticker = ticker,
    name = name,
    exchange = exchange,
    sector = sector,
    industry = industry,
    country = country,
    active = active,
)
