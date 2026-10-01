package com.catalystradar.api

import com.catalystradar.persistence.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@AutoConfigureMockMvc
class OpenApiDocsTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `openapi documents the versioned api`() {
        mockMvc.get("/v3/api-docs") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.paths./v1/companies/{ticker}") { exists() }
            jsonPath("$.paths./v1/discovery/catalyzed") { exists() }
            jsonPath("$.paths./v1/events") { exists() }
        }
    }
}
