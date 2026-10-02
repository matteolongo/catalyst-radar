package com.catalystradar.domain.event

/** A factual excerpt or statement supporting one extracted catalyst event. */
data class EventEvidence(
    val quoteOrFact: String,
    val sourceOffsetHint: String? = null,
) {
    init {
        require(quoteOrFact.isNotBlank()) { "quoteOrFact must not be blank" }
    }
}
