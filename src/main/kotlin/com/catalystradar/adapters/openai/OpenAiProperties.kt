package com.catalystradar.adapters.openai

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * OpenAI API access for structured extraction (and embeddings in
 * CR-11). The extraction model is pinned configuration, not code:
 * changing it changes cost and behavior and belongs in config review.
 */
@Component
@ConfigurationProperties(prefix = "catalyst.openai")
data class OpenAiProperties(
    var baseUrl: String = "https://api.openai.com",
    var apiKey: String = "",
    var extractionModel: String = "gpt-4o-mini",
    var embeddingModel: String = "text-embedding-3-small",
)
