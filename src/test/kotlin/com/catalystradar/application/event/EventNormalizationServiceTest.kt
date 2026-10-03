package com.catalystradar.application.event

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventEvidence
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.event.EventRepository
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.persistence.event.StoredEvent
import com.catalystradar.ports.ExtractedEvent
import com.catalystradar.ports.ExtractionResult
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    /**
     * Normalization itself writes nothing; this stores what it prepared
     * the way the pipeline's document transaction does, so the tests can
     * still observe which fingerprint each candidate earned.
     */
    private fun store(
        document: SourceDocument,
        result: ExtractionResult,
        allowedCompanies: List<Company>,
    ): List<StoredEvent> = service.prepareDocument(document, result, allowedCompanies).map { candidate ->
        events.saveIfAbsent(candidate.event, document.id, candidate.fingerprint)
    }

    @Test
    fun `persists validated candidates for supported companies`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val document = documents.save(newDocument("polygon"))

        val persisted = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    candidate("DELL", EventType.GUIDANCE_RAISE),
                    candidate("ZZZZ", EventType.GUIDANCE_RAISE),
                    candidate("DELL", EventType.EARNINGS_BEAT, confidence = 1.5),
                ),
            ),
            listOf(company),
        )

        assertEquals(1, persisted.size)
        assertEquals(company.id, persisted[0].event.companyId)
        assertEquals(EventType.GUIDANCE_RAISE, persisted[0].event.type)
        assertEquals(SourceQuality.TIER1_NEWS, persisted[0].event.sourceQuality)
        assertEquals("taxonomy-v1", persisted[0].event.taxonomyVersion)
        assertEquals("event-extractor-v1", persisted[0].event.extractorVersion)
        assertEquals(listOf(EventEvidence("raised", null)), persisted[0].event.evidence)
        assertNotNull(events.findById(persisted[0].event.id))
    }

    @Test
    fun `links events to their source document`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val document = documents.save(newDocument("polygon"))

        val persisted = store(
            document,
            ExtractionResult(documentRelevant = true, events = listOf(candidate("DELL", EventType.EARNINGS_BEAT))),
            listOf(company),
        )

        val companyEvents = events.findByCompanyId(persisted[0].event.companyId)
        assertEquals(1, companyEvents.size)
        assertEquals(
            document.id,
            eventRows.findById(persisted[0].event.id).orElseThrow().sourceDocumentId,
        )
    }

    @Test
    fun `falls back along the timestamp chain`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val publishedAt = Instant.parse("2026-09-15T21:00:00Z")
        val discoveredAt = Instant.parse("2026-09-16T10:00:00Z")
        val withPublished = documents.save(newDocument("finnhub", "fin-1").copy(publishedAt = publishedAt))
        val withoutPublished = documents.save(newDocument("finnhub", "fin-2").copy(publishedAt = null))

        val first = store(
            withPublished,
            ExtractionResult(documentRelevant = true, events = listOf(candidate("DELL", EventType.EARNINGS_BEAT))),
            listOf(company),
        )
        val second = store(
            withoutPublished,
            ExtractionResult(documentRelevant = true, events = listOf(candidate("DELL", EventType.EARNINGS_BEAT))),
            listOf(company),
        )

        assertEquals(publishedAt, first[0].event.eventTimestamp)
        assertEquals(discoveredAt, second[0].event.eventTimestamp)
        assertEquals(SourceQuality.TIER2_NEWS, first[0].event.sourceQuality)
        assertEquals(discoveredAt, first[0].event.discoveredAt)
    }

    @Test
    fun `rejects candidates outside the document company scope`() {
        val dell = companies.save(Company(ticker = "DELL", name = "Dell"))
        companies.save(Company(ticker = "MSFT", name = "Microsoft"))
        val document = documents.save(newDocument("polygon"))

        val persisted = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    candidate("DELL", EventType.GUIDANCE_RAISE),
                    candidate("MSFT", EventType.GUIDANCE_RAISE),
                ),
            ),
            listOf(dell),
        )

        assertEquals(listOf(dell.id), persisted.map { it.event.companyId })
    }

    @Test
    fun `rejects event timestamps after document discovery`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val document = documents.save(newDocument("polygon"))

        val persisted = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    candidate("DELL", EventType.GUIDANCE_RAISE)
                        .copy(eventTimestamp = document.discoveredAt.plusSeconds(1)),
                ),
            ),
            listOf(company),
        )

        assertEquals(emptyList(), persisted)
    }

    @Test
    fun `model estimate drift does not duplicate the same factual event`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val document = documents.save(newDocument("polygon"))

        val first = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    candidate(
                        "DELL", EventType.GUIDANCE_RAISE,
                        confidence = 0.55, magnitude = 0.1, surprise = 0.2, materiality = 0.3,
                    ),
                ),
            ),
            listOf(company),
        )
        val second = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    candidate(
                        "DELL", EventType.GUIDANCE_RAISE,
                        confidence = 0.99, magnitude = 0.9, surprise = 0.8, materiality = 0.95,
                        expectedHorizon = EventHorizon.DAYS, directness = Directness.INFERRED,
                    ),
                ),
            ),
            listOf(company),
        )

        assertEquals(first.map { it.event.id }, second.map { it.event.id })

        assertTrue(first.all { it.inserted })

        assertTrue(second.none { it.inserted })
        assertEquals(1, events.findByCompanyId(company.id).size)
    }

    @Test
    fun `source offset hint drift does not duplicate the same factual event`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val document = documents.save(newDocument("polygon"))

        val first = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(candidate("DELL", EventType.GUIDANCE_RAISE, evidence = listOf(EventEvidence("raised", "body:0-6")))),
            ),
            listOf(company),
        )
        val second = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(candidate("DELL", EventType.GUIDANCE_RAISE, evidence = listOf(EventEvidence("raised", null)))),
            ),
            listOf(company),
        )

        assertEquals(first.map { it.event.id }, second.map { it.event.id })

        assertTrue(first.all { it.inserted })

        assertTrue(second.none { it.inserted })
        assertEquals(1, events.findByCompanyId(company.id).size)
    }

    @Test
    fun `evidence whitespace and case differences do not duplicate the same factual event`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val document = documents.save(newDocument("polygon"))

        val first = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    candidate("DELL", EventType.GUIDANCE_RAISE, evidence = listOf(EventEvidence("Dell raised\n its   outlook.", null))),
                ),
            ),
            listOf(company),
        )
        val second = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    candidate("DELL", EventType.GUIDANCE_RAISE, evidence = listOf(EventEvidence("dell raised its outlook.", null))),
                ),
            ),
            listOf(company),
        )

        assertEquals(first.map { it.event.id }, second.map { it.event.id })

        assertTrue(first.all { it.inserted })

        assertTrue(second.none { it.inserted })
        assertEquals(1, events.findByCompanyId(company.id).size)
    }

    @Test
    fun `attribute ordering does not duplicate the same factual event`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val document = documents.save(newDocument("polygon"))

        val first = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    candidate("DELL", EventType.GUIDANCE_RAISE, attributes = mapOf("period" to "FY2027", "segment" to "ISG")),
                ),
            ),
            listOf(company),
        )
        val second = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    candidate("DELL", EventType.GUIDANCE_RAISE, attributes = mapOf("segment" to "ISG", "period" to "FY2027")),
                ),
            ),
            listOf(company),
        )

        assertEquals(first.map { it.event.id }, second.map { it.event.id })

        assertTrue(first.all { it.inserted })

        assertTrue(second.none { it.inserted })
        assertEquals(1, events.findByCompanyId(company.id).size)
    }

    @Test
    fun `different factual evidence for the same document stays distinct`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val document = documents.save(newDocument("polygon"))

        val stored = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    candidate("DELL", EventType.GUIDANCE_RAISE, evidence = listOf(EventEvidence("raised", null))),
                    candidate("DELL", EventType.GUIDANCE_RAISE, evidence = listOf(EventEvidence("lowered", null))),
                ),
            ),
            listOf(company),
        )

        assertEquals(2, stored.size)
        assertEquals(2, events.findByCompanyId(company.id).size)
    }

    @Test
    fun `distinct event identity stays distinct for the same evidence`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val document = documents.save(newDocument("polygon"))

        val stored = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    candidate("DELL", EventType.GUIDANCE_RAISE),
                    candidate("DELL", EventType.GUIDANCE_CUT),
                    candidate("DELL", EventType.GUIDANCE_RAISE, direction = Direction.NEGATIVE),
                    candidate("DELL", EventType.GUIDANCE_RAISE, attributes = mapOf("period" to "FY2028")),
                    candidate(
                        "DELL", EventType.GUIDANCE_RAISE,
                        eventTimestamp = Instant.parse("2026-09-15T12:00:00Z"),
                    ),
                ),
            ),
            listOf(company),
        )

        assertEquals(5, stored.size)
        assertEquals(5, events.findByCompanyId(company.id).size)
    }

    @Test
    fun `evidence order does not duplicate the same factual event`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val document = documents.save(newDocument("polygon"))

        val first = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    candidate(
                        "DELL", EventType.GUIDANCE_RAISE,
                        evidence = listOf(EventEvidence("raised", null), EventEvidence("outlook", null)),
                    ),
                ),
            ),
            listOf(company),
        )
        val second = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(
                    candidate(
                        "DELL", EventType.GUIDANCE_RAISE,
                        evidence = listOf(EventEvidence("outlook", null), EventEvidence("raised", null)),
                    ),
                ),
            ),
            listOf(company),
        )

        assertEquals(first.map { it.event.id }, second.map { it.event.id })

        assertTrue(first.all { it.inserted })

        assertTrue(second.none { it.inserted })
        assertEquals(1, events.findByCompanyId(company.id).size)
    }

    @Test
    fun `carries extracted evidence into the stored event unchanged`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val document = documents.save(newDocument("polygon"))
        val evidence = listOf(EventEvidence("Dell raised its outlook.", "body:0-27"))

        val stored = store(
            document,
            ExtractionResult(
                documentRelevant = true,
                events = listOf(candidate("DELL", EventType.GUIDANCE_RAISE, evidence = evidence)),
            ),
            listOf(company),
        )

        assertEquals(evidence, stored[0].event.evidence)
        assertEquals(
            listOf(EventEvidence("Dell raised its outlook.", "body:0-27")),
            events.findById(stored[0].event.id)?.evidence,
        )
    }

    private fun newDocument(provider: String, providerDocumentId: String? = null) = SourceDocument(
        provider = provider,
        providerDocumentId = providerDocumentId ?: if (provider == "polygon") "poly-1" else "fin-1",
        title = "Dell raises forecast",
        body = "Dell raised.",
        publishedAt = Instant.parse("2026-09-15T21:00:00Z"),
        discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
    )

    private fun candidate(
        ticker: String,
        type: EventType,
        confidence: Double = 0.9,
        magnitude: Double? = null,
        surprise: Double? = null,
        materiality: Double? = null,
        expectedHorizon: EventHorizon = EventHorizon.WEEKS,
        directness: Directness = Directness.DIRECT,
        direction: Direction = Direction.POSITIVE,
        eventTimestamp: Instant? = null,
        evidence: List<EventEvidence> = listOf(EventEvidence("raised", null)),
        attributes: Map<String, String> = emptyMap(),
    ) = ExtractedEvent(
        ticker = ticker,
        type = type,
        direction = direction,
        confidence = confidence,
        magnitude = magnitude,
        surprise = surprise,
        materiality = materiality,
        expectedHorizon = expectedHorizon,
        directness = directness,
        eventTimestamp = eventTimestamp,
        evidence = evidence,
        attributes = attributes,
    )
}
