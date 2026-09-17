package com.catalystradar.persistence.event

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventCluster
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.SourceDocumentStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Transactional
class EventStoreTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var events: EventStore

    @Autowired
    private lateinit var clusters: EventClusterStore

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    @Test
    fun `persists and reloads event with attributes and links`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell Technologies"))
        val document = documents.save(newDocument())
        val cluster = clusters.save(
            EventCluster(
                companyId = company.id,
                eventType = EventType.GUIDANCE_RAISE,
                firstSeenAt = Instant.parse("2026-09-16T10:00:00Z"),
            ),
        )

        val saved = events.save(
            CatalystEvent(
                companyId = company.id,
                clusterId = cluster.id,
                type = EventType.GUIDANCE_RAISE,
                direction = Direction.POSITIVE,
                confidence = 0.85,
                magnitude = 0.12,
                surprise = 0.6,
                materiality = 0.8,
                sourceQuality = SourceQuality.TIER1_NEWS,
                expectedHorizon = EventHorizon.WEEKS,
                directness = Directness.DIRECT,
                scheduled = false,
                eventTimestamp = Instant.parse("2026-09-15T20:00:00Z"),
                discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
                taxonomyVersion = "taxonomy-v1",
                extractorVersion = "event-extractor-v1",
                attributes = mapOf("period" to "FY2026"),
            ),
            sourceDocumentId = document.id,
        )

        val reloaded = events.findById(saved.id)

        assertNotNull(reloaded)
        assertEquals(EventType.GUIDANCE_RAISE, reloaded.type)
        assertEquals(Direction.POSITIVE, reloaded.direction)
        assertEquals(0.85, reloaded.confidence)
        assertEquals(cluster.id, reloaded.clusterId)
        assertEquals(mapOf("period" to "FY2026"), reloaded.attributes)
    }

    @Test
    fun `lists events by company and by cluster`() {
        val company = companies.save(Company(ticker = "DELL", name = "Dell Technologies"))
        val other = companies.save(Company(ticker = "HPQ", name = "HP"))
        val cluster = clusters.save(
            EventCluster(
                companyId = company.id,
                eventType = EventType.EARNINGS_BEAT,
                firstSeenAt = Instant.parse("2026-09-16T10:00:00Z"),
            ),
        )
        events.save(newEvent(company.id, cluster.id, EventType.EARNINGS_BEAT))
        events.save(newEvent(company.id, cluster.id, EventType.REVENUE_BEAT))
        events.save(newEvent(other.id, null, EventType.EARNINGS_BEAT))

        assertEquals(2, events.findByCompanyId(company.id).size)
        assertEquals(1, events.findByCompanyId(other.id).size)
        assertEquals(2, events.findByClusterId(cluster.id).size)
    }

    private fun newDocument() = SourceDocument(
        provider = "polygon",
        title = "Dell raises guidance",
        body = "Dell raised its full-year outlook.",
        discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
    )

    private fun newEvent(companyId: UUID, clusterId: UUID?, type: EventType) = CatalystEvent(
        companyId = companyId,
        clusterId = clusterId,
        type = type,
        direction = Direction.POSITIVE,
        confidence = 0.8,
        sourceQuality = SourceQuality.TIER1_NEWS,
        expectedHorizon = EventHorizon.DAYS,
        directness = Directness.DIRECT,
        discoveredAt = Instant.parse("2026-09-16T10:00:00Z"),
        taxonomyVersion = "taxonomy-v1",
        extractorVersion = "event-extractor-v1",
    )
}
