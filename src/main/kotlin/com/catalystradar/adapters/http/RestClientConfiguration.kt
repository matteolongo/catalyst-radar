package com.catalystradar.adapters.http

import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.web.client.RestClient

/**
 * Prototype REST client builder. Each adapter sets its own base URL on
 * injection; a shared singleton builder would leak it across adapters.
 */
@Configuration
class RestClientConfiguration {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    fun restClientBuilder(): RestClient.Builder = RestClient.builder()
}
