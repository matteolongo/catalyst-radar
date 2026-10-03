package com.catalystradar.api

import com.catalystradar.persistence.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(properties = ["catalyst.api.auth-enabled=true"])
@AutoConfigureMockMvc
class OpsDashboardTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `dashboard and its script are served by the API without a public key`() {
        mockMvc.get("/ops/index.html").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("CatalystRadar")) }
        }
        mockMvc.get("/ops/app.js").andExpect {
            status { isOk() }
        }
    }
}
