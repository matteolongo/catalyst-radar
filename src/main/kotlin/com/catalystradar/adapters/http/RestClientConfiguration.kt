package com.catalystradar.adapters.http

import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.web.client.RestClient

/**
 * Prototype REST client builder. Each adapter sets its own base URL on
 * injection; a shared singleton builder would leak it across adapters.
 * Every client records `http.client.requests` through the application's
 * observation registry.
 */
@Configuration
class RestClientConfiguration {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    fun restClientBuilder(observations: ObjectProvider<ObservationRegistry>): RestClient.Builder =
        RestClient.builder()
            .observationRegistry(observations.getIfAvailable { ObservationRegistry.NOOP })
            .observationConvention(ProviderHttpObservationConvention())
}
