package com.catalystradar.application.event

import com.catalystradar.application.extraction.ExtractionValidator
import com.catalystradar.common.Versions
import com.catalystradar.domain.company.Company
import com.catalystradar.domain.company.normalizeTicker
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.ports.ExtractedEvent
import com.catalystradar.ports.ExtractionResult
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

/** One normalized event plus the identity hash it is stored under. */
data class NormalizedEvent(
    val event: CatalystEvent,
    val fingerprint: String,
)

/**
 * Turns validated extraction candidates for one stored document into
 * catalyst events. It resolves tickers only against the companies
 * linked to that document, then normalizes timestamps down the event/published/discovered
 * chain, assigns source quality from the provider tier, and links each
 * event to its source document. Cluster assignment follows in CR-11.
 *
 * Every method is pure: an event is validated, normalized, and given the
 * fingerprint it would be stored under, but nothing is written here. The
 * pipeline keeps provider calls outside the write transaction and persists
 * a whole document through DocumentOutcomePersistenceService.
 */
@Service
class EventNormalizationService(
    private val validator: ExtractionValidator,
) {

    /**
     * Validates and normalizes without touching the database. Every
     * candidate that survives carries the fingerprint it would be stored
     * under, so callers can persist all of them in one transaction.
     */
    fun prepareDocument(
        document: SourceDocument,
        result: ExtractionResult,
        allowedCompanies: Collection<Company>,
    ): List<NormalizedEvent> {
        return prepareValidatedDocument(document, validateDocument(result), allowedCompanies)
    }

    fun validateDocument(result: ExtractionResult): ExtractionResult = validator.validate(result)

    fun prepareValidatedDocument(document: SourceDocument, validated: ExtractionResult, allowedCompanies: Collection<Company>): List<NormalizedEvent> {
        if (!validated.documentRelevant) return emptyList()
        val companiesByTicker = companiesByTicker(allowedCompanies)
        return validated.events.mapNotNull { candidate ->
            normalize(document, candidate, companiesByTicker)?.let {
                NormalizedEvent(it, eventFingerprint(document.id, it))
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
            evidence = candidate.evidence,
        )
    }

    private fun companiesByTicker(companies: Collection<Company>): Map<String, Company> =
        companies.mapNotNull { company ->
            runCatching { normalizeTicker(company.ticker) }.getOrNull()?.let { it to company }
        }.toMap()

    /**
     * Identifies the real-world fact, not one model response. Only inputs
     * that distinguish events are hashed, so re-running extraction with
     * different confidence, magnitude, surprise, materiality, horizon, or
     * directness estimates cannot fork a second event, while a different
     * company, type, direction, timestamp, attribute, or quoted fact still
     * does. Model output location hints are excluded for the same reason:
     * they move without changing the fact.
     *
     * Fields are length-prefixed, so no quote or attribute can imitate a
     * separator and make two different events collide.
     */
    private fun eventFingerprint(sourceDocumentId: UUID, event: CatalystEvent): String {
        val fields = buildList {
            add(FINGERPRINT_SCHEME)
            add(sourceDocumentId.toString())
            add(event.companyId.toString())
            add(event.type.name)
            add(event.direction.name)
            add(event.eventTimestamp?.toString().orEmpty())
            event.attributes.toSortedMap().forEach { (key, value) -> add("$key=$value") }
            event.evidence.map { normalizeEvidenceText(it.quoteOrFact) }.distinct().sorted().forEach(::add)
        }
        val canonical = fields.joinToString(separator = "") { "${it.length}:$it" }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
    }

    /**
     * Evidence text varies only in presentation: the same quoted fact
     * arrives capitalized, collapsed, or line-wrapped depending on the
     * publisher and the model.
     */
    private fun normalizeEvidenceText(quoteOrFact: String): String =
        quoteOrFact.lowercase(Locale.ROOT).replace(WHITESPACE_RUN, " ").trim()

    private fun sourceQualityFor(provider: String): SourceQuality = when (provider) {
        "polygon" -> SourceQuality.TIER1_NEWS
        "finnhub" -> SourceQuality.TIER2_NEWS
        else -> SourceQuality.OTHER
    }

    private companion object {
        /** Version tag so a future input change is a deliberate schema decision. */
        const val FINGERPRINT_SCHEME = "event-fingerprint-v1"
        val WHITESPACE_RUN = Regex("\\s+")
    }
}
