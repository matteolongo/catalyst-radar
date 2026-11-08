package com.catalystradar.security

import com.catalystradar.domain.company.Company
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.security.ApiClientStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.net.URI

@SpringBootTest(properties = ["catalyst.api.auth-enabled=true", "catalyst.internal.admin-key=test-admin"])
@AutoConfigureMockMvc
@Transactional
class ApiKeyAuthFilterTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var keys: ApiKeyService

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var clients: ApiClientStore

    @Test
    fun `missing key is rejected`() {
        mockMvc.get("/v1/companies/DELL") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_API_KEY") }
        }
    }

    @Test
    fun `valid key passes`() {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        val key = keys.create("backtester")

        mockMvc.get("/v1/companies/DELL") {
            accept = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer ${key.rawKey}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.ticker") { value("DELL") }
        }
    }

    @Test
    fun `health stays public`() {
        mockMvc.get("/actuator/health") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `disabled key is forbidden`() {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        val key = keys.create("backtester")
        clients.setActive(key.clientId, false)

        mockMvc.get("/v1/companies/DELL") {
            accept = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer ${key.rawKey}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("KEY_DISABLED") }
        }
    }

    @Test
    fun `malformed key is rejected`() {
        mockMvc.get("/v1/companies/DELL") {
            accept = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer nope")
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `ordinary options request to an internal route still requires admin key`() {
        mockMvc.options("/internal/ingestion/runs").andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `alternate spellings of protected routes still require keys`() {
        for (path in listOf("/%76%31/companies/DELL", "/v1;scope=test/companies/DELL")) {
            mockMvc.perform(MockMvcRequestBuilders.get(URI.create(path)))
                .andExpect(status().isUnauthorized)
        }
        mockMvc.get("/radar/v1/companies/DELL") {
            contextPath = "/radar"
        }.andExpect {
            status { isUnauthorized() }
        }
        mockMvc.options("/internal;scope=test/ingestion/runs").andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `operators can revoke an issued key more than once`() {
        val key = keys.create("backtester")

        repeat(2) {
            mockMvc.delete("/internal/api-clients/${key.clientId}") {
                header("X-Admin-Key", "test-admin")
            }.andExpect {
                status { isNoContent() }
            }
        }

        mockMvc.get("/v1/companies/DELL") {
            header("Authorization", "Bearer ${key.rawKey}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("KEY_DISABLED") }
        }
    }
}
