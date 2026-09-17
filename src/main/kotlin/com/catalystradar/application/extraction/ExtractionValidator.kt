package com.catalystradar.application.extraction

import com.catalystradar.ports.ExtractedEvent
import com.catalystradar.ports.ExtractionResult
import org.springframework.stereotype.Component

/**
 * Application-side semantic validation for extraction candidates.
 * The adapter already drops malformed shapes; this enforces the value
 * rules even a schema-valid response can break. Invalid candidates are
 * dropped, never repaired: the model does not get a vote on validity.
 */
@Component
class ExtractionValidator {

    fun validate(result: ExtractionResult): ExtractionResult {
        if (!result.documentRelevant) return result.copy(events = emptyList())
        return result.copy(events = result.events.filter { it.isValid() })
    }

    private fun ExtractedEvent.isValid(): Boolean {
        if (ticker.isBlank()) return false
        if (confidence !in 0.0..1.0) return false
        if (surprise != null && surprise !in 0.0..1.0) return false
        if (materiality != null && materiality !in 0.0..1.0) return false
        if (magnitude != null && (!magnitude.isFinite() || magnitude < 0.0)) return false
        if (evidence.isEmpty()) return false
        return true
    }
}
