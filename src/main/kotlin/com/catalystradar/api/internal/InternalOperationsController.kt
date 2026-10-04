package com.catalystradar.api.internal

import com.catalystradar.api.dto.*
import com.catalystradar.application.ingestion.IngestionStatus
import com.catalystradar.application.operations.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/internal/operations")
class InternalOperationsController(
    private val operations: OperationsService,
    @Qualifier("operationsClock") private val clock: Clock,
) {
    private val windowResolver = ActivityWindowResolver()

    @GetMapping("/config")
    fun config(): ResponseEntity<OperationsConfigResponse> = noStore(operations.config().toResponse())

    @GetMapping("/overview")
    fun overview(
        @RequestParam(required = false) range: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
    ): ResponseEntity<OperationsOverviewResponse> = noStore(operations.overview(resolveWindow(range, from, to)).toResponse())

    @GetMapping("/documents")
    fun documents(
        @RequestParam(name = "status", required = false) statuses: List<DocumentState>?,
        @RequestParam(required = false) provider: String?,
        @RequestParam(required = false) ticker: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "false") dueOnly: Boolean,
        @RequestParam(required = false) runId: UUID?,
        @RequestParam(required = false) ingestionRunId: UUID?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<OperationsPageResponse<DocumentListItemResponse>> {
        val query = DocumentQuery(
            states = statuses.orEmpty().toSet(),
            provider = provider,
            ticker = ticker,
            title = q,
            dueOnly = dueOnly,
            runId = runId,
            ingestionRunId = ingestionRunId,
            from = from,
            to = to,
            page = PageRequest(limit, cursor),
        )
        return noStore(operations.documents(query).toResponse { it.toResponse() })
    }

    @GetMapping("/documents/{id}")
    fun document(@PathVariable id: UUID): ResponseEntity<DocumentDetailResponse> = noStore(operations.document(id).toResponse())

    @GetMapping("/documents/{id}/body")
    fun documentBody(@PathVariable id: UUID): ResponseEntity<DocumentBodyResponse> = noStore(operations.documentBody(id).toResponse())

    @GetMapping("/documents/{id}/events")
    fun documentEvents(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<EventsFeedResponse> {
        val events = operations.documentEvents(id, PageRequest(limit, cursor))
        return noStore(EventsFeedResponse(events = events.events.map { it.toResponse() }, nextCursor = events.nextCursor))
    }

    @GetMapping("/documents/{id}/attempts")
    fun documentAttempts(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<OperationsPageResponse<ProcessingAttemptResponse>> = noStore(
        operations.documentAttempts(id, PageRequest(limit, cursor)).toResponse { it.toResponse() },
    )

    @GetMapping("/documents/{id}/model-runs")
    fun documentModelRuns(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<OperationsPageResponse<ModelCallResponse>> = noStore(
        operations.documentModelRuns(id, PageRequest(limit, cursor)).toResponse { it.toResponse() },
    )

    @GetMapping("/runs")
    fun runs(
        @RequestParam(required = false) range: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) kind: OperationKind?,
        @RequestParam(required = false) status: OperationStatus?,
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<OperationsPageResponse<OperationRunResponse>> {
        val query = RunQuery(resolveWindow(range, from, to), kind, status, PageRequest(limit, cursor))
        return noStore(operations.runs(query).toResponse { it.toResponse() })
    }

    @GetMapping("/runs/{id}")
    fun run(@PathVariable id: UUID): ResponseEntity<OperationRunDetailResponse> = noStore(operations.run(id).toResponse())

    @GetMapping("/runs/{id}/issues")
    fun runIssues(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<OperationsPageResponse<OperationIssueResponse>> = noStore(
        operations.runIssues(id, PageRequest(limit, cursor)).toResponse { it.toResponse() },
    )

    @GetMapping("/ingestion-runs")
    fun ingestionRuns(
        @RequestParam(required = false) range: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) provider: String?,
        @RequestParam(required = false) status: IngestionStatus?,
        @RequestParam(required = false) runId: UUID?,
        @RequestParam(required = false) ingestionRunId: UUID?,
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<OperationsPageResponse<IngestionRunResponse>> {
        val page = PageRequest(limit, cursor)
        val query = if (ingestionRunId != null) {
            require(range == null && from == null && to == null) { "exact ingestion lookup cannot include a window" }
            IngestionQuery(window = null, provider = provider, status = status, runId = runId, ingestionRunId = ingestionRunId, page = page)
        } else {
            val window = if (range == null && from == null && to == null && runId != null) null else resolveWindow(range, from, to)
            IngestionQuery(window = window, provider = provider, status = status, runId = runId, page = page)
        }
        return noStore(operations.ingestionRuns(query).toResponse { it.toResponse() })
    }

    @GetMapping("/model-runs")
    fun modelRuns(
        @RequestParam(required = false) range: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) provider: String?,
        @RequestParam(required = false) operation: String?,
        @RequestParam(required = false) model: String?,
        @RequestParam(required = false) success: Boolean?,
        @RequestParam(required = false) documentId: UUID?,
        @RequestParam(required = false) attemptId: UUID?,
        @RequestParam(required = false) runId: UUID?,
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<OperationsPageResponse<ModelCallResponse>> {
        val query = modelQuery(range, from, to, provider, operation, model, success, documentId, attemptId, runId, limit, cursor)
        return noStore(operations.modelRuns(query).toResponse { it.toResponse() })
    }

    @GetMapping("/model-runs/{id}")
    fun modelRun(@PathVariable id: UUID): ResponseEntity<ModelCallResponse> = noStore(operations.modelRun(id).toResponse())

    @GetMapping("/model-summary")
    fun modelSummary(
        @RequestParam(required = false) range: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) provider: String?,
        @RequestParam(required = false) operation: String?,
        @RequestParam(required = false) model: String?,
        @RequestParam(required = false) success: Boolean?,
        @RequestParam(required = false) documentId: UUID?,
        @RequestParam(required = false) attemptId: UUID?,
        @RequestParam(required = false) runId: UUID?,
    ): ResponseEntity<ModelSummaryResponse> = noStore(
        operations.modelSummary(modelQuery(range, from, to, provider, operation, model, success, documentId, attemptId, runId)).toResponse(),
    )

    private fun modelQuery(
        range: String?,
        from: Instant?,
        to: Instant?,
        provider: String?,
        operation: String?,
        model: String?,
        success: Boolean?,
        documentId: UUID?,
        attemptId: UUID?,
        runId: UUID?,
        limit: Int = 25,
        cursor: String? = null,
    ) = ModelQuery(
        window = resolveWindow(range, from, to),
        provider = provider,
        operation = operation,
        model = model,
        success = success,
        documentId = documentId,
        attemptId = attemptId,
        runId = runId,
        page = PageRequest(limit, cursor),
    )

    private fun resolveWindow(range: String?, from: Instant?, to: Instant?): ActivityWindow {
        val preset = when (range) {
            null -> null
            "24h" -> WindowPreset.HOURS_24
            "7d" -> WindowPreset.DAYS_7
            else -> throw IllegalArgumentException("range must be 24h or 7d")
        }
        return windowResolver.resolve(preset, from, to, clock.instant())
    }

    private fun <T : Any> noStore(body: T): ResponseEntity<T> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
