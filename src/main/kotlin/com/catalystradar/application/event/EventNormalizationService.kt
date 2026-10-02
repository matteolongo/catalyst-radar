package com.catalystradar.application.event

import com.catalystradar.application.extraction.ExtractionValidator
import com.catalystradar.common.Versions
import com.catalystradar.domain.company.Company
import com.catalystradar.domain.company.normalizeTicker
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.EventEvidence
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.ports.ExtractedEvent
import com.catalystradar.ports.ExtractionResult
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

/**
 * Turns validated extraction candidates for one stored document into
 * persisted catalyst events. It resolves tickers only against the companies
 * linked to that document, then normalizes timestamps down the event/published/discovered
 * chain, assigns source quality from the provider tier, and links each
 * event to its source document. Cluster assignment follows in CR-11.
 */
@Service
class EventNormalizationService(
    private val validator: ExtractionValidator,
    private val events: EventStore,
    private val metrics: CatalystMetrics,
) {

    fun processDocument(
        document: SourceDocument,
        result: ExtractionResult,
        allowedCompanies: Collection<Company>,
    ): List<CatalystEvent> {
        val validated = validator.validate(result)
        if (!validated.documentRelevant) return emptyList()
        val companiesByTicker = companiesByTicker(allowedCompanies)
        return validated.events.mapNotNull { candidate ->
            normalize(document, candidate, companiesByTicker)?.let {
                val saved = events.saveIfAbsent(it, document.id, eventFingerprint(document.id, it))
                if (saved.inserted) {
                    metrics.eventExtracted(it.family.name, it.type.name, it.direction.name)
                }
                saved.event
            }
        }
    }

    /**
     * Dry-run normalization without persistence: replay and evaluation
     * recompute history through this path while live storage stays
     * untouched.
     */
    fun normalize(
        document: SourceDocument,
        candidate: ExtractedEvent,
        allowedCompanies: Collection<Company>,
    ): CatalystEvent? = normalize(document, candidate, companiesByTicker(allowedCompanies))

    private fun normalize(
        document: SourceDocument,
        candidate: ExtractedEvent,
        companiesByTicker: Map<String, Company>,
    ): CatalystEvent? {
        val company = try {
            companiesByTicker[normalizeTicker(candidate.ticker)]
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        val eventTimestamp = candidate.eventTimestamp
            ?: document.publishedAt
            ?: document.discoveredAt
        if (eventTimestamp.isAfter(document.discoveredAt)) return null
        return CatalystEvent(
            companyId = company.id,
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
            eventTimestamp = eventTimestamp,
            discoveredAt = document.discoveredAt,
            taxonomyVersion = Versions.TAXONOMY_V1,
            extractorVersion = Versions.EXTRACTOR_V1,
            attributes = candidate.attributes,
            evidence = candidate.evidence.map { EventEvidence(it.quoteOrFact, it.sourceOffsetHint) },
        )
    }

    private fun companiesByTicker(companies: Collection<Company>): Map<String, Company> =
        companies.mapNotNull { company ->
            runCatching { normalizeTicker(company.ticker) }.getOrNull()?.let { it to company }
        }.toMap()

    private fun eventFingerprint(sourceDocumentId: java.util.UUID, event: CatalystEvent): String {
        val facts = event.evidence
            .map { "${it.quoteOrFact.trim().lowercase(Locale.ROOT)}\u001f${it.sourceOffsetHint.orEmpty().trim()}" }
            .sorted()
            .joinToString("\u001e")
        val attributes = event.attributes.toSortedMap().entries.joinToString("\u001e") { "${it.key}\u001f${it.value}" }
        val canonical = listOf(
            sourceDocumentId,
            event.companyId,
            event.type,
            event.direction,
            event.eventTimestamp,
            event.confidence,
            event.magnitude,
            event.surprise,
            event.materiality,
            event.expectedHorizon,
            event.directness,
            facts,
            attributes,
        ).joinToString("\u001d")
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
    }

    private fun sourceQualityFor(provider: String): SourceQuality = when (provider) {
        "polygon" -> SourceQuality.TIER1_NEWS
        "finnhub" -> SourceQuality.TIER2_NEWS
        else -> SourceQuality.OTHER
    }
}
