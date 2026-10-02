package com.catalystradar.application.clustering

import com.catalystradar.domain.company.Company
import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventCluster
import com.catalystradar.domain.event.EventEvidence
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import com.catalystradar.domain.event.SourceQuality
import com.catalystradar.domain.event.SourceDocument
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.event.EventClusterStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.ports.Embedding
import com.catalystradar.ports.EmbeddingProvider
import com.pgvector.PGvector
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Duplicate-reporting scenarios: repeated coverage of one real-world
 * fact shares a canonical cluster (and later a single score), while
 * distinct facts stay apart. Clustering only decides here; the document
 * transaction that applies the decision is covered by the pipeline tests.
 */
@Transactional
class EventClusteringServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var events: EventStore

    @Autowired
    private lateinit var clusters: EventClusterStore

    @Autowired
    private lateinit var documents: SourceDocumentStore

    private var storedDocumentId: UUID = SOURCE_DOCUMENT_ID

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
        val evidence = listOf(EventEvidence("Dell raised its full-year outlook."))
        val first = newEvent(company.id, EventType.GUIDANCE_RAISE, "2026-09-16T10:00:00Z", evidence)
        val existing = clusters.save(
            EventCluster(
                companyId = company.id,
                eventType = EventType.GUIDANCE_RAISE,
                firstSeenAt = Instant.parse("2026-09-16T10:00:00Z"),
            ),
            embedding = PGvector(embeddings.embed(candidateText("DELL", first)).values.toFloatArray()),
            embeddingModel = embeddings.model,
        )
        val second = newEvent(company.id, EventType.GUIDANCE_RAISE, "2026-09-16T11:00:00Z", evidence)

        val plan = service().prepareClustering(unstoredDocument(), UNSTORED_FINGERPRINT, second)

        assertEquals(EventClusterPlan.JoinCluster(existing.id), plan)
    }

    @Test
    fun `different event types stay apart`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))

        val plan = service().prepareClustering(
            unstoredDocument(),
            UNSTORED_FINGERPRINT,
            newEvent(company.id, EventType.EARNINGS_BEAT, "2026-09-16T10:00:00Z"),
        )

        assertIs<EventClusterPlan.OpenCluster>(plan)
    }

    @Test
    fun `events outside the time window stay apart`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val evidence = listOf(EventEvidence("Dell raised its full-year outlook."))
        val earlier = newEvent(company.id, EventType.GUIDANCE_RAISE, "2026-09-10T10:00:00Z", evidence)
        clusters.save(
            EventCluster(
                companyId = company.id,
                eventType = EventType.GUIDANCE_RAISE,
                firstSeenAt = Instant.parse("2026-09-10T10:00:00Z"),
            ),
            embedding = PGvector(embeddings.embed(candidateText("DELL", earlier)).values.toFloatArray()),
            embeddingModel = embeddings.model,
        )

        val plan = service().prepareClustering(
            unstoredDocument(),
            UNSTORED_FINGERPRINT,
            newEvent(company.id, EventType.GUIDANCE_RAISE, "2026-09-16T10:00:00Z", evidence),
        )

        assertIs<EventClusterPlan.OpenCluster>(plan)
    }

    @Test
    fun `same typed events with different evidence stay apart`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val first = newEvent(
            company.id,
            EventType.GUIDANCE_RAISE,
            "2026-09-16T10:00:00Z",
            listOf(EventEvidence("Dell raised fiscal 2027 EPS guidance.")),
        )
        clusters.save(
            EventCluster(
                companyId = company.id,
                eventType = EventType.GUIDANCE_RAISE,
                firstSeenAt = Instant.parse("2026-09-16T10:00:00Z"),
            ),
            embedding = PGvector(embeddings.embed(candidateText("DELL", first)).values.toFloatArray()),
            embeddingModel = embeddings.model,
        )

        val plan = service().prepareClustering(
            unstoredDocument(),
            UNSTORED_FINGERPRINT,
            newEvent(
                company.id,
                EventType.GUIDANCE_RAISE,
                "2026-09-16T11:00:00Z",
                listOf(EventEvidence("Dell raised fiscal 2028 revenue guidance.")),
            ),
        )

        assertIs<EventClusterPlan.OpenCluster>(plan)
    }

    @Test
    fun `a cluster first seen after the event cannot match it`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val evidence = listOf(EventEvidence("Dell raised its full-year outlook."))
        val later = newEvent(company.id, EventType.GUIDANCE_RAISE, "2026-09-16T10:00:00Z", evidence)
        clusters.save(
            EventCluster(
                companyId = company.id,
                eventType = EventType.GUIDANCE_RAISE,
                firstSeenAt = Instant.parse("2026-09-16T10:00:00Z"),
            ),
            embedding = PGvector(embeddings.embed(candidateText("DELL", later)).values.toFloatArray()),
            embeddingModel = embeddings.model,
        )

        val plan = service().prepareClustering(
            unstoredDocument(),
            UNSTORED_FINGERPRINT,
            newEvent(company.id, EventType.GUIDANCE_RAISE, "2026-09-15T10:00:00Z", evidence),
        )

        assertIs<EventClusterPlan.OpenCluster>(plan)
    }

    @Test
    fun `reuses the stored cluster of an already clustered fact without embedding again`() = runTest {
        val company = companies.save(Company(ticker = "DELL", name = "Dell"))
        val event = newEvent(company.id, EventType.GUIDANCE_RAISE, "2026-09-16T10:00:00Z")
        val cluster = clusters.save(
            EventCluster(
                companyId = company.id,
                eventType = EventType.GUIDANCE_RAISE,
                firstSeenAt = Instant.parse("2026-09-16T10:00:00Z"),
            ),
            embedding = PGvector(embeddings.embed(candidateText("DELL", event)).values.toFloatArray()),
            embeddingModel = embeddings.model,
        )
        val stored = events.saveIfAbsent(event, storedDocument().id, STORED_FINGERPRINT).event
        events.assignCluster(stored.id, cluster.id)
        val callsBefore = embeddings.calls

        val plan = service().prepareClustering(
            sourceDocumentId = storedDocumentId,
            eventFingerprint = STORED_FINGERPRINT,
            event = stored,
        )

        assertEquals(EventClusterPlan.JoinCluster(cluster.id), plan)
        assertEquals(callsBefore, embeddings.calls)
    }

    private fun unstoredDocument(): UUID = UUID.randomUUID()

    /** The document a previously stored event is filed under. */
    private fun storedDocument(): SourceDocument = documents.findById(storedDocumentId)
        ?: documents.save(
            SourceDocument(
                provider = "polygon",
                title = "Dell raises outlook",
                body = "Dell raised its outlook.",
                discoveredAt = Instant.parse("2026-09-16T12:00:00Z"),
            ),
        ).also { storedDocumentId = it.id }

    private fun newEvent(
        companyId: UUID,
        type: EventType,
        at: String,
        evidence: List<EventEvidence> = emptyList(),
    ) = CatalystEvent(
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
        evidence = evidence,
    )

    /** Deterministic 1536-dim vectors: identical text always matches exactly. */
    class FakeEmbeddingProvider : EmbeddingProvider {
        override val model: String = "fake"

        var calls: Int = 0
            private set

        override suspend fun embed(text: String): Embedding {
            calls++
            var seed = text.hashCode().toLong()
            val values = List(1536) {
                seed = (seed * 6364136223846793005L + 1442695040888963407L) shr 11
                ((seed ushr 32) % 2000).toFloat() / 1000f - 1f
            }
            return Embedding(values, model)
        }
    }

    companion object {
        val SOURCE_DOCUMENT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000ff")
        val STORED_FINGERPRINT: String = "a".repeat(64)
        val UNSTORED_FINGERPRINT: String = "b".repeat(64)
    }
}
