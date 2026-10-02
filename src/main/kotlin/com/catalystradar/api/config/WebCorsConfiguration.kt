package com.catalystradar.api.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

/**
 * Browser access for dashboard clients. Defaults cover local static
 * servers and dev frontends; lock down via
 * catalyst.ui.allowed-origins in real deployments. file:// origins are
 * intentionally not allowed.
 */
@Configuration
class WebCorsConfiguration(
    @Value("\${catalyst.ui.allowed-origins:http://localhost:*,http://127.0.0.1:*}")
    private val allowedOrigins: List<String>,
) {

    @Bean
    fun corsFilter(): CorsFilter {
        val configuration = CorsConfiguration()
        configuration.allowedOriginPatterns = allowedOrigins
        configuration.allowedMethods = listOf("GET", "POST", "OPTIONS")
        configuration.allowedHeaders = listOf("Authorization", "X-Admin-Key", "X-Request-Id", "Content-Type", "Accept")
        configuration.maxAge = 3600
        val source = UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
        return CorsFilter(source)
    }

    @Bean
    fun corsFilterRegistration(corsFilter: CorsFilter): FilterRegistrationBean<CorsFilter> =
        FilterRegistrationBean(corsFilter).apply { order = Ordered.HIGHEST_PRECEDENCE }
}
