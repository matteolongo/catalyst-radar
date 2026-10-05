package com.catalystradar.operations

import com.catalystradar.persistence.operations.DocumentStepStore
import com.catalystradar.application.catalyst.CatalystService
import com.catalystradar.application.clustering.DedupProperties
import com.catalystradar.application.clustering.EventClusteringService
import com.catalystradar.application.event.EventNormalizationService
import com.catalystradar.application.ingestion.IngestionProperties
import com.catalystradar.application.ingestion.IngestionService
import com.catalystradar.application.ingestion.SourceDocumentRegistrationService
import com.catalystradar.application.operations.OperationRunRecorder
import com.catalystradar.application.operations.ProcessingAttemptService
import com.catalystradar.application.pipeline.DocumentOutcomePersistenceService
import com.catalystradar.application.pipeline.PipelineProperties
import com.catalystradar.application.pipeline.PipelineService
import com.catalystradar.application.pipeline.PipelineServiceTest
import com.catalystradar.common.Versions
import com.catalystradar.domain.company.Company
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.document.DocumentProcessingStore
import com.catalystradar.persistence.document.SourceDocumentCompanyStore
import com.catalystradar.persistence.document.SourceDocumentStore
import com.catalystradar.persistence.event.EventClusterStore
import com.catalystradar.persistence.event.EventStore
import com.catalystradar.persistence.extraction.ModelRunInput
import com.catalystradar.persistence.extraction.ModelRunStore
import com.catalystradar.persistence.ingestion.IngestionRunStore
import com.catalystradar.ports.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class RecordedPipelineFixture(
    private val companyStore: CompanyStore,
    private val registrations: SourceDocumentRegistrationService,
    private val ingestionRuns: IngestionRunStore,
    private val normalization: EventNormalizationService,
    private val clusters: EventClusterStore,
    private val events: EventStore,
    private val persistence: DocumentOutcomePersistenceService,
    private val catalyst: CatalystService,
    private val documents: SourceDocumentStore,
    private val documentCompanies: SourceDocumentCompanyStore,
    private val processing: DocumentProcessingStore,
    private val modelRuns: ModelRunStore,
    private val recorder: OperationRunRecorder,
    private val attempts: ProcessingAttemptService,
    private val steps: DocumentStepStore,
) {
    fun build(
        company: Company,
        at: Instant,
        titles: List<String> = listOf("Observability raise story"),
        failFirstTitle: String? = null,
        failIngestion: Boolean = false,
    ): PipelineService {
        val metrics = CatalystMetrics(SimpleMeterRegistry())
        val failedSources = mutableSetOf<UUID>()
        val articles = titles.map { title ->
            val externalId = UUID.randomUUID().toString()
            RawArticle(
                provider = "polygon", providerArticleId = externalId,
                url = "https://example.invalid/$externalId", title = title,
                body = "${company.ticker} raised its outlook: $title",
                publishedAt = at.minusSeconds(600), tickers = listOf(company.ticker),
            )
        }
        val news = object : NewsProvider {
            override val name = "polygon"
            override suspend fun fetch(request: NewsFetchRequest): NewsFetchResult {
                if (failIngestion) throw ProviderException.RateLimited(retryAfterSeconds = 30L)
                return NewsFetchResult(
                    articles = if (company.ticker in request.tickers) articles else emptyList(),
                    nextCursor = null,
                )
            }
        }
        val extraction = object : EventExtractionProvider {
            override suspend fun extract(request: ExtractionRequest): ExtractionResult {
                val fails = request.document.title == failFirstTitle && failedSources.add(request.document.id)
                modelRuns.record(ModelRunInput(
                    provider = "openai", operation = "extract", model = "gpt-4o-mini",
                    promptVersion = Versions.PROMPT_V1, extractorVersion = Versions.EXTRACTOR_V1,
                    sourceDocumentId = request.document.id, processingAttemptId = request.processingAttemptId,
                    inputTokens = if (fails) null else 100, outputTokens = if (fails) null else 50,
                    latencyMs = 10, estimatedCost = if (fails) null else BigDecimal("0.000045"),
                    success = !fails, errorCode = if (fails) "RATE_LIMITED" else null,
                    error = if (fails) "Provider rate limit; wait for the scheduled retry." else null,
                ))
                if (fails) throw ProviderException.RateLimited(retryAfterSeconds = 60, provider = "openai")
                return PipelineServiceTest.TitleExtraction.extract(request)
            }
        }
        val embeddings = object : EmbeddingProvider {
            override val model = "text-embedding-3-small"
            override suspend fun embed(text: String): Embedding = embed(EmbeddingRequest(text))
            override suspend fun embed(request: EmbeddingRequest): Embedding {
                modelRuns.record(ModelRunInput(
                    provider = "openai", operation = "embed", model = model,
                    sourceDocumentId = request.sourceDocumentId, processingAttemptId = request.processingAttemptId,
                    inputTokens = 50, outputTokens = null, latencyMs = 5,
                    estimatedCost = BigDecimal("0.000001"), success = true,
                ))
                return PipelineServiceTest.FakeEmbeddings.embed(request.text).copy(model = model)
            }
        }
        return PipelineService(
            ingestion = IngestionService(
                providers = listOf(news),
                properties = IngestionProperties(provider = "polygon", fallbackProvider = "none"),
                companies = companyStore, registrations = registrations,
                runs = ingestionRuns, metrics = metrics,
            ),
            extraction = extraction, normalization = normalization,
            clustering = EventClusteringService(
                embeddings = embeddings, clusters = clusters, events = events,
                companies = companyStore, properties = DedupProperties(),
            ),
            persistence = persistence, catalyst = catalyst, documents = documents,
            documentCompanies = documentCompanies, processing = processing,
            companies = companyStore, properties = PipelineProperties(), metrics = metrics,
            recorder = recorder, attempts = attempts, steps = steps,
        )
    }
}
