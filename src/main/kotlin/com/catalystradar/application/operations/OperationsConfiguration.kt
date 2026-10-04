package com.catalystradar.application.operations

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class OperationsConfiguration {
    @Bean("operationsClock")
    fun operationsClock(): Clock = Clock.systemUTC()
}
