package com.catalystradar.api.config

import com.catalystradar.persistence.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(properties = ["catalyst.ui.allowed-origins=https://ops.example"])
@AutoConfigureMockMvc
class UiCorsConfigurationIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `custom dashboard origin can read actuator health`() {
        mockMvc.get("/actuator/health") {
            header("Origin", "https://ops.example")
        }.andExpect {
            status { isOk() }
            header { string("Access-Control-Allow-Origin", "https://ops.example") }
        }
    }
}
