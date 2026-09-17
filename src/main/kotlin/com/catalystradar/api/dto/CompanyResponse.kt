package com.catalystradar.api.dto

import com.catalystradar.domain.company.Company

/**
 * Public company metadata. Separate from persistence rows so internal
 * storage details never leak into the versioned API.
 */
data class CompanyResponse(
    val ticker: String,
    val name: String,
    val exchange: String?,
    val sector: String?,
    val industry: String?,
    val country: String?,
    val active: Boolean,
)

fun Company.toResponse() = CompanyResponse(
    ticker = ticker,
    name = name,
    exchange = exchange,
    sector = sector,
    industry = industry,
    country = country,
    active = active,
)
