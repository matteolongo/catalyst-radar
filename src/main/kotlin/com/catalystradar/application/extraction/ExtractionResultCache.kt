package com.catalystradar.application.extraction

import com.catalystradar.common.Versions
import com.catalystradar.persistence.extraction.ExtractionResultStore
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.ports.ExtractionRequest
import com.catalystradar.ports.ExtractionResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Read-through cache of parsed extraction results. Source documents are
 * immutable, so (document, extractor version, model) fully determines the
 * input to the model; a hit replaces a paid, non-deterministic call.
 * Storage failures never fail extraction: the cache only saves work.
 */
@Service
class ExtractionResultCache(private val store: ExtractionResultStore) {

    private val log = LoggerFactory.getLogger(ExtractionResultCache::class.java)

    suspend fun extract(provider: EventExtractionProvider, request: ExtractionRequest): ExtractionResult {
        val documentId = request.document.id
        runCatching { store.find(documentId, Versions.EXTRACTOR_V1, provider.model) }
            .onFailure { log.warn("extraction cache read failed for document {}", documentId) }
            .getOrNull()
            ?.let { return it }
        val result = provider.extract(request)
        runCatching { store.save(documentId, Versions.EXTRACTOR_V1, provider.model, result) }
            .onFailure { log.warn("extraction cache write failed for document {}", documentId) }
        return result
    }
}
