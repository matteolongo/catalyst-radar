package com.catalystradar.application.universe

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.company.normalizeTicker
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.DocumentProcessingStore
import com.catalystradar.persistence.document.SourceDocumentCompanyStore
import com.catalystradar.persistence.universe.UnresolvedTickerStore
import com.catalystradar.ports.CompanyLookupProvider
import com.catalystradar.ports.CompanyReference
import com.catalystradar.ports.ProviderException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class PromotionResult(
    val company: Company,
    /** False when the ticker was already a configured company; nothing changed then. */
    val created: Boolean,
    val documentsLinked: Int,
    val documentsQueued: Int,
    val trackedSince: Instant?,
)

/** The provider does not list the ticker as an active US common stock. */
class NotUsEquityException(val ticker: String) : RuntimeException("$ticker is not an active US common stock")

/** The reference provider failed, so the ticker could be neither confirmed nor rejected. */
class ReferenceLookupFailedException(val ticker: String, cause: ProviderException) :
    RuntimeException("reference lookup failed for $ticker", cause)

/** The configured active-company cap has been reached. */
class UniverseCapReachedException(val cap: Int) : RuntimeException("the active universe already holds $cap companies")

/**
 * Adds a candidate ticker to the universe (ADR 0002). The reference lookup
 * runs before, and outside, the write transaction. Its stored documents are
 * linked; only documents never queued are queued, so no document is
 * re-extracted and no event is created twice.
 */
@Service
class UniversePromotionService(
    private val companies: CompanyStore,
    private val lookup: CompanyLookupProvider,
    private val writer: UniversePromotionWriter,
    private val properties: UniverseProperties,
) {

    private val log = LoggerFactory.getLogger(UniversePromotionService::class.java)

    suspend fun promote(rawTicker: String, now: Instant = Instant.now()): PromotionResult {
        val ticker = normalizeTicker(rawTicker)
        companies.findByTicker(ticker)?.let { return existing(it) }
        if (companies.countActive() >= properties.maxActiveCompanies) throw UniverseCapReachedException(properties.maxActiveCompanies)
        val reference = try {
            lookup.findUsEquity(ticker)
        } catch (e: ProviderException) {
            throw ReferenceLookupFailedException(ticker, e)
        } ?: throw NotUsEquityException(ticker)
        val result = try {
            writer.write(ticker, reference, now, properties.maxActiveCompanies)
        } catch (e: DataIntegrityViolationException) {
            // A concurrent promotion of the same ticker won; report its company.
            companies.findByTicker(ticker)?.let { return existing(it) } ?: throw e
        }
        log.info(
            "promoted ticker={} companyId={} documentsLinked={} documentsQueued={}",
            ticker, result.company.id, result.documentsLinked, result.documentsQueued,
        )
        return result
    }

    private fun existing(company: Company) =
        PromotionResult(company, created = false, documentsLinked = 0, documentsQueued = 0, trackedSince = companies.findTrackedSince(company.id))
}

@Service
class UniversePromotionWriter(
    private val companies: CompanyStore,
    private val unresolved: UnresolvedTickerStore,
    private val documentCompanies: SourceDocumentCompanyStore,
    private val processing: DocumentProcessingStore,
) {

    @Transactional
    fun write(ticker: String, reference: CompanyReference, now: Instant, cap: Int): PromotionResult {
        if (companies.countActive() >= cap) throw UniverseCapReachedException(cap)
        val company = companies.save(
            Company(
                ticker = ticker,
                name = reference.name,
                exchange = reference.exchange,
                sector = reference.sector,
                industry = reference.industry,
                country = reference.country,
            ),
        )
        companies.markPromoted(company.id, now)
        val documentIds = unresolved.documentIds(ticker)
        var queued = 0
        documentIds.forEach { documentId ->
            val neverQueued = processing.findBySourceDocumentId(documentId) == null
            documentCompanies.link(documentId, company.id)
            if (neverQueued) {
                processing.ensurePending(documentId, now)
                queued++
            }
        }
        unresolved.clear(ticker)
        return PromotionResult(company, created = true, documentsLinked = documentIds.size, documentsQueued = queued, trackedSince = now)
    }
}
