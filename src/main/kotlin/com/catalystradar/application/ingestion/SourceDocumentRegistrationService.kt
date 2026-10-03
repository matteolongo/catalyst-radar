package com.catalystradar.application.ingestion

import com.catalystradar.domain.company.normalizeTicker
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.DocumentProcessingStore
import com.catalystradar.persistence.document.SourceDocumentCompanyStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.jdbc.toJsonB
import com.catalystradar.ports.RawArticle
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * What registration did with one fetched article. An article naming no
 * configured company is still stored: raw source material is the audit
 * trail, and a ticker nobody tracks today may be tracked tomorrow. It is
 * not queued, because nothing downstream could attribute it and guessing
 * would attach catalyst impact to the wrong company.
 */
sealed interface DocumentRegistration {

    /** Stored and queued for extraction. */
    data class Queued(val documentId: UUID) : DocumentRegistration

    /** Stored for audit only: no configured company explains this article. */
    data class Unresolved(val documentId: UUID) : DocumentRegistration

    /** Already ingested earlier, so nothing changed. */
    data object Duplicate : DocumentRegistration
}

/**
 * Registers one provider article after it has been fetched. This is the
 * transaction boundary for local source-document, company-link, and queue
 * writes; provider and model calls remain outside it.
 */
@Service
class SourceDocumentRegistrationService(
    private val companies: CompanyStore,
    private val documents: SourceDocumentStore,
    private val documentCompanies: SourceDocumentCompanyStore,
    private val processing: DocumentProcessingStore,
) {

    @Transactional
    fun registerIfNew(provider: String, article: RawArticle, now: Instant): DocumentRegistration {
        val normalized = normalizeArticle(article, now)
        if (existingDocument(provider, normalized.providerDocumentId, normalized.contentHash) != null) {
            return DocumentRegistration.Duplicate
        }

        val document = try {
            documents.save(
                normalized.toDocument(),
                rawPayload = mapOf(
                    "provider" to article.provider,
                    "tickers" to article.tickers.joinToString(","),
                ).toJsonB(),
            )
        } catch (e: DataIntegrityViolationException) {
            if (existingDocument(provider, normalized.providerDocumentId, normalized.contentHash) != null) {
                return DocumentRegistration.Duplicate
            }
            throw e
        }

        val companyIds = article.tickers.mapNotNull { ticker ->
            runCatching { normalizeTicker(ticker) }.getOrNull()
                ?.let(companies::findByTicker)
                ?.id
        }.toSet()
        documentCompanies.link(document.id, companyIds)
        if (companyIds.isEmpty()) return DocumentRegistration.Unresolved(document.id)
        processing.ensurePending(document.id, now)
        return DocumentRegistration.Queued(document.id)
    }

    private fun existingDocument(provider: String, providerDocumentId: String?, contentHash: String) =
        providerDocumentId?.let { documents.findByProviderAndProviderDocumentId(provider, it) }
            ?: documents.findByContentHash(contentHash)
}
