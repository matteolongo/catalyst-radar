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
    fun `dashboard and every static asset are served without a public key`() {
        mockMvc.get("/ops/index.html").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("CatalystRadar")) }
            content { string(org.hamcrest.Matchers.containsString("operations-model.js")) }
        }
        mockMvc.get("/ops/styles.css").andExpect { status { isOk() } }
        mockMvc.get("/ops/app.js").andExpect { status { isOk() } }
        mockMvc.get("/ops/operations-model.js").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("CatalystOperationsModel")) }
        }
        mockMvc.get("/ops/operations.js").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("CatalystOperations")) }
        }
    }

    @Test
    fun `every script and stylesheet the dashboard references is packaged`() {
        val html = mockMvc.get("/ops/index.html").andReturn().response.contentAsString
        val assets = Regex("""<(?:script src|link rel="stylesheet" href)="([^"/:]+\.(?:js|css))"""").findAll(html).map { it.groupValues[1] }.toList()

        kotlin.test.assertTrue("guided.js" in assets, "index.html should reference guided.js, found $assets")
        assets.forEach { asset -> mockMvc.get("/ops/$asset").andExpect { status { isOk() } } }
    }
}
