package com.catalystradar.api.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Browser access for dashboard clients. Defaults cover local static
 * servers and dev frontends; lock down via
 * catalyst.ui.allowed-origins in real deployments. Serve the dashboard
 * over http — file:// origins are intentionally not allowed.
 */
@Configuration
class WebCorsConfiguration(
    @Value("\${catalyst.ui.allowed-origins:http://localhost:*,http://127.0.0.1:*}")
    private val allowedOrigins: List<String>,
) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOriginPatterns(*allowedOrigins.toTypedArray())
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("Authorization", "X-Admin-Key", "X-Request-Id", "Content-Type", "Accept")
            .maxAge(3600)
    }
}
