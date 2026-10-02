package com.catalystradar.api.config

import com.catalystradar.application.company.CompanyService
import com.catalystradar.domain.company.Company
import com.catalystradar.security.ApiKeyService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options

@WebMvcTest(com.catalystradar.api.publicapi.CompanyController::class)
class WebCorsConfigurationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var service: CompanyService

    @MockitoBean
    private lateinit var apiKeys: ApiKeyService

    @Test
    fun `allowed dashboard origin receives cors headers`() {
        `when`(service.findByTicker("DELL")).thenReturn(Company(ticker = "DELL", name = "Dell"))

        mockMvc.get("/v1/companies/DELL") {
            accept = MediaType.APPLICATION_JSON
            header("Origin", "http://localhost:5173")
        }.andExpect {
            status { isOk() }
            header { string("Access-Control-Allow-Origin", "http://localhost:5173") }
        }
    }

    @Test
    fun `foreign origin is rejected`() {
        `when`(service.findByTicker("DELL")).thenReturn(Company(ticker = "DELL", name = "Dell"))

        mockMvc.get("/v1/companies/DELL") {
            accept = MediaType.APPLICATION_JSON
            header("Origin", "https://evil.example")
        }.andExpect {
            status { isForbidden() }
            header { doesNotExist("Access-Control-Allow-Origin") }
        }
    }

    @Test
    fun `preflight passes through to cors handling`() {
        mockMvc.options("/v1/companies/DELL") {
            header("Origin", "http://localhost:8090")
            header("Access-Control-Request-Method", "GET")
        }.andExpect {
            status { isOk() }
            header { string("Access-Control-Allow-Origin", "http://localhost:8090") }
        }
    }
}
