package com.catalystradar.application.clustering

import com.catalystradar.domain.event.CatalystEvent
import com.catalystradar.domain.event.EventType
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "catalyst.dedup")
data class DedupProperties(
    var window: Duration = Duration.ofHours(72),
    var similarityThreshold: Double = 0.86,
    var maxCandidates: Int = 25,
)
