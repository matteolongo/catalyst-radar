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
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional

@SpringBootTest(properties = ["catalyst.api.auth-enabled=true"])
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
}
