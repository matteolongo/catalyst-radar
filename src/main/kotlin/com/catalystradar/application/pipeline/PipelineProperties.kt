package com.catalystradar.application.pipeline

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "catalyst.pipeline")
data class PipelineProperties(
    var batchSize: Int = 50,
    var maxAttempts: Int = 3,
    var retryDelay: Duration = Duration.ofMinutes(1),
)
