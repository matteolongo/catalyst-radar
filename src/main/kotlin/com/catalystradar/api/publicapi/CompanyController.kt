package com.catalystradar.api.publicapi

import com.catalystradar.api.dto.CompanyResponse
import com.catalystradar.api.dto.toResponse
import com.catalystradar.application.company.CompanyService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Public company lookup. Error bodies stay minimal here; the shared
 * RFC 9457 error model arrives with CR-14.
 */
@RestController
@RequestMapping("/v1/companies")
class CompanyController(private val companies: CompanyService) {

    @GetMapping("/{ticker}")
    fun getByTicker(@PathVariable ticker: String): CompanyResponse =
        try {
            companies.findByTicker(ticker)?.toResponse()
                ?: throw ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No supported company exists for ticker $ticker",
                )
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
}
