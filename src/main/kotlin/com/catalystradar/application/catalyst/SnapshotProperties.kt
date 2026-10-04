package com.catalystradar.application.catalyst

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "catalyst.snapshots")
class SnapshotProperties(var enabled: Boolean = false, interval: Duration = Duration.ofHours(24)) {
    var interval: Duration = interval
        set(value) { require(!value.isZero && !value.isNegative) { "snapshot interval must be positive" }; field = value }

    init { require(!interval.isZero && !interval.isNegative) { "snapshot interval must be positive" } }
}
