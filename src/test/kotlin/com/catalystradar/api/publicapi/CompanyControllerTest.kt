package com.catalystradar.api.publicapi

import com.catalystradar.application.company.CompanyService
import com.catalystradar.domain.company.Company
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(CompanyController::class)
class CompanyControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var service: CompanyService

    @Test
    fun `returns company metadata`() {
        `when`(service.findByTicker("DELL")).thenReturn(
            Company(
                ticker = "DELL",
                name = "Dell Technologies Inc. Class C",
                exchange = "NYSE",
                sector = "Industrials",
                industry = "Computer Hardware",
                country = "US",
            ),
        )

        mockMvc.get("/v1/companies/DELL") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.ticker") { value("DELL") }
            jsonPath("$.name") { value("Dell Technologies Inc. Class C") }
            jsonPath("$.exchange") { value("NYSE") }
        }
    }

    @Test
    fun `unknown ticker returns 404`() {
        `when`(service.findByTicker(anyString())).thenReturn(null)

        mockMvc.get("/v1/companies/NOPE") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `invalid ticker returns 400`() {
        `when`(service.findByTicker("!!!")).thenThrow(IllegalArgumentException("invalid ticker: !!!"))

        mockMvc.get("/v1/companies/!!!") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isBadRequest() }
        }
    }
}
