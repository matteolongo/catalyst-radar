package com.catalystradar.application.event

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.event.EventRepository
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.ports.EvidenceSpan
import com.catalystradar.ports.ExtractedEvent
import com.catalystradar.ports.ExtractionResult
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Transactional
class EventNormalizationServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var service: EventNormalizationService

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Autowired
    private lateinit var events: EventStore

    @Autowired
    private lateinit var eventRows: EventRepository

    @Test
    fun `persists validated candidates for supported companies`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val document = documents.save(newDocument("polygon"))

        val persisted = service.processDocument(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    candidate("DELL", EventType.GUIDANCE_RAISE),
                    candidate("ZZZZ", EventType.GUIDANCE_RAISE),
                    candidate("DELL", EventType.EARNINGS_BEAT, confidence = 1.5),
                ),
            ),
        )

        assertEquals(1, persisted.size)
        assertEquals(company.id, persisted[0].companyId)
        assertEquals(EventType.GUIDANCE_RAISE, persisted[0].type)
        assertEquals(SourceQuality.TIER1_NEWS, persisted[0].sourceQuality)
        assertEquals("taxonomy-v1", persisted[0].taxonomyVersion)
        assertEquals("event-extractor-v1", persisted[0].extractorVersion)
        assertNotNull(events.findById(persisted[0].id))
    }

    @Test
    fun `links events to their source document`() {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        val document = documents.save(newDocument("polygon"))

        val persisted = service.processDocument(
            document,
            ExtractionResult(documentRelevant = true, events = listOf(candidate("DELL", EventType.EARNINGS_BEAT))),
        )

        val companyEvents = events.findByCompanyId(persisted[0].companyId)
        assertEquals(1, companyEvents.size)
        assertEquals(
            document.id,
            eventRows.findById(persisted[0].id).orElseThrow().sourceDocumentId,
        )
    }

    @Test
    fun `falls back along the timestamp chain`() {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        val publishedAt = Instant.parse("2026-09-15T21:00:00Z")
        val discoveredAt = Instant.parse("2026-09-16T10:00:00Z")
        val withPublished = documents.save(newDocument("finnhub", "fin-1").copy(publishedAt = publishedAt))
        val withoutPublished = documents.save(newDocument("finnhub", "fin-2").copy(publishedAt = null))

        val first = service.processDocument(
            withPublished,
            ExtractionResult(documentRelevant = true, events = listOf(candidate("DELL", EventType.EARNINGS_BEAT))),
        )
        val second = service.processDocument(
            withoutPublished,
            ExtractionResult(documentRelevant = true, events = listOf(candidate("DELL", EventType.EARNINGS_BEAT))),
        )

        assertEquals(publishedAt, first[0].eventTimestamp)
        assertEquals(discoveredAt, second[0].eventTimestamp)
        assertEquals(SourceQuality.TIER2_NEWS, first[0].sourceQuality)
        assertEquals(discoveredAt, first[0].discoveredAt)
    }

    private fun newDocument(provider: String, providerDocumentId: String? = null) = SourceDocument(
        provider = provider,
        providerDocumentId = providerDocumentId ?: if (provider == "polygon") "poly-1" else "fin-1",
        title = "Dell raises forecast",
        body = "Dell raised.",
        publishedAt = Instant.parse("2026-09-15T21:00:00Z"),
        discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
    )

    private fun candidate(ticker: String, type: EventType, confidence: Double = 0.9) = ExtractedEvent(
        ticker = ticker,
        type = type,
        direction = Direction.POSITIVE,
        confidence = confidence,
        magnitude = null,
        surprise = null,
        materiality = null,
        expectedHorizon = EventHorizon.WEEKS,
        directness = Directness.DIRECT,
        eventTimestamp = null,
        evidence = listOf(EvidenceSpan("raised", null)),
        attributes = emptyMap(),
    )
}
