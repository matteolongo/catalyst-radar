package com.catalystradar.security

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "catalyst")
data class SecurityProperties(
    var api: Api = Api(),
    var internal: Internal = Internal(),
) {
    data class Api(
        var authEnabled: Boolean = false,
    )

    data class Internal(
        var adminKey: String = "",
    )
}
