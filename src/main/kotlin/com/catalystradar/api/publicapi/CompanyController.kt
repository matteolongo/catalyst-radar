package com.catalystradar.api.publicapi

import com.catalystradar.api.dto.CompanyResponse
import com.catalystradar.api.dto.toResponse
import com.catalystradar.api.error.CompanyNotFoundException
import com.catalystradar.application.company.CompanyService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Public company lookup. */
@RestController
@RequestMapping("/v1/companies")
class CompanyController(private val companies: CompanyService) {

    @GetMapping("/{ticker}")
    fun getByTicker(@PathVariable ticker: String): CompanyResponse =
        companies.findByTicker(ticker)?.toResponse()
            ?: throw CompanyNotFoundException(ticker)
}
