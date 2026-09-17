package com.catalystradar.domain.company

import java.util.UUID

data class Company(
    val id: UUID = UUID.randomUUID(),
    val ticker: String,
    val name: String,
    val exchange: String? = null,
    val sector: String? = null,
    val industry: String? = null,
    val country: String? = null,
    val active: Boolean = true,
) {
    init {
        require(ticker.isNotBlank()) { "ticker must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
    }
}
