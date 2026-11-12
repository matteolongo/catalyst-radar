package com.catalystradar.application.extraction

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.ports.ExtractionRequest
import com.catalystradar.ports.ExtractionResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals

@Transactional
class ExtractionResultCacheTest : PostgresIntegrationTest() {

    @Autowired private lateinit var cache: ExtractionResultCache
    @Autowired private lateinit var documents: SourceDocumentStore
    @Autowired private lateinit var companies: CompanyStore

    private class CountingProvider(override val model: String) : EventExtractionProvider {
        var calls = 0
        override suspend fun extract(request: ExtractionRequest): ExtractionResult {
            calls++
            return ExtractionResult(documentRelevant = false, events = emptyList())
        }
    }

    private fun request(): ExtractionRequest {
        val company = companies.save(Company(ticker = "CACH", name = "Cache Co"))
        val document = documents.save(
            SourceDocument(provider = "polygon", title = "t", body = "b", discoveredAt = Instant.parse("2026-09-16T10:00:00Z")),
        )
        return ExtractionRequest(document, listOf(company))
    }

    @Test
    fun `a stored extraction result is reused instead of calling the model again`() = runTest {
        val provider = CountingProvider("model-a")
        val request = request()

        val first = cache.extract(provider, request)
        val second = cache.extract(provider, request)

        assertEquals(first, second)
        assertEquals(1, provider.calls)
    }

    @Test
    fun `results are not shared across models`() = runTest {
        val request = request()
        val a = CountingProvider("model-a")
        val b = CountingProvider("model-b")

        cache.extract(a, request)
        cache.extract(b, request)

        assertEquals(1, a.calls)
        assertEquals(1, b.calls)
    }
}
