package com.catalystradar.api.internal

import com.catalystradar.api.dto.IngestionRunsResponse
import com.catalystradar.api.dto.ModelRunsResponse
import com.catalystradar.api.dto.toResponse
import com.catalystradar.persistence.extraction.ModelRunStore
import com.catalystradar.persistence.ingestion.IngestionRunStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Run-history reads for operators: ingestion runs plus the model
 * invocations (tokens, latency, cost) behind them. Same admin-key
 * gate as all other internal routes.
 */
@RestController
@RequestMapping("/internal")
class InternalRunsController(
    private val ingestionRuns: IngestionRunStore,
    private val modelRuns: ModelRunStore,
) {

    @GetMapping("/ingestion/runs")
    fun ingestionRuns(@RequestParam(defaultValue = "20") limit: Int): IngestionRunsResponse {
        require(limit in 1..100) { "limit must be within 1..100" }
        return IngestionRunsResponse(ingestionRuns.listRecent(limit).map { it.toResponse() })
    }

    @GetMapping("/model-runs")
    fun modelRuns(@RequestParam(defaultValue = "50") limit: Int): ModelRunsResponse {
        require(limit in 1..200) { "limit must be within 1..200" }
        return ModelRunsResponse(modelRuns.listRecent(limit).map { it.toResponse() })
    }
}
