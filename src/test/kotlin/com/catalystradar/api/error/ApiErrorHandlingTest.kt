package com.catalystradar.api.error

import com.catalystradar.api.internal.InternalReplayController
import com.catalystradar.api.publicapi.CompanyController
import com.catalystradar.application.company.CompanyService
import com.catalystradar.application.replay.ReplayService
import com.catalystradar.common.InvalidInputException
import com.catalystradar.ports.EventExtractionProvider
import com.catalystradar.security.ApiKeyService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

@WebMvcTest(
    CompanyController::class,
    InternalReplayController::class,
    properties = ["catalyst.internal.admin-key=test-admin"],
)
class ApiErrorHandlingTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var companies: CompanyService

    @MockitoBean
    private lateinit var replay: ReplayService

    @MockitoBean
    private lateinit var extraction: EventExtractionProvider

    @MockitoBean
    private lateinit var keys: ApiKeyService

    @Test
    fun `unexpected failure returns the request id it was logged under`() {
        whenever(companies.findByTicker("DELL")).thenThrow(IllegalStateException("boom: internal detail"))

        val response = mockMvc.get("/v1/companies/DELL") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value("INTERNAL_ERROR") }
        }.andReturn().response

        val header = requireNotNull(response.getHeader("X-Request-Id"))
        assertEquals(header, Regex("\"requestId\":\"([^\"]+)\"").find(response.contentAsString)?.groupValues?.get(1))
        assertFalse(response.contentAsString.contains("internal detail"))
    }

    @Test
    fun `a valid supplied request id is echoed`() {
        whenever(companies.findByTicker("NOPE")).thenReturn(null)

        mockMvc.get("/v1/companies/NOPE") {
            accept = MediaType.APPLICATION_JSON
            header("X-Request-Id", "client-trace.42")
        }.andExpect {
            status { isNotFound() }
            header { string("X-Request-Id", "client-trace.42") }
            jsonPath("$.requestId") { value("client-trace.42") }
        }
    }

    @Test
    fun `an unsafe supplied request id is replaced`() {
        whenever(companies.findByTicker("NOPE")).thenReturn(null)

        val response = mockMvc.get("/v1/companies/NOPE") {
            accept = MediaType.APPLICATION_JSON
            header("X-Request-Id", "bad id\nforged-log-line")
        }.andReturn().response

        assertNotEquals("bad id\nforged-log-line", response.getHeader("X-Request-Id"))
    }

    @Test
    fun `internal invariant failure is a server error without its message`() {
        whenever(companies.findByTicker("DELL")).thenThrow(IllegalArgumentException("internal invariant detail"))

        val response = mockMvc.get("/v1/companies/DELL") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value("INTERNAL_ERROR") }
        }.andReturn().response

        assertFalse(response.contentAsString.contains("internal invariant detail"))
    }

    @Test
    fun `invalid caller input is a bad request with its message`() {
        whenever(companies.findByTicker("DELL")).thenThrow(InvalidInputException("invalid ticker"))

        mockMvc.get("/v1/companies/DELL") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
            jsonPath("$.detail") { value("invalid ticker") }
        }
    }

    @Test
    fun `unsupported method is reported as method not allowed`() {
        mockMvc.post("/v1/companies/DELL") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isMethodNotAllowed() }
            jsonPath("$.code") { value("METHOD_NOT_ALLOWED") }
        }
    }

    @Test
    fun `unknown route is reported as not found`() {
        mockMvc.get("/v1/no-such-route") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    fun `malformed request body is a bad request`() {
        mockMvc.post("/internal/replays") {
            accept = MediaType.APPLICATION_JSON
            contentType = MediaType.APPLICATION_JSON
            header("X-Admin-Key", "test-admin")
            content = "{"
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }
}
