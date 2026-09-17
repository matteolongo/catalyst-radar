package com.catalystradar.ports

/**
 * Provider-neutral US equity reference capability (ticker list refresh).
 * Adapters translate vendor symbols into this shape.
 */
interface CompanyReferenceProvider {
    suspend fun listUsEquities(cursor: String? = null): CompanyPage
}

data class CompanyReference(
    val ticker: String,
    val name: String,
    val exchange: String? = null,
    val sector: String? = null,
    val industry: String? = null,
    val country: String? = null,
    val active: Boolean = true,
)

data class CompanyPage(
    val values: List<CompanyReference>,
    val nextCursor: String?,
)
