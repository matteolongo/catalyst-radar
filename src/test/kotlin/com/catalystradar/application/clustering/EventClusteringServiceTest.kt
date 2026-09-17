package com.catalystradar.application.clustering

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.event.EventClusterStore
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.ports.Embedding
import com.catalystradar.ports.EmbeddingProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Duplicate-reporting scenarios: repeated coverage of one real-world
 * fact shares a canonical cluster (and later a single score), while
 * distinct facts stay apart.
 */
@Transactional
class EventClusteringServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var events: EventStore

    @Autowired
    private lateinit var clusters: EventClusterStore

    private val embeddings = FakeEmbeddingProvider()

    private fun service() = EventClusteringService(
        embeddings = embeddings,
        clusters = clusters,
        events = events,
        companies = companies,
        properties = DedupProperties(),
    )

    @Test
    fun `duplicate reports share one cluster`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val first = events.save(newEvent(company.id, EventType.GUIDANCE_RAISE, "2026-09-16T10:00:00Z"))
        val second = events.save(newEvent(company.id, EventType.GUIDANCE_RAISE, "2026-09-16T11:00:00Z"))

        val firstCluster = service().clusterEvent(first.id)
        val secondCluster = service().clusterEvent(second.id)

        assertEquals(firstCluster, secondCluster)
        assertEquals(firstCluster, events.findById(first.id)?.clusterId)
        assertEquals(firstCluster, events.findById(second.id)?.clusterId)
    }

    @Test
    fun `different event types stay apart`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val first = events.save(newEvent(company.id, EventType.EARNINGS_BEAT, "2026-09-16T10:00:00Z"))
        val second = events.save(newEvent(company.id, EventType.REVENUE_BEAT, "2026-09-16T10:00:00Z"))

        val firstCluster = service().clusterEvent(first.id)
        val secondCluster = service().clusterEvent(second.id)

        assertNotEquals(firstCluster, secondCluster)
    }

    @Test
    fun `events outside the time window stay apart`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val first = events.save(newEvent(company.id, EventType.GUIDANCE_RAISE, "2026-09-10T10:00:00Z"))
        val second = events.save(newEvent(company.id, EventType.GUIDANCE_RAISE, "2026-09-16T10:00:00Z"))

        val firstCluster = service().clusterEvent(first.id)
        val secondCluster = service().clusterEvent(second.id)

        assertNotEquals(firstCluster, secondCluster)
    }

    @Test
    fun `reclustering is idempotent`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val event = events.save(newEvent(company.id, EventType.GUIDANCE_RAISE, "2026-09-16T10:00:00Z"))
        val service = service()

        val first = service.clusterEvent(event.id)
        val second = service.clusterEvent(event.id)

        assertEquals(first, second)
        assertNotNull(clusters.findById(first))
    }

    private fun newEvent(companyId: UUID, type: EventType, at: String) = CatalystEvent(
        companyId = companyId,
        type = type,
        direction = Direction.POSITIVE,
        confidence = 0.9,
        sourceQuality = SourceQuality.TIER1_NEWS,
        expectedHorizon = EventHorizon.WEEKS,
        directness = Directness.DIRECT,
        eventTimestamp = Instant.parse(at),
        discoveredAt = Instant.parse("2026-09-16T12:00:00Z"),
        taxonomyVersion = "taxonomy-v1",
        extractorVersion = "event-extractor-v1",
    )

    /** Deterministic 1536-dim vectors: identical text always matches exactly. */
    class FakeEmbeddingProvider : EmbeddingProvider {
        override val model: String = "fake"

        override suspend fun embed(text: String): Embedding {
            var seed = text.hashCode().toLong()
            val values = List(1536) {
                seed = (seed * 6364136223846793005L + 1442695040888963407L) shr 11
                ((seed ushr 32) % 2000).toFloat() / 1000f - 1f
            }
            return Embedding(values, model)
        }
    }
}
