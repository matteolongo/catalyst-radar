package com.catalystradar.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Assigns one request ID per request, before authentication runs, so the
 * ID in an error body, the response header and the log lines match. A
 * caller-supplied ID is kept only when it is short and log-safe.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val id = resolve(request.getHeader(HEADER))
        request.setAttribute(ATTRIBUTE, id)
        response.setHeader(HEADER, id)
        MDC.put(MDC_KEY, id)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Request-Id"
        const val MDC_KEY = "requestId"
        private const val ATTRIBUTE = "com.catalystradar.requestId"
        private val SAFE_ID = Regex("^[A-Za-z0-9._-]{1,64}$")

        private fun resolve(presented: String?): String =
            presented?.takeIf { SAFE_ID.matches(it) } ?: UUID.randomUUID().toString()

        /** The ID assigned to this request; resolved on the spot if the filter did not run. */
        fun of(request: HttpServletRequest): String =
            request.getAttribute(ATTRIBUTE) as? String ?: resolve(request.getHeader(HEADER))
    }
}
