package com.catalystradar.application.pipeline

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "catalyst.pipeline")
data class PipelineProperties(
    var batchSize: Int = 50,
    var maxAttempts: Int = 3,
    /** Documents processed in parallel; each one calls the extraction and embedding providers. */
    var concurrency: Int = 1,
    var retryDelay: Duration = Duration.ofMinutes(1),
)
