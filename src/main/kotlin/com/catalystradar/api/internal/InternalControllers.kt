package com.catalystradar.api.internal

import com.catalystradar.api.dto.CatalystResponse
import com.catalystradar.api.dto.CreateApiClientRequest
import com.catalystradar.api.dto.CreatedApiClientResponse
import com.catalystradar.api.dto.PipelineResultResponse
import com.catalystradar.api.dto.toResponse
import com.catalystradar.application.catalyst.CatalystService
import com.catalystradar.application.catalyst.CatalystViewService
import com.catalystradar.application.company.CompanyNotFoundException
import com.catalystradar.application.company.CompanyService
import com.catalystradar.application.pipeline.PipelineService
import com.catalystradar.security.ApiKeyService
import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Protected operations. Every route here requires the admin key (see
 * the auth filter); these never participate in public API versioning.
 */
@RestController
@RequestMapping("/internal/ingestion")
class InternalPipelineController(private val pipeline: PipelineService) {

    @PostMapping("/runs")
    fun runIngestion(): PipelineResultResponse =
        runBlocking { pipeline.runCycle().toResponse() }
}

@RestController
@RequestMapping("/internal/companies/{ticker}")
class InternalCompanyController(
    private val companies: CompanyService,
    private val catalyst: CatalystService,
    private val views: CatalystViewService,
) {

    @PostMapping("/recalculate")
    fun recalculate(@PathVariable ticker: String): CatalystResponse {
        val company = companies.findByTicker(ticker) ?: throw CompanyNotFoundException(ticker)
        catalyst.recalculate(company.id)
        return views.view(company.ticker).toResponse()
    }
}

@RestController
@RequestMapping("/internal/api-clients")
class InternalApiClientController(private val keys: ApiKeyService) {

    @PostMapping
    fun create(@RequestBody request: CreateApiClientRequest): CreatedApiClientResponse {
        require(request.name.isNotBlank()) { "name must not be blank" }
        return keys.create(request.name.trim()).toResponse()
    }
}
