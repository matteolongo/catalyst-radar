package com.catalystradar.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/**
 * API-key gate for public endpoints. Actuator stays public for
 * operations; internal endpoints always demand the admin key, even in
 * local development. Public-key auth itself is off by default so local
 * development needs no keys until auth is explicitly enabled.
 */
@Component
class ApiKeyAuthFilter(
    private val keys: ApiKeyService,
    private val environment: Environment,
    private val mapper: ObjectMapper,
) : OncePerRequestFilter() {

    private val authEnabled: Boolean
        get() = environment.getProperty("catalyst.api.auth-enabled", Boolean::class.java, false)

    private val adminKey: String
        get() = environment.getProperty("catalyst.internal.admin-key", "")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        // CORS preflights carry no credentials by design; Spring's CORS
        // handling answers them and the real request is still checked.
        if (request.method == "OPTIONS") {
            chain.doFilter(request, response)
            return
        }
        val path = request.requestURI
        when {
            path == "/actuator" || path.startsWith("/actuator/") -> chain.doFilter(request, response)
            path.startsWith("/internal/") -> checkAdmin(request, response, chain)
            path.startsWith("/v1/") && authEnabled -> checkApiKey(request, response, chain)
            else -> chain.doFilter(request, response)
        }
    }

    private fun checkApiKey(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val rawKey = request.getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
        when (val result = rawKey?.let { keys.authenticate(it) }) {
            is ApiKeyAuthResult.Authenticated -> chain.doFilter(request, response)
            is ApiKeyAuthResult.DisabledKey -> deny(request, response, HttpStatus.FORBIDDEN, "KEY_DISABLED", "API key is disabled")
            else -> deny(request, response, HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "Valid API key required")
        }
    }

    private fun checkAdmin(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val configured = adminKey
        val presented = request.getHeader("X-Admin-Key")
        if (configured.isNotBlank() && presented != null && constantEquals(configured, presented)) {
            chain.doFilter(request, response)
        } else {
            deny(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Internal endpoint requires the admin key")
        }
    }

    private fun constantEquals(expected: String, actual: String): Boolean =
        java.security.MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())

    private fun deny(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        code: String,
        detail: String,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        val requestId = request.getHeader("X-Request-Id")?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()
        mapper.writeValue(
            response.outputStream,
            mapOf(
                "type" to "https://catalystradar.dev/problems/${code.lowercase().replace('_', '-')}",
                "title" to status.reasonPhrase,
                "status" to status.value(),
                "code" to code,
                "detail" to detail,
                "requestId" to requestId,
            ),
        )
    }
}
