package com.catalystradar.application.universe

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "catalyst.universe")
data class UniverseProperties(
    var seedOnStartup: Boolean = false,
    /** Every active company costs provider and model calls each cycle; promotion stops here. */
    var maxActiveCompanies: Int = 1000,
)
