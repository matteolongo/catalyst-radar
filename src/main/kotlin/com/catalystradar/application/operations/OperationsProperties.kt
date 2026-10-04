package com.catalystradar.application.operations

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "catalyst.operations")
class OperationsProperties(
    freshnessMultiplier: Long = 2,
    dependencyWindow: Duration = Duration.ofHours(1),
    processingStallAfter: Duration = Duration.ofMinutes(15),
) {
    var freshnessMultiplier: Long = freshnessMultiplier
        set(value) { require(value > 0) { "freshness multiplier must be positive" }; field = value }
    var dependencyWindow: Duration = dependencyWindow
        set(value) { require(!value.isZero && !value.isNegative && value <= Duration.ofDays(7)) { "dependency window must be positive and at most seven days" }; field = value }
    var processingStallAfter: Duration = processingStallAfter
        set(value) { require(!value.isZero && !value.isNegative) { "processing stall threshold must be positive" }; field = value }

    init { validate() }
    fun validate() {
        require(freshnessMultiplier > 0) { "freshness multiplier must be positive" }
        require(!dependencyWindow.isZero && !dependencyWindow.isNegative && dependencyWindow <= Duration.ofDays(7)) { "dependency window must be positive and at most seven days" }
        require(!processingStallAfter.isZero && !processingStallAfter.isNegative) { "processing stall threshold must be positive" }
    }
}
