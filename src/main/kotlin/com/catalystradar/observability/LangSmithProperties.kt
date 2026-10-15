package com.catalystradar.observability

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * LangSmith trace export for LLM calls. Disabled by default so no spans
 * leave the process until an operator opts in. Content capture is a
 * separate switch because article bodies and model output are sensitive:
 * the default exports structure (model, tokens, latency) without raw text.
 */
@Component
@ConfigurationProperties(prefix = "catalyst.langsmith")
data class LangSmithProperties(
    var enabled: Boolean = false,
    var project: String = "catalyst-radar",
    var captureContent: Boolean = false,
)
