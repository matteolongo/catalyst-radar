package com.catalystradar.application.event

import com.catalystradar.application.extraction.ExtractionValidator
import com.catalystradar.common.Versions
import com.catalystradar.domain.company.normalizeTicker
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.ports.ExtractedEvent
import com.catalystradar.ports.ExtractionResult
import org.springframework.stereotype.Service

/**
 * Turns validated extraction candidates for one stored document into
 * persisted catalyst events: resolves tickers against the supported
 * universe, normalizes timestamps down the event/published/discovered
 * chain, assigns source quality from the provider tier, and links each
 * event to its source document. Cluster assignment follows in CR-11.
 */
@Service
class EventNormalizationService(
    private val validator: ExtractionValidator,
    private val companies: CompanyStore,
    private val events: EventStore,
) {

    fun processDocument(document: SourceDocument, result: ExtractionResult): List<CatalystEvent> {
        val validated = validator.validate(result)
        if (!validated.documentRelevant) return emptyList()
        return validated.events.mapNotNull { candidate -> persistCandidate(document, candidate) }
    }

    private fun persistCandidate(document: SourceDocument, candidate: ExtractedEvent): CatalystEvent? {
        val companyId = try {
            companies.findByTicker(normalizeTicker(candidate.ticker))?.id
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        val event = CatalystEvent(
            companyId = companyId,
            type = candidate.type,
            direction = candidate.direction,
            confidence = candidate.confidence,
            magnitude = candidate.magnitude,
            surprise = candidate.surprise,
            materiality = candidate.materiality,
            sourceQuality = sourceQualityFor(document.provider),
            expectedHorizon = candidate.expectedHorizon,
            directness = candidate.directness,
            scheduled = false,
            eventTimestamp = candidate.eventTimestamp
                ?: document.publishedAt
                ?: document.discoveredAt,
            discoveredAt = document.discoveredAt,
            taxonomyVersion = Versions.TAXONOMY_V1,
            extractorVersion = Versions.EXTRACTOR_V1,
            attributes = candidate.attributes,
        )
        return events.save(event, sourceDocumentId = document.id)
    }

    private fun sourceQualityFor(provider: String): SourceQuality = when (provider) {
        "polygon" -> SourceQuality.TIER1_NEWS
        "finnhub" -> SourceQuality.TIER2_NEWS
        else -> SourceQuality.OTHER
    }
}
